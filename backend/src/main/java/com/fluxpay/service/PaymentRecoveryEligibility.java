package com.fluxpay.service;

import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PayoutAttemptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Recovery pricing is permitted only for definitively failed, unreversed original funding. */
@Service
public class PaymentRecoveryEligibility {
  private final PaymentReader reader;
  private final PayoutAttemptRepository attempts;
  private final LedgerWriter ledger;

  public PaymentRecoveryEligibility(
      PaymentReader reader, PayoutAttemptRepository attempts, LedgerWriter ledger) {
    this.reader = reader;
    this.attempts = attempts;
    this.ledger = ledger;
  }

  public PaymentSnapshot require(Payment payment) {
    var latest = attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(payment.id().toString());
    if (payment.status() != PaymentStatus.FAILED
        || payment.postedAt() == null
        || latest.isEmpty()
        || latest.get().status() != PayoutAttemptStatus.FAILED) throw unavailable();
    var snapshot = reader.get(payment.id().toString());
    String reference = "refund:" + payment.id();
    if (snapshot.posting() == null
        || ledger.contains(reference + ":sender:credit")
        || ledger.contains(reference + ":clearing:debit")
        || ledger.contains(reference + ":fee:debit")) throw unavailable();
    return snapshot;
  }

  private static BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        "INVALID_PAYMENT_STATE",
        "Recovery quotes require a final failed payout with unreversed original funding.");
  }
}
