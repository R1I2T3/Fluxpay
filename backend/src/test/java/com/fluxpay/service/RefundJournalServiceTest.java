package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.common.enums.PaymentStatus;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RefundJournalServiceTest {
  @Test
  void postsNewBalancedLinesWithoutTouchingTheOriginalDebitKey() {
    LedgerWriter ledger = mock(LedgerWriter.class);
    PaymentSnapshot payment = payment();
    new RefundJournalService(ledger).refund(payment);

    InOrder order = inOrder(ledger);
    order
        .verify(ledger)
        .append(
            payment.payoutClearingWalletId(),
            "DEBIT",
            new BigDecimal("1000.00"),
            "USD",
            "refund:P-001:clearing:debit");
    order
        .verify(ledger)
        .append(
            payment.senderWalletId(),
            "CREDIT",
            new BigDecimal("1000.00"),
            "USD",
            "refund:P-001:sender:credit");
    verifyNoMoreInteractions(ledger);
  }

  @Test
  void doubleRefundRestoresBalancesOnceAndPreservesOriginalEntry() {
    SeededLedger ledger = new SeededLedger();
    PaymentSnapshot payment = payment();
    SeededLedger.LedgerEntry originalBefore = ledger.entriesSnapshot().get("payment:P-001:debit");

    RefundJournalService journal = new RefundJournalService(ledger);
    journal.refund(payment);
    journal.refund(payment);

    assertThat(ledger.balancesSnapshot().get(payment.senderWalletId()))
        .isEqualByComparingTo(new BigDecimal("10000.00"));
    assertThat(ledger.balancesSnapshot().get(payment.payoutClearingWalletId()))
        .isEqualByComparingTo(new BigDecimal("0.00"));
    // 2 seeded originals (P-001, P-002) + 2 refund lines for P-001
    assertThat(ledger.entriesSnapshot()).hasSize(4);
    assertThat(ledger.entriesSnapshot().get("payment:P-001:debit")).isEqualTo(originalBefore);
    assertThat(ledger.entriesSnapshot())
        .containsKeys("refund:P-001:clearing:debit", "refund:P-001:sender:credit");
  }

  private static PaymentSnapshot payment() {
    return new PaymentSnapshot(
        "P-001",
        UUID.nameUUIDFromBytes("fluxpay:P-001:user".getBytes()),
        UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes()),
        UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes()),
        new BigDecimal("1000.00"),
        "USD",
        "KES",
        PaymentStatus.ROUTED);
  }

  /**
   * In-memory {@link LedgerWriter} seeded to the post-payment state for {@code P-001} (sender
   * {@code 9000.00 USD}, clearing {@code 1000.00 USD}, plus the immutable original entries).
   * Replaces the deleted {@code MockLedgerWriter}; each idempotency key applies once.
   */
  private static final class SeededLedger implements LedgerWriter {
    record LedgerEntry(
        UUID walletId,
        String entryType,
        BigDecimal amount,
        String currency,
        String idempotencyKey) {}

    private final Map<UUID, BigDecimal> balances = new HashMap<>();
    private final Map<String, LedgerEntry> entries = new LinkedHashMap<>();

    SeededLedger() {
      UUID sender = UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes());
      UUID clearing = UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes());
      balances.put(sender, new BigDecimal("9000.00"));
      balances.put(clearing, new BigDecimal("1000.00"));
      balances.put(
          UUID.nameUUIDFromBytes("fluxpay:P-002:sender".getBytes()), new BigDecimal("9500.00"));
      balances.put(
          UUID.nameUUIDFromBytes("fluxpay:P-002:clearing".getBytes()), new BigDecimal("500.00"));
      entries.put(
          "payment:P-001:debit",
          new LedgerEntry(
              sender, "DEBIT", new BigDecimal("1000.00"), "USD", "payment:P-001:debit"));
      entries.put(
          "payment:P-002:debit",
          new LedgerEntry(
              UUID.nameUUIDFromBytes("fluxpay:P-002:sender".getBytes()),
              "DEBIT",
              new BigDecimal("500.00"),
              "USD",
              "payment:P-002:debit"));
    }

    @Override
    public synchronized void append(
        UUID walletId,
        String entryType,
        BigDecimal amount,
        String currency,
        String idempotencyKey) {
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

    @Override
    public synchronized boolean contains(String idempotencyKey) {
      return entries.containsKey(idempotencyKey);
    }

    synchronized Map<UUID, BigDecimal> balancesSnapshot() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(balances));
    }

    synchronized Map<String, LedgerEntry> entriesSnapshot() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }
  }
}
