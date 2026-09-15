package com.fluxpay.messaging;

import java.math.BigDecimal;
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

  /**
   * Canonical factory for durable outbox events. The caller generates {@code eventId} once and
   * reuses it for the outbox row; the payload carries {@code schemaVersion} and {@code
   * aggregateSequence} alongside business details.
   */
  public static PaymentEventEnvelope create(
      String eventType,
      String eventId,
      String paymentId,
      String correlationId,
      Instant occurredAt,
      int schemaVersion,
      int aggregateSequence,
      Map<String, ?> details) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (details != null) {
      details.forEach(payload::put);
    }
    payload.put("schemaVersion", schemaVersion);
    payload.put("aggregateSequence", aggregateSequence);
    PaymentEventEnvelope envelope =
        new PaymentEventEnvelope(eventType, eventId, paymentId, correlationId, occurredAt, payload);
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
    Integer schemaVersion = integerValue(envelope.payload().get("schemaVersion"));
    if (schemaVersion == null || schemaVersion != 1) {
      throw new IllegalArgumentException("payload.schemaVersion must equal 1");
    }
    Integer aggregateSequence = integerValue(envelope.payload().get("aggregateSequence"));
    if (aggregateSequence == null || aggregateSequence < 1) {
      throw new IllegalArgumentException("payload.aggregateSequence must be a positive integer");
    }
  }

  public int aggregateSequence() {
    Integer value = integerValue(payload.get("aggregateSequence"));
    if (value == null || value < 1) {
      throw new IllegalArgumentException("payload.aggregateSequence must be a positive integer");
    }
    return value;
  }

  private static Integer integerValue(Object value) {
    if (!(value instanceof Number number)) {
      return null;
    }
    try {
      return new BigDecimal(number.toString()).intValueExact();
    } catch (ArithmeticException | NumberFormatException ignored) {
      return null;
    }
  }
}
