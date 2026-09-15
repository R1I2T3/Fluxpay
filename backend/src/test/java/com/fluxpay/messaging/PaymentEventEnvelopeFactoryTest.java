package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentEventEnvelopeFactoryTest {
  private final EventEnvelopeCodec codec =
      new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());

  @Test
  void reviewRequestTopicIsRegistered() {
    assertThat(EventTopics.PAYMENT_REVIEW_REQUESTED).isEqualTo("payment.review.requested");
    assertThat(EventTopics.ALL).contains(EventTopics.PAYMENT_REVIEW_REQUESTED);
  }

  @Test
  void factoryBuildsCanonicalEnvelopeWithSingleEventId() {
    String eventId = UUID.randomUUID().toString();
    Instant now = Instant.parse("2026-09-13T10:00:00Z");
    var envelope =
        PaymentEventEnvelope.create(
            EventTopics.PAYMENT_INITIATED,
            eventId,
            "P-001",
            "corr-1",
            now,
            1,
            7,
            Map.of("summary", "hello"));

    assertThat(envelope.eventType()).isEqualTo("payment.initiated");
    assertThat(envelope.eventId()).isEqualTo(eventId);
    assertThat(envelope.correlationId()).isEqualTo("corr-1");
    assertThat(envelope.payload()).containsEntry("schemaVersion", 1);
    assertThat(envelope.payload()).containsEntry("aggregateSequence", 7);

    String json = codec.write(envelope);
    var decoded = codec.read(json);
    assertThat(decoded.eventId()).isEqualTo(eventId);
    assertThat(decoded.payload()).containsEntry("schemaVersion", 1);
    assertThat(decoded.payload()).containsEntry("aggregateSequence", 7);
  }

  @Test
  void rejectsEnvelopeWithoutSchemaVersion() {
    var envelope =
        new PaymentEventEnvelope(
            EventTopics.PAYMENT_INITIATED,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "corr-missing-schema",
            Instant.parse("2026-09-13T10:00:00Z"),
            Map.of("aggregateSequence", 1));

    assertThatThrownBy(() -> PaymentEventEnvelope.validate(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload.schemaVersion must equal 1");
  }

  @Test
  void rejectsEnvelopeWithUnsupportedSchemaVersion() {
    var envelope =
        new PaymentEventEnvelope(
            EventTopics.PAYMENT_INITIATED,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "corr-wrong-schema",
            Instant.parse("2026-09-13T10:00:00Z"),
            Map.of("schemaVersion", 2, "aggregateSequence", 1));

    assertThatThrownBy(() -> PaymentEventEnvelope.validate(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload.schemaVersion must equal 1");
  }

  @Test
  void rejectsEnvelopeWithNonintegralAggregateSequence() {
    var envelope =
        new PaymentEventEnvelope(
            EventTopics.PAYMENT_INITIATED,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "corr-fractional-sequence",
            Instant.parse("2026-09-13T10:00:00Z"),
            Map.of("schemaVersion", 1, "aggregateSequence", 1.5));

    assertThatThrownBy(() -> PaymentEventEnvelope.validate(envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload.aggregateSequence must be a positive integer");
  }

  @Test
  void rejectsOtherNoncanonicalAggregateSequences() {
    for (Object sequence : new Object[] {0, -1, Long.MAX_VALUE, "1"}) {
      var envelope =
          new PaymentEventEnvelope(
              EventTopics.PAYMENT_INITIATED,
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString(),
              "corr-invalid-sequence",
              Instant.parse("2026-09-13T10:00:00Z"),
              Map.of("schemaVersion", 1, "aggregateSequence", sequence));

      assertThatThrownBy(() -> PaymentEventEnvelope.validate(envelope))
          .as("aggregateSequence=%s", sequence)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("payload.aggregateSequence must be a positive integer");
    }
  }
}
