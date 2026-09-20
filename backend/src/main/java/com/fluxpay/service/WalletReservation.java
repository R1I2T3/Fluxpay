package com.fluxpay.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Transaction-local source-funds reservation consumed by one matching ledger debit. */
public record WalletReservation(
    UUID walletId, String idempotencyKey, String currency, BigDecimal amount) {}
