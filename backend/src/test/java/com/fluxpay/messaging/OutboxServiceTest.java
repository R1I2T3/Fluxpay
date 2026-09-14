package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class OutboxServiceTest {
  static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final EventEnvelopeCodec codec = new EventEnvelopeCodec(mapper);

  LocalContainerEntityManagerFactoryBean factory;
  JpaTransactionManager manager;
  TransactionTemplate tx;
  OutboxEventRepository events;
  OutboxDeliveryRepository deliveries;
  PaymentRepository payments;
  RecipientRepository recipients;
  OutboxService outbox;

  final UUID user = UUID.randomUUID();
  final UUID paymentId = UUID.randomUUID();

  @BeforeEach
  void setup() {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:outbox-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1",
            "sa",
            ""));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(
        PersistenceManagedTypes.of(
            Payment.class.getName(),
            Recipient.class.getName(),
            OutboxEvent.class.getName(),
            OutboxDelivery.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    manager = new JpaTransactionManager(factory.getObject());
    tx = new TransactionTemplate(manager);
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    events = repositories.getRepository(OutboxEventRepository.class);
    deliveries = repositories.getRepository(OutboxDeliveryRepository.class);
    payments = repositories.getRepository(PaymentRepository.class);
    recipients = repositories.getRepository(RecipientRepository.class);
    var target = new OutboxService(events, deliveries, codec, clock);
    var proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    outbox = (OutboxService) proxy.getProxy();
    var recipient =
        new Recipient(
            UUID.randomUUID(), user, "A", "acct", "Bank", "IN", "INR", RecipientStatus.ACTIVE, NOW);
    var payment =
        new Payment(
            paymentId,
            user,
            UUID.randomUUID(),
            recipient,
            new BigDecimal("100.0000"),
            "USD",
            "INR",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{}",
            NOW);
    tx.executeWithoutResult(
        s -> {
          recipients.save(recipient);
          payments.save(payment);
        });
  }

  @AfterEach
  void close() {
    factory.destroy();
  }

  @Test
  void enqueuePersistsStableEventAndDeliveryInCallerTransaction() {
    String eventId = UUID.randomUUID().toString();
    var envelope =
        PaymentEventEnvelope.create(
            EventTopics.PAYMENT_INITIATED,
            eventId,
            paymentId.toString(),
            "corr-1",
            NOW,
            1,
            3,
            Map.of("summary", "confirmed"));
    AtomicReference<String> returned = new AtomicReference<>();
    tx.executeWithoutResult(
        s -> {
          returned.set(outbox.enqueue(envelope, 3));
        });

    assertThat(returned.get()).isEqualTo(eventId);
    var storedEvent = events.findById(UUID.fromString(eventId)).orElseThrow();
    assertThat(storedEvent.topic()).isEqualTo("payment.initiated");
    var decoded = codec.read(storedEvent.payload());
    assertThat(decoded.eventType()).isEqualTo("payment.initiated");
    assertThat(decoded.eventId()).isEqualTo(eventId);
    assertThat(decoded.correlationId()).isEqualTo("corr-1");
    assertThat(decoded.payload()).containsEntry("schemaVersion", 1);
    assertThat(decoded.payload()).containsEntry("aggregateSequence", 3);
    var delivery = deliveries.findById(UUID.fromString(eventId)).orElseThrow();
    assertThat(delivery.paymentId()).isEqualTo(paymentId);
    assertThat(delivery.aggregateSequence()).isEqualTo(3);
    assertThat(delivery.state()).isEqualTo("PENDING");
  }
}
