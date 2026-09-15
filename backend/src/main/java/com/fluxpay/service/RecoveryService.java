package com.fluxpay.service;

import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.RecoveryResult;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.messaging.PaymentEventPayload;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Refund joins the operation transaction; retry/switch share payout reservation and delivery. */
@Service
public class RecoveryService {
  private final PaymentRepository payments;
  private final PaymentReader reader;
  private final PayoutAttemptRepository attempts;
  private final RefundJournalService refunds;
  private final PayoutOutboxService outbox;
  private final Clock clock;

  public RecoveryService(
      PaymentRepository payments,
      PaymentReader reader,
      PayoutAttemptRepository attempts,
      RefundJournalService refunds,
      PayoutOutboxService outbox,
      Clock clock) {
    this.payments = payments;
    this.reader = reader;
    this.attempts = attempts;
    this.refunds = refunds;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public RecoveryResult refundFunded(UUID user, UUID paymentId, String correlationId) {
    var payment = payments.lockOwned(paymentId, user).orElseThrow();
    if (payment.status() == PaymentStatus.REFUNDED)
      return new RecoveryResult(
          paymentId.toString(),
          PaymentEventPayload.refund(paymentId.toString(), clock.instant(), Map.of()).eventId(),
          true);
    if (payment.status() != PaymentStatus.FAILED)
      throw new IllegalStateException("Only a final failed payout can be refunded");
    var latest =
        attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId.toString()).orElseThrow();
    if (latest.status() != PayoutAttemptStatus.FAILED)
      throw new IllegalStateException("Latest payout is not a final failure");
    var snapshot = reader.get(paymentId.toString());
    refunds.refund(snapshot);
    payment.refundPayout(clock.instant());
    var eventId =
        outbox.enqueue(
            payment,
            EventTopics.PAYMENT_REFUNDED,
            correlationId,
            Map.of(
                "attempt",
                latest.attemptNumber(),
                "currency",
                snapshot.sourceCurrency(),
                "amount",
                snapshot.amount(),
                "summary",
                "Original payment funding refunded"));
    return new RecoveryResult(paymentId.toString(), eventId, false);
  }
}
