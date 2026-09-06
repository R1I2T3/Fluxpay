package com.fluxpay.common.contracts;
import java.math.BigDecimal;
import java.util.UUID;
public interface LedgerWriter { void append(UUID walletId, String entryType, BigDecimal amount, String currency, String idempotencyKey); }
