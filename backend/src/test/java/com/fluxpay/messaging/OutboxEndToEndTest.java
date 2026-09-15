package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.common.contracts.TransportPort;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentEventStore;
import com.fluxpay.service.PaymentEventIngestionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirmation -&gt; outbox -&gt; broker -&gt; timeline without a live broker: the relay publishes
 * the exact stored envelope bytes and the ingestion service persists them idempotently.
 */
class OutboxEndToEndTest {
  static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final EventEnvelopeCodec codec = new EventEnvelopeCodec(mapper);

  LocalContainerEntityManagerFactoryBean factory;
  JpaTransactionManager manager;
  TransactionTemplate tx;
  OutboxEventRepository events;
  OutboxDeliveryRepository deliveries;

  static class CapturingTransport implements TransportPort {
    final List<String> topics = new ArrayList<>();
    final List<String> payloads = new ArrayList<>();
    final List<String> keys = new ArrayList<>();

    public void send(String topic, String payload, String key) {
      topics.add(topic);
      payloads.add(payload);
      keys.add(key);
    }
  }

  static class MemoryStore implements PaymentEventStore {
    final List<PaymentEvent> rows = new ArrayList<>();

    public boolean appendIfAbsent(PaymentEvent event) {
      if (rows.stream().anyMatch(e -> e.eventId().equals(event.eventId()))) {
        return false;
      }
      rows.add(event);
      return true;
    }

    public List<PaymentEvent> timeline(String paymentId) {
      return rows.stream().filter(e -> e.paymentId().equals(paymentId)).toList();
    }

    public boolean contains(String paymentId, String eventType) {
      return rows.stream()
          .anyMatch(e -> e.paymentId().equals(paymentId) && e.eventType().equals(eventType));
    }
  }

  @BeforeEach
  void setup() {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:e2e-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", ""));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(
        PersistenceManagedTypes.of(OutboxEvent.class.getName(), OutboxDelivery.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    manager = new JpaTransactionManager(factory.getObject());
    tx = new TransactionTemplate(manager);
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    events = repositories.getRepository(OutboxEventRepository.class);
    deliveries = repositories.getRepository(OutboxDeliveryRepository.class);
  }

  @AfterEach
  void close() {
    factory.destroy();
  }

  @SuppressWarnings("unchecked")
  <T> T proxy(T service) {
    var proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }

  @Test
  void confirmationOutboxRelayReachesTimelineIdempotently() {
    UUID paymentId = UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    var envelope =
        PaymentEventEnvelope.create(
            EventTopics.PAYMENT_INITIATED,
            eventId,
            paymentId.toString(),
            "corr-e2e-1",
            NOW,
            1,
            1,
            Map.of("status", "PROCESSING", "summary", "confirmed"));
    var outbox = proxy(new OutboxService(events, deliveries, codec, clock));
    tx.executeWithoutResult(s -> outbox.enqueue(envelope, 1));

    var transport = new CapturingTransport();
    var claims = proxy(new OutboxClaimService(deliveries, events, clock));
    var relay = new OutboxRelay(deliveries, events, transport, clock, claims, 60);
    assertThat(relay.relayOnce(10)).isEqualTo(1);
    assertThat(transport.topics).containsExactly("payment.initiated");
    assertThat(transport.keys).containsExactly(paymentId.toString());
    // Relay publishes the exact stored envelope bytes.
    String storedJson =
        tx.execute(s -> events.findById(UUID.fromString(eventId)).orElseThrow().payload());
    assertThat(transport.payloads.get(0)).isEqualTo(storedJson);

    var store = new MemoryStore();
    var ingestion = new PaymentEventIngestionService(codec, store);
    assertThat(ingestion.ingest(transport.topics.get(0), transport.payloads.get(0))).isTrue();
    // Duplicate broker delivery is acknowledged but stored once.
    assertThat(ingestion.ingest(transport.topics.get(0), transport.payloads.get(0))).isFalse();
    assertThat(store.rows).hasSize(1);
    assertThat(store.rows.get(0).eventId().toString()).isEqualTo(eventId);
    assertThat(store.rows.get(0).correlationId()).isEqualTo("corr-e2e-1");
  }

  @Test
  void reviewRequestEnvelopeReachesTimeline() {
    UUID paymentId = UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    var envelope =
        PaymentEventEnvelope.create(
            EventTopics.PAYMENT_REVIEW_REQUESTED,
            eventId,
            paymentId.toString(),
            "corr-review-1",
            NOW,
            1,
            1,
            Map.of("status", "UNDER_REVIEW"));
    var outbox = proxy(new OutboxService(events, deliveries, codec, clock));
    tx.executeWithoutResult(s -> outbox.enqueue(envelope, 1));

    var transport = new CapturingTransport();
    var claims = proxy(new OutboxClaimService(deliveries, events, clock));
    var relay = new OutboxRelay(deliveries, events, transport, clock, claims, 60);
    assertThat(relay.relayOnce(10)).isEqualTo(1);

    var store = new MemoryStore();
    var ingestion = new PaymentEventIngestionService(codec, store);
    assertThat(ingestion.ingest(transport.topics.get(0), transport.payloads.get(0))).isTrue();
    assertThat(store.rows.get(0).eventType()).isEqualTo("payment.review.requested");
  }
}
