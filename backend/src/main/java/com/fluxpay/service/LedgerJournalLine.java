package com.fluxpay.service;

import java.math.BigDecimal;
import java.util.UUID;

/** One immutable debit or credit instruction in a complete ledger journal. */
public record LedgerJournalLine(
    UUID walletId,
    String entryType,
    BigDecimal amount,
    String currency,
    String idempotencyKey,
    String narration,
    BigDecimal rate,
    String quoteId) {
  public LedgerJournalLine(
      UUID walletId,
      String entryType,
      BigDecimal amount,
      String currency,
      String idempotencyKey,
      String narration) {
    this(walletId, entryType, amount, currency, idempotencyKey, narration, null, null);
  }
}
