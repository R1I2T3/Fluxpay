package com.fluxpay.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PaymentEventEnvelope(
    String eventType,
    String eventId,
    String paymentId,
    String correlationId,
    Instant occurredAt,
    Map<String, Object> payload) {

  public PaymentEventEnvelope {
    if (payload == null) {
      payload = Map.of();
    }
    payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }

  public static PaymentEventEnvelope from(
      String topic, String correlationId, PaymentEventPayload payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    PaymentEventEnvelope envelope =
        new PaymentEventEnvelope(
            topic,
            payload.eventId(),
            payload.paymentId(),
            correlationId,
            payload.occurredAt(),
            payload.details());
    validate(envelope);
    return envelope;
  }

  public static void validate(PaymentEventEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    if (envelope.eventType() == null || !EventTopics.ALL.contains(envelope.eventType())) {
      throw new IllegalArgumentException("unknown event topic: " + envelope.eventType());
    }
    if (envelope.paymentId() == null || envelope.paymentId().isBlank()) {
      throw new IllegalArgumentException("paymentId must not be blank");
    }
    if (envelope.correlationId() == null || envelope.correlationId().isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    if (envelope.eventId() == null || envelope.eventId().isBlank()) {
      throw new IllegalArgumentException("eventId must not be blank");
    }
    if (envelope.occurredAt() == null) {
      throw new IllegalArgumentException("occurredAt must not be null");
    }
    if (envelope.payload() == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
  }
}
