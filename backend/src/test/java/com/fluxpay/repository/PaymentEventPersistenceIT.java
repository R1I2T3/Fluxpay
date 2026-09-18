package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.beans.User;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.ConfirmPaymentRequest;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.messaging.EventEnvelopeCodec;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.messaging.OutboxRelay;
import com.fluxpay.messaging.PaymentEventEnvelope;
import com.fluxpay.messaging.PaymentEventPayload;
import com.fluxpay.service.PaymentConfirmationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "fluxpay.outbox.dispatch-initial-delay-ms=600000")
@ActiveProfiles("oracle-it")
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS_ACTIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ORACLE_TEST_JDBC_URL", matches = ".+")
class PaymentEventPersistenceIT {

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired PaymentEventRepository repository;
  @Autowired PaymentConfirmationService confirmation;
  @Autowired OutboxRelay relay;
  @Autowired OutboxDeliveryRepository deliveries;
  @Autowired OutboxEventRepository outboxEvents;
  @Autowired PaymentRepository payments;
  @Autowired PaymentQuoteRepository quotes;
  @Autowired RecipientRepository recipients;
  @Autowired TransferRouteRepository routes;
  @Autowired TransferProviderRepository providers;
  @Autowired UserRepository users;
  @Autowired WalletRepository wallets;
  @Autowired JdbcTemplate jdbc;

  @MockBean KycGate kyc;
  @MockBean ComplianceAssessor compliance;
  @MockBean PostingPort posting;
  private final EventEnvelopeCodec codec =
      new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());

  @BeforeAll
  static void requireDedicatedSchema() {
    assertThat(System.getenv("ORACLE_TEST_USERNAME")).isEqualToIgnoringCase("FLUXPAY_TEST");
  }

  @Test
  void consumerPersistsAProducedEventRowAndAcksDuplicates() throws Exception {
    String paymentId = "IT-" + java.util.UUID.randomUUID();
    PaymentEventPayload payload =
        PaymentEventPayload.random(
            paymentId, Instant.parse("2026-09-04T10:00:00Z"), Map.of("attempt", 1));
    String json =
        codec.write(
            PaymentEventEnvelope.create(
                EventTopics.PAYOUT_SUBMITTED,
                payload.eventId(),
                payload.paymentId(),
                "c-it-" + payload.eventId(),
                payload.occurredAt(),
                1,
                1,
                payload.details()));

    kafka.send(EventTopics.PAYOUT_SUBMITTED, paymentId, json).join();

    PaymentEvent stored = waitForRow(payload.eventId());
    assertThat(stored.eventId().toString()).isEqualTo(payload.eventId());
    assertThat(stored.correlationId()).isNotBlank();
    assertThat(stored.kafkaTopic()).isEqualTo("payout.submitted");
    assertThat(new ObjectMapper().findAndRegisterModules().readTree(stored.payload()))
        .isNotNull(); // stored CLOB is valid JSON

    kafka.send(EventTopics.PAYOUT_SUBMITTED, paymentId, json).join();
    // A later record with the same key proves the consumer advanced past the duplicate.
    var marker = PaymentEventPayload.random(paymentId, Instant.now(), Map.of("marker", true));
    kafka
        .send(
            EventTopics.PAYOUT_SUBMITTED,
            paymentId,
            codec.write(
                PaymentEventEnvelope.create(
                    EventTopics.PAYOUT_SUBMITTED,
                    marker.eventId(),
                    marker.paymentId(),
                    "c-it-marker",
                    marker.occurredAt(),
                    1,
                    2,
                    marker.details())))
        .join();
    waitForRow(marker.eventId());
    assertThat(repository.countByPaymentId(paymentId)).isEqualTo(2);
  }

  @Test
  void confirmationTravelsFromApplicationServiceThroughOutboxAndKafkaToTimeline() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID routeId = UUID.randomUUID();
    UUID providerId = UUID.randomUUID();
    String routeCode =
        ("IT_" + UUID.randomUUID().toString().substring(0, 12)).toUpperCase(Locale.ROOT);
    Instant now = Instant.now();

    try {
      users.saveAndFlush(
          new User(
              userId,
              "outbox-" + userId + "@fluxpay.invalid",
              "!ORACLE_TEST_NO_LOGIN!",
              "USER",
              "Outbox fixture",
              now,
              now));
      Wallet wallet = wallets.saveAndFlush(new Wallet(userId, "USD", WalletAccountRole.CUSTOMER));
      Recipient recipient =
          recipients.saveAndFlush(
              new Recipient(
                  recipientId,
                  userId,
                  "Timeline recipient",
                  "acct-" + recipientId,
                  "Fixture bank",
                  "IN",
                  "INR",
                  RecipientStatus.ACTIVE,
                  now));
      TransferProvider provider =
          providers.saveAndFlush(
              TransferProvider.create(
                  providerId,
                  "PROV_" + routeCode,
                  "Timeline provider",
                  RailType.BANK_NETWORK,
                  true,
                  false,
                  now));
      routes.saveAndFlush(
          TransferRoute.create(
              routeId,
              provider,
              routeCode,
              "Timeline route",
              DestinationType.EXTERNAL_ACCOUNT,
              "IN",
              "INR",
              new BigDecimal("5.0000"),
              new BigDecimal("0.000000"),
              60,
              new BigDecimal("99.00"),
              null,
              null,
              true,
              false,
              now));

      Payment payment =
          new Payment(
              paymentId,
              userId,
              wallet.getId(),
              recipient,
              new BigDecimal("100.0000"),
              "USD",
              "INR",
              PaymentPurpose.FAMILY_SUPPORT,
              RoutePreference.BALANCED,
              "{}",
              now);
      payment.quoted(1, now);
      payments.saveAndFlush(payment);
      quotes.saveAndFlush(
          new PaymentQuote(
              quoteId,
              paymentId,
              1,
              routeCode,
              new BigDecimal("80.000000"),
              BigDecimal.ZERO.setScale(6),
              new BigDecimal("80.000000"),
              new BigDecimal("5.0000"),
              new BigDecimal("7600.0000"),
              60,
              true,
              now,
              now.plusSeconds(900)));

      when(kyc.isVerified(userId)).thenReturn(true);
      when(compliance.assess(any(), any(), any())).thenReturn(ScreeningVerdict.APPROVE);
      when(posting.postApprovedPayment(any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(new PostingAccounts(wallet.getId(), UUID.randomUUID(), UUID.randomUUID()));

      org.slf4j.MDC.put("correlationId", "c-it-confirmation-" + paymentId);
      try {
        var response =
            confirmation.confirm(
                userId, paymentId, new ConfirmPaymentRequest(quoteId), "confirm-it-" + paymentId);
        assertThat(response.status()).isEqualTo(PaymentStatus.PROCESSING);
      } finally {
        org.slf4j.MDC.remove("correlationId");
      }

      var pending = deliveries.findByPaymentIdOrderByAggregateSequenceAsc(paymentId);
      assertThat(pending)
          .singleElement()
          .satisfies(d -> assertThat(d.state()).isEqualTo("PENDING"));
      var delivery = pending.get(0);
      var durableEvent = outboxEvents.findById(delivery.eventId()).orElseThrow();
      var envelope = codec.read(durableEvent.payload());
      assertThat(durableEvent.topic()).isEqualTo(EventTopics.PAYMENT_INITIATED);
      assertThat(envelope.eventId()).isEqualTo(delivery.eventId().toString());
      assertThat(envelope.aggregateSequence()).isEqualTo(delivery.aggregateSequence());
      assertThat(repository.findById(delivery.eventId())).isEmpty();

      relayUntilSent(delivery.eventId());

      PaymentEvent stored = waitForRow(delivery.eventId().toString());
      assertThat(stored.paymentId()).isEqualTo(paymentId.toString());
      assertThat(stored.eventType()).isEqualTo(EventTopics.PAYMENT_INITIATED);
      assertThat(stored.kafkaTopic()).isEqualTo(EventTopics.PAYMENT_INITIATED);
      assertThat(stored.correlationId()).isEqualTo("c-it-confirmation-" + paymentId);
      var payload = new ObjectMapper().findAndRegisterModules().readTree(stored.payload());
      assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
      assertThat(payload.get("aggregateSequence").asInt()).isEqualTo(1);
      assertThat(payload.get("status").asText()).isEqualTo(PaymentStatus.PROCESSING.name());
    } finally {
      cleanupConfirmationFixture(userId, paymentId, recipientId, routeId, providerId);
    }
  }

  @Test
  void reviewRequestTopicIsConsumedToTimeline() throws Exception {
    String paymentId = "IT-" + java.util.UUID.randomUUID();
    String eventId = java.util.UUID.randomUUID().toString();
    var envelope =
        PaymentEventEnvelope.create(
            EventTopics.PAYMENT_REVIEW_REQUESTED,
            eventId,
            paymentId,
            "c-it-review-" + eventId,
            Instant.now(),
            1,
            1,
            Map.of("status", "UNDER_REVIEW", "reviewReference", "R-1"));
    kafka.send(EventTopics.PAYMENT_REVIEW_REQUESTED, paymentId, codec.write(envelope)).join();

    PaymentEvent stored = waitForRow(eventId);
    assertThat(stored.eventType()).isEqualTo("payment.review.requested");
    assertThat(stored.kafkaTopic()).isEqualTo("payment.review.requested");
  }

  private PaymentEvent waitForRow(String eventId) throws InterruptedException {
    for (int i = 0; i < 60; i++) {
      var row = repository.findById(java.util.UUID.fromString(eventId));
      if (row.isPresent()) return row.get();
      Thread.sleep(500);
    }
    throw new AssertionError("payment_events row not persisted within 30s");
  }

  private void relayUntilSent(UUID eventId) throws InterruptedException {
    for (int i = 0; i < 10; i++) {
      relay.relayOnce(50);
      if (deliveries.findById(eventId).map(d -> "SENT".equals(d.state())).orElse(false)) {
        return;
      }
      Thread.sleep(250);
    }
    throw new AssertionError("outbox delivery was not marked SENT");
  }

  private void cleanupConfirmationFixture(
      UUID userId, UUID paymentId, UUID recipientId, UUID routeId, UUID providerId) {
    String paymentHex = hex(paymentId);
    List<String> eventIds =
        jdbc.queryForList(
            "SELECT RAWTOHEX(event_id) FROM outbox_delivery WHERE payment_id=HEXTORAW(?)",
            String.class,
            paymentHex);
    jdbc.update("DELETE FROM payment_events WHERE payment_id=?", paymentId.toString());
    jdbc.update("DELETE FROM outbox_delivery WHERE payment_id=HEXTORAW(?)", paymentHex);
    for (String eventId : eventIds) {
      jdbc.update("DELETE FROM outbox_events WHERE id=HEXTORAW(?)", eventId);
    }
    jdbc.update("DELETE FROM payment_operations WHERE payment_id=HEXTORAW(?)", paymentHex);
    jdbc.update("UPDATE payments SET selected_quote_id=NULL WHERE id=HEXTORAW(?)", paymentHex);
    jdbc.update("DELETE FROM payment_quotes WHERE payment_id=HEXTORAW(?)", paymentHex);
    jdbc.update("DELETE FROM payments WHERE id=HEXTORAW(?)", paymentHex);
    jdbc.update("DELETE FROM recipients WHERE id=HEXTORAW(?)", hex(recipientId));
    jdbc.update("DELETE FROM wallets WHERE user_id=HEXTORAW(?)", hex(userId));
    jdbc.update("DELETE FROM transfer_routes WHERE id=HEXTORAW(?)", hex(routeId));
    jdbc.update("DELETE FROM transfer_providers WHERE id=HEXTORAW(?)", hex(providerId));
    jdbc.update("DELETE FROM users WHERE id=HEXTORAW(?)", hex(userId));
  }

  private static String hex(UUID id) {
    return id.toString().replace("-", "");
  }
}
