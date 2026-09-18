package com.fluxpay.service;

import com.fluxpay.dto.*;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PayoutFinalizationService {
  private final PaymentRepository payments;
  private final PayoutAttemptRepository attempts;
  private final PaymentOperationService operations;
  private final PayoutOutboxService outbox;
  private final Clock clock;

  public PayoutFinalizationService(
      PaymentRepository payments,
      PayoutAttemptRepository attempts,
      PaymentOperationService operations,
      PayoutOutboxService outbox,
      Clock clock) {
    this.payments = payments;
    this.attempts = attempts;
    this.operations = operations;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayoutApi.OutcomeResponse finish(
      PayoutReservationService.Reserved reserved,
      UUID operationId,
      PayoutResult result,
      String correlationId) {
    if (result == null || result.outcome() == PayoutResult.Outcome.UNCERTAIN)
      throw new IllegalStateException("Uncertain provider delivery requires reconciliation");
    var payment = payments.lockOwned(reserved.paymentId(), reserved.userId()).orElseThrow();
    var completed = operations.completedResponse(operationId, PayoutApi.OutcomeResponse.class);
    if (completed.isPresent()) return completed.get();
    var attempt = attempts.findById(reserved.attemptId()).orElseThrow();
    var latest =
        attempts
            .findFirstByPaymentIdOrderByAttemptNumberDesc(payment.id().toString())
            .orElseThrow();
    if (!latest.id().equals(attempt.id())
        || attempt.status() != com.fluxpay.beans.PayoutAttemptStatus.PROCESSING)
      throw new IllegalStateException("Only the current pending attempt can be finalized");
    var details = new LinkedHashMap<String, Object>();
    details.put("attempt", attempt.attemptNumber());
    details.put("routeCode", reserved.routeCode());
    details.put("providerRef", result.providerRef());
    details.put("providerFee", result.providerFee());
    details.put("error", result.errorCode());
    details.put("errorMessage", result.errorMessage());
    var completedAt = clock.instant();
    if (result.success()) {
      attempt.markCompleted(result.providerRef(), completedAt);
      payment.completePayout(completedAt);
    } else {
      attempt.markFailed(result.errorCode(), result.errorMessage(), completedAt);
      payment.failPayout(completedAt);
    }
    var eventId =
        outbox.enqueue(
            payment,
            result.success() ? EventTopics.PAYOUT_COMPLETED : EventTopics.PAYOUT_FAILED,
            correlationId,
            details);
    var response =
        new PayoutApi.OutcomeResponse(
            attempt.attemptNumber(),
            reserved.routeCode(),
            payment.status().name(),
            result.providerRef(),
            result.errorCode(),
            result.success()
                ? List.of()
                : List.of(RecoveryAction.RETRY, RecoveryAction.SWITCH, RecoveryAction.REFUND),
            false,
            eventId,
            reserved.quote());
    operations.completeInTransaction(operationId, response, 200);
    return response;
  }
}
