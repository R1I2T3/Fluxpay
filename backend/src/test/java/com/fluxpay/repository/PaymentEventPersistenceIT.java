package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.dto.EventEnvelopeCodec;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventEnvelope;
import com.fluxpay.dto.PaymentEventPayload;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ORACLE_JDBC_URL", matches = ".+")
class PaymentEventPersistenceIT {

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired PaymentEventRepository repository;
  @Autowired PaymentEventStore store;
  private final EventEnvelopeCodec codec =
      new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());

  @Test
  void consumerPersistsAProducedEventRowAndAcksDuplicates() throws Exception {
    String paymentId = "IT-" + java.util.UUID.randomUUID();
    PaymentEventPayload payload =
        PaymentEventPayload.random(
            paymentId, Instant.parse("2026-09-04T10:00:00Z"), Map.of("attempt", 1));
    String json =
        codec.write(
            PaymentEventEnvelope.from(
                EventTopics.PAYOUT_SUBMITTED, "c-it-" + payload.eventId(), payload));

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
                PaymentEventEnvelope.from(EventTopics.PAYOUT_SUBMITTED, "c-it-marker", marker)))
        .join();
    waitForRow(marker.eventId());
    assertThat(repository.countByPaymentId(paymentId)).isEqualTo(2);
  }

  private PaymentEvent waitForRow(String eventId) throws InterruptedException {
    for (int i = 0; i < 60; i++) {
      var row = repository.findById(java.util.UUID.fromString(eventId));
      if (row.isPresent()) return row.get();
      Thread.sleep(500);
    }
    throw new AssertionError("payment_events row not persisted within 30s");
  }
}
