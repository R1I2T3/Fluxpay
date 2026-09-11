package com.fluxpay.service;

import com.fluxpay.common.contracts.LedgerWriter;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a balanced compensating refund for a routed payment.
 *
 * <p>The refund never touches the original debit key: it appends a clearing {@code DEBIT} followed
 * by a sender {@code CREDIT}, both for the source amount/currency under deterministic {@code
 * refund:<paymentId>:*} keys. No wallet repository is consulted and no existing entry is mutated.
 */
@Profile("mock")
@Service
public class RefundJournalService {

  private final LedgerWriter ledger;

  public RefundJournalService(LedgerWriter ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
  }

  @Transactional
  public void refund(PaymentSnapshot payment) {
    Objects.requireNonNull(payment, "payment must not be null");
    if (payment.senderWalletId().equals(payment.payoutClearingWalletId())) {
      throw new IllegalArgumentException("sender and clearing wallets must differ");
    }
    ledger.append(
        payment.payoutClearingWalletId(),
        "DEBIT",
        payment.amount(),
        payment.sourceCurrency(),
        "refund:" + payment.paymentId() + ":clearing:debit");
    ledger.append(
        payment.senderWalletId(),
        "CREDIT",
        payment.amount(),
        payment.sourceCurrency(),
        "refund:" + payment.paymentId() + ":sender:credit");
  }

  /**
   * Probe for completed ledger compensation. Publication is tracked independently; a replay may
   * republish the deterministic refund event until it appears in the timeline.
   */
  public boolean isAlreadyRefunded(PaymentSnapshot payment) {
    Objects.requireNonNull(payment, "payment must not be null");
    return ledger.contains("refund:" + payment.paymentId() + ":clearing:debit")
        && ledger.contains("refund:" + payment.paymentId() + ":sender:credit");
  }
}
