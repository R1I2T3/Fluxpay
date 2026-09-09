package com.fluxpay.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record PaymentEventPayload(
    String paymentId, String eventId, Instant occurredAt, Map<String, Object> details) {

  public PaymentEventPayload {
    if (details == null) {
      details = Map.of();
    }
    details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  public static PaymentEventPayload random(
      String paymentId, Instant occurredAt, Map<String, ?> details) {
    return new PaymentEventPayload(
        paymentId, UUID.randomUUID().toString(), occurredAt, copyDetails(details));
  }

  public static PaymentEventPayload refund(
      String paymentId, Instant occurredAt, Map<String, ?> details) {
    String eventId =
        UUID.nameUUIDFromBytes(("refund:" + paymentId).getBytes(StandardCharsets.UTF_8)).toString();
    return new PaymentEventPayload(paymentId, eventId, occurredAt, copyDetails(details));
  }

  private static Map<String, Object> copyDetails(Map<String, ?> details) {
    if (details == null) {
      return Map.of();
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    details.forEach(copy::put);
    return Collections.unmodifiableMap(copy);
  }
}
