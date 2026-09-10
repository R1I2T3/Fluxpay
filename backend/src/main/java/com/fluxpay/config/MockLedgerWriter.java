package com.fluxpay.config;

import com.fluxpay.common.contracts.LedgerWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Solo {@code mock}-profile ledger used by member-4 tests and local runs.
 *
 * <p>Each idempotency key applies once. Seeded to the post-payment state for {@code P-001}: sender
 * {@code 9000.00 USD}, clearing {@code 1000.00 USD}, plus the immutable original entry {@code
 * payment:P-001:debit}. {@code DEBIT} subtracts and {@code CREDIT} adds; any other entry type or
 * currency is rejected.
 */
public class MockLedgerWriter implements LedgerWriter {

  public static final UUID P001_SENDER_WALLET =
      UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes(StandardCharsets.UTF_8));
  public static final UUID P001_CLEARING_WALLET =
      UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes(StandardCharsets.UTF_8));
  public static final String ORIGINAL_KEY = "payment:P-001:debit";

  /** Single immutable ledger line, exposed via snapshots for tests. */
  public record LedgerEntry(
      UUID walletId, String entryType, BigDecimal amount, String currency, String idempotencyKey) {}

  private final Map<UUID, BigDecimal> balances = new HashMap<>();
  private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();

  public MockLedgerWriter() {
    balances.put(P001_SENDER_WALLET, new BigDecimal("9000.00"));
    balances.put(P001_CLEARING_WALLET, new BigDecimal("1000.00"));
    entries.put(
        ORIGINAL_KEY,
        new LedgerEntry(
            P001_SENDER_WALLET, "DEBIT", new BigDecimal("1000.00"), "USD", ORIGINAL_KEY));
  }

  @Override
  public synchronized void append(
      UUID walletId, String entryType, BigDecimal amount, String currency, String idempotencyKey) {
    Objects.requireNonNull(walletId, "walletId must not be null");
    Objects.requireNonNull(entryType, "entryType must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    if (entries.containsKey(idempotencyKey)) {
      return;
    }
    if (!"DEBIT".equals(entryType) && !"CREDIT".equals(entryType)) {
      throw new IllegalArgumentException("unsupported entry type: " + entryType);
    }
    if (!"USD".equals(currency)) {
      throw new IllegalArgumentException("unsupported currency: " + currency);
    }
    BigDecimal current = balances.getOrDefault(walletId, BigDecimal.ZERO);
    BigDecimal next = "DEBIT".equals(entryType) ? current.subtract(amount) : current.add(amount);
    balances.put(walletId, next);
    entries.put(
        idempotencyKey, new LedgerEntry(walletId, entryType, amount, currency, idempotencyKey));
  }

  /**
   * Immutable balance snapshot, visible for tests.
   *
   * @return unmodifiable copy of wallet balances.
   */
  public synchronized Map<UUID, BigDecimal> balancesSnapshot() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(balances));
  }

  /**
   * Immutable entry snapshot, visible for tests.
   *
   * @return unmodifiable copy of entries keyed by idempotency key.
   */
  public synchronized Map<String, LedgerEntry> entriesSnapshot() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
  }
}
