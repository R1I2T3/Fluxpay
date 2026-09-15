package com.fluxpay.service;

import com.fluxpay.common.contracts.LedgerWriter;
import java.util.ArrayList;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reverses the original source-side journal, including its fee, under stable reversal keys. */
@Service
public class RefundJournalService {

  private final LedgerWriter ledger;
  private final LedgerJournalService journals;

  public RefundJournalService(LedgerJournalService journals, LedgerWriter ledger) {
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.journals = Objects.requireNonNull(journals);
  }

  @Transactional
  public void refund(PaymentSnapshot payment) {
    Objects.requireNonNull(payment, "payment must not be null");
    var posting = payment.posting();
    if (posting == null)
      throw new IllegalArgumentException("Original posting snapshot is required");
    String reference = "refund:" + payment.paymentId();
    String narration = "Reversal of " + posting.originalJournalReference();
    var lines = new ArrayList<LedgerJournalLine>();
    lines.add(
        new LedgerJournalLine(
            posting.clearingWalletId(),
            "DEBIT",
            posting.net(),
            posting.currency(),
            reference + ":clearing:debit",
            narration));
    lines.add(
        new LedgerJournalLine(
            posting.customerWalletId(),
            "CREDIT",
            posting.gross(),
            posting.currency(),
            reference + ":sender:credit",
            narration));
    if (posting.fee().signum() != 0)
      lines.add(
          new LedgerJournalLine(
              posting.feeWalletId(),
              "DEBIT",
              posting.fee(),
              posting.currency(),
              reference + ":fee:debit",
              narration));
    journals.post(reference, lines);
  }

  /**
   * Probe for completed ledger compensation. Publication is tracked independently; a replay may
   * republish the deterministic refund event until it appears in the timeline.
   */
  public boolean isAlreadyRefunded(PaymentSnapshot payment) {
    Objects.requireNonNull(payment, "payment must not be null");
    return ledger.contains("refund:" + payment.paymentId() + ":clearing:debit")
        && ledger.contains("refund:" + payment.paymentId() + ":sender:credit")
        && payment.posting() != null
        && (payment.posting().fee().signum() == 0
            || ledger.contains("refund:" + payment.paymentId() + ":fee:debit"));
  }
}
