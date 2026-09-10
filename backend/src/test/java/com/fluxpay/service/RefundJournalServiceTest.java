package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.config.MockLedgerWriter;
import java.math.BigDecimal;
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
    MockLedgerWriter ledger = new MockLedgerWriter();
    PaymentSnapshot payment = payment();
    MockLedgerWriter.LedgerEntry originalBefore =
        ledger.entriesSnapshot().get("payment:P-001:debit");

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
}
