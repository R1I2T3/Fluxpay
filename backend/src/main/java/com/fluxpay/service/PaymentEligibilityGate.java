package com.fluxpay.service;

public interface PaymentEligibilityGate {
  void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode);

  ConfirmOutcome confirmIdempotent(PaymentSnapshot payment, String idempotencyKey);

  record ConfirmOutcome(boolean alreadyConfirmed, String originalEventId) {
    public ConfirmOutcome {
      if (originalEventId == null || originalEventId.isBlank()) {
        throw new IllegalArgumentException("originalEventId must not be blank");
      }
    }
  }
}
