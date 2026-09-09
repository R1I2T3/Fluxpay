package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.dto.EventEnvelopeCodec;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventEnvelope;
import com.fluxpay.dto.PaymentEventPayload;
import java.time.Instant;
import java.util.List;
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
    PaymentEventPayload payload =
        PaymentEventPayload.random(
            "P-001", Instant.parse("2026-09-04T10:00:00Z"), Map.of("attempt", 1));
    String json =
        codec.write(
            PaymentEventEnvelope.from(
                EventTopics.PAYOUT_SUBMITTED, "c-it-" + payload.eventId(), payload));

    kafka.send(EventTopics.PAYOUT_SUBMITTED, "P-001", json).join();

    PaymentEvent stored = waitForRow("P-001");
    assertThat(stored.eventId()).isEqualTo(payload.eventId());
    assertThat(stored.correlationId()).isNotBlank();
    assertThat(stored.kafkaTopic()).isEqualTo("payout.submitted");
    assertThat(new ObjectMapper().findAndRegisterModules().readTree(stored.payload()))
        .isNotNull(); // stored CLOB is valid JSON

    kafka.send(EventTopics.PAYOUT_SUBMITTED, "P-001", json).join();
    assertThat(repository.countByPaymentId("P-001")).isEqualTo(1); // duplicate acked, stored once
  }

  private PaymentEvent waitForRow(String paymentId) throws InterruptedException {
    for (int i = 0; i < 60; i++) {
      List<PaymentEvent> rows = repository.findByPaymentIdOrderByOccurredAtAscEventIdAsc(paymentId);
      if (!rows.isEmpty()) return rows.get(0);
      Thread.sleep(500);
    }
    throw new AssertionError("payment_events row not persisted within 30s");
  }
}
