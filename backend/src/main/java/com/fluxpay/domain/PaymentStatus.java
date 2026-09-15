package com.fluxpay.domain;

/** Authoritative payment lifecycle; provider attempts have their own execution status. */
public enum PaymentStatus {
  DRAFT,
  QUOTED,
  UNDER_REVIEW,
  PROCESSING,
  COMPLETED,
  FAILED,
  REFUNDED,
  REJECTED,
  CANCELLED
}
