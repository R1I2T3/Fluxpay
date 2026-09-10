package com.fluxpay.service;

public interface PaymentEligibilityGate {
  void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode);

  ConfirmOutcome confirmIdempotent(PaymentSnapshot payment, String idempotencyKey);

  /**
   * Releases a reservation made by {@link #confirmIdempotent} when downstream execution fails.
   * Failed validation must never be stored as a completed confirmation. Default no-op for
   * production gates that only store on success.
   */
  default void release(PaymentSnapshot payment, String idempotencyKey) {}

  record ConfirmOutcome(boolean alreadyConfirmed, String originalEventId) {
    public ConfirmOutcome {
      if (originalEventId == null || originalEventId.isBlank()) {
        throw new IllegalArgumentException("originalEventId must not be blank");
      }
    }
  }
}
