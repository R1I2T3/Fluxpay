package com.fluxpay.dto;

import java.util.Objects;

public record RecoveryResult(String paymentId, String eventId, boolean idempotentReplay) {
  public RecoveryResult {
    if (paymentId == null || paymentId.isBlank()) {
      throw new IllegalArgumentException("paymentId must not be blank");
    }
    Objects.requireNonNull(eventId, "eventId must not be null");
  }
}
