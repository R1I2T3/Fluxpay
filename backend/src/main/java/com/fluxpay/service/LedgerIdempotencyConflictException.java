package com.fluxpay.service;

public class LedgerIdempotencyConflictException extends RuntimeException {
  public LedgerIdempotencyConflictException(String idempotencyKey) {
    super("Idempotency key was already used for a different ledger entry: " + idempotencyKey);
  }
}
