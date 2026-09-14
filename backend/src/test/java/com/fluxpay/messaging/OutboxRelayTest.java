package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.common.contracts.TransportPort;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

class OutboxRelayTest {
  static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  final Clock clock =
      new Clock() {
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
          return now.get();
        }
      };
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final EventEnvelopeCodec codec = new EventEnvelopeCodec(mapper);

  LocalContainerEntityManagerFactoryBean factory;
  JpaTransactionManager manager;
  TransactionTemplate tx;
  OutboxEventRepository events;
  OutboxDeliveryRepository deliveries;

  static class RecordingTransport implements TransportPort {
    final List<String> sentTopics = new ArrayList<>();
    final List<String> sentPayloads = new ArrayList<>();
    volatile boolean fail = false;

    public void send(String topic, String payload, String key) throws Exception {
      if (fail) {
        throw new RuntimeException("broker unavailable");
      }
      synchronized (this) {
        sentTopics.add(topic);
        sentPayloads.add(payload);
      }
    }
  }

  RecordingTransport transport = new RecordingTransport();
  OutboxRelay relay;
  OutboxService outbox;

  @BeforeEach
  void setup() {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:relay-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", ""));
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
    outbox = proxy(new OutboxService(events, deliveries, codec, clock));
    var claimService = proxy(new OutboxClaimService(deliveries, events, clock));
    relay = new OutboxRelay(deliveries, events, transport, clock, claimService, 60);
  }

  @SuppressWarnings("unchecked")
  <T> T proxy(T service) {
    var proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }

  @AfterEach
  void close() {
    factory.destroy();
  }

  private String enqueue(UUID paymentId, int sequence, String topic) {
    String eventId = UUID.randomUUID().toString();
    var envelope =
        PaymentEventEnvelope.create(
            topic,
            eventId,
            paymentId.toString(),
            "corr-" + sequence,
            now.get(),
            1,
            sequence,
            Map.of("summary", "s" + sequence));
    final String[] held = new String[1];
    tx.executeWithoutResult(s -> held[0] = outbox.enqueue(envelope, sequence));
    return held[0];
  }

  @Test
  void publishesPendingAndMarksSent() {
    UUID payment = UUID.randomUUID();
    enqueue(payment, 1, EventTopics.PAYMENT_INITIATED);
    int sent = relay.relayOnce(10);
    assertThat(sent).isEqualTo(1);
    assertThat(transport.sentTopics).containsExactly("payment.initiated");
    tx.executeWithoutResult(
        s -> {
          assertThat(deliveries.findAll())
              .singleElement()
              .satisfies(
                  d -> {
                    assertThat(d.state()).isEqualTo("SENT");
                  });
        });
  }

  @Test
  void brokerFailureSchedulesRetryAndRecovers() {
    UUID payment = UUID.randomUUID();
    enqueue(payment, 1, EventTopics.PAYMENT_INITIATED);
    transport.fail = true;
    assertThat(relay.relayOnce(10)).isEqualTo(0);
    assertThat(transport.sentTopics).isEmpty();
    tx.executeWithoutResult(
        s -> {
          var d = deliveries.findAll().get(0);
          assertThat(d.state()).isEqualTo("PENDING");
          assertThat(d.attemptCount()).isEqualTo(1);
        });
    // Advance past backoff (1s for first retry) then recover.
    now.set(NOW.plus(Duration.ofSeconds(5)));
    transport.fail = false;
    assertThat(relay.relayOnce(10)).isEqualTo(1);
    assertThat(transport.sentTopics).hasSize(1);
  }

  @Test
  void expiredSendingLeaseIsReclaimed() {
    UUID payment = UUID.randomUUID();
    String id = enqueue(payment, 1, EventTopics.PAYMENT_INITIATED);
    // Simulate worker crash after claim: SENDING with expired lease.
    tx.executeWithoutResult(
        s -> {
          var d = deliveries.findById(UUID.fromString(id)).orElseThrow();
          d.claim("crashed-token", NOW.plusSeconds(1));
          deliveries.saveAndFlush(d);
        });
    // Before expiry, not eligible.
    assertThat(relay.relayOnce(10)).isEqualTo(0);
    assertThat(transport.sentTopics).isEmpty();
    // After lease expiry, reclaimed and published.
    now.set(NOW.plus(Duration.ofSeconds(120)));
    assertThat(relay.relayOnce(10)).isEqualTo(1);
    assertThat(transport.sentTopics).hasSize(1);
  }

  @Test
  void secondEventMustNotOvertakeEarlierPending() {
    UUID payment = UUID.randomUUID();
    enqueue(payment, 1, EventTopics.PAYMENT_INITIATED);
    enqueue(payment, 2, EventTopics.PAYOUT_SUBMITTED);
    // Make first delivery not yet due (future nextAttemptAt) so only second looks eligible
    // if ordering were ignored. Ordering must still block the second.
    tx.executeWithoutResult(
        s -> {
          var all = deliveries.findByPaymentIdOrderByAggregateSequenceAsc(payment);
          assertThat(all).hasSize(2);
        });
    // Fail the first publish so it stays PENDING with backoff, then ensure second is blocked.
    transport.fail = true;
    now.set(NOW);
    relay.relayOnce(10);
    transport.fail = false;
    // First is PENDING with nextAttemptAt = NOW+1s; advance only 0s -> nothing due.
    // Advance 5s so both are due; relay must send first before second in same or next batches.
    now.set(NOW.plus(Duration.ofSeconds(5)));
    transport.sentPayloads.clear();
    transport.sentTopics.clear();
    int sent = relay.relayOnce(1);
    assertThat(sent).isEqualTo(1);
    // The single sent batch must be the earlier sequence.
    var firstPayload = codec.read(transport.sentPayloads.get(0));
    assertThat(firstPayload.payload().get("aggregateSequence")).isEqualTo(1);
    // Now the second can go.
    assertThat(relay.relayOnce(10)).isEqualTo(1);
    var secondPayload = codec.read(transport.sentPayloads.get(1));
    assertThat(secondPayload.payload().get("aggregateSequence")).isEqualTo(2);
  }

  @Test
  void acknowledgementLossRedeliversSameEventId() {
    UUID payment = UUID.randomUUID();
    String id = enqueue(payment, 1, EventTopics.PAYMENT_INITIATED);
    // First relay publishes but the mark is lost: simulate by re-opening as SENDING expired.
    assertThat(relay.relayOnce(10)).isEqualTo(1);
    assertThat(transport.sentPayloads).hasSize(1);
    String firstJson = transport.sentPayloads.get(0);
    // Lose the acknowledgement: flip back to SENDING with an expired lease (crash after publish).
    tx.executeWithoutResult(
        s -> {
          var d = deliveries.findById(UUID.fromString(id)).orElseThrow();
          // markSent had run; rewind to simulate lost commit, keeping same event.
          org.springframework.test.util.ReflectionTestUtils.setField(d, "state", "SENDING");
          d.claim("lost-ack-token", NOW.minusSeconds(1));
          deliveries.saveAndFlush(d);
        });
    now.set(NOW.plus(Duration.ofSeconds(120)));
    assertThat(relay.relayOnce(10)).isEqualTo(1);
    assertThat(transport.sentPayloads).hasSize(2);
    // Duplicate delivery carries the same durable event ID.
    assertThat(codec.read(transport.sentPayloads.get(1)).eventId()).isEqualTo(id);
    assertThat(codec.read(firstJson).eventId()).isEqualTo(id);
  }

  @Test
  void twoConcurrentDispatchersDoNotDoublePublish() throws Exception {
    UUID payment = UUID.randomUUID();
    enqueue(payment, 1, EventTopics.PAYMENT_INITIATED);
    enqueue(UUID.randomUUID(), 1, EventTopics.PAYMENT_INITIATED);
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () -> {
                start.await();
                return relay.relayOnce(10);
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return relay.relayOnce(10);
              });
      start.countDown();
      int total =
          a.get(10, java.util.concurrent.TimeUnit.SECONDS)
              + b.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(total).isEqualTo(2);
      assertThat(transport.sentPayloads).hasSize(2);
      // Each durable event published exactly once despite concurrent claims.
      var ids = transport.sentPayloads.stream().map(j -> codec.read(j).eventId()).toList();
      assertThat(ids).doesNotHaveDuplicates();
    } finally {
      pool.shutdownNow();
    }
  }
}
