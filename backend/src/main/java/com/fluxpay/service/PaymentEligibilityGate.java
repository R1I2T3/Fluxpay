package com.fluxpay.service;

public interface PaymentEligibilityGate {
  void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode);

  ConfirmOutcome confirmIdempotent(PaymentSnapshot payment, String idempotencyKey);

  /** Completes a reservation only after execution publishes its terminal event. */
  void complete(PaymentSnapshot payment, String idempotencyKey, String eventId);

  /**
   * Releases a reservation made by {@link #confirmIdempotent} when downstream execution fails.
   * Failed validation must never be stored as a completed confirmation. Default no-op for
   * production gates that only store on success.
   */
  default void release(PaymentSnapshot payment, String idempotencyKey) {}

  record ConfirmOutcome(boolean alreadyConfirmed, String originalEventId) {
    public ConfirmOutcome {
      if (alreadyConfirmed && (originalEventId == null || originalEventId.isBlank())) {
        throw new IllegalArgumentException("originalEventId must not be blank");
      }
    }
  }
}
