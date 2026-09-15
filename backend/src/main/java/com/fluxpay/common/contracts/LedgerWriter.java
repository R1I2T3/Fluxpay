package com.fluxpay.common.contracts;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerWriter {
  void append(
      UUID walletId, String entryType, BigDecimal amount, String currency, String idempotencyKey);

  /**
   * Durable idempotency probe for refund guards. Production ledgers should query by idempotency
   * key; default returns false to preserve existing implementations.
   */
  default boolean contains(String idempotencyKey) {
    return false;
  }
}
