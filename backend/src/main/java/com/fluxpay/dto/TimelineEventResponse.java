package com.fluxpay.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Chronological timeline entry mapped from a stored {@code payment_events} row. */
public record TimelineEventResponse(
    String eventId,
    String paymentId,
    String eventType,
    String kafkaTopic,
    String correlationId,
    Map<String, Object> payload,
    Instant occurredAt) {

  public TimelineEventResponse {
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    payload =
        payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
  }
}
