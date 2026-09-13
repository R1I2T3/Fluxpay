package com.fluxpay.service;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.common.contracts.PaymentEligibilityGate;
import com.fluxpay.repository.PaymentOperationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbPaymentEligibilityGate implements PaymentEligibilityGate {
  private final PaymentOperationRepository operations;
  private final Clock clock;
  private final SelectedQuoteService selectedQuotes;

  public DbPaymentEligibilityGate(
      PaymentOperationRepository operations, Clock clock, SelectedQuoteService selectedQuotes) {
    this.operations = Objects.requireNonNull(operations, "operations must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.selectedQuotes = Objects.requireNonNull(selectedQuotes, "selectedQuotes must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode) {
    selectedQuotes.require(payment, requestedRouteCode);
  }

  @Override
  @Transactional
  public ConfirmOutcome confirmIdempotent(PaymentSnapshot payment, String idempotencyKey) {
    var existing =
        operations.findByUserIdAndOperationTypeAndClientKey(
            payment.senderUserId(), "PAYOUT_CONFIRM", idempotencyKey);
    if (existing.isPresent()) {
      String stored = existing.get().responseData();
      if (stored == null || stored.isBlank()) {
        throw new IllegalStateException("payment request is still in progress");
      }
      return new ConfirmOutcome(true, stored);
    }
    PaymentOperation pending =
        new PaymentOperation(
            UUID.randomUUID(),
            payment.senderUserId(),
            "PAYOUT_CONFIRM",
            idempotencyKey,
            payment.paymentId(),
            202,
            "",
            UUID.fromString(payment.paymentId()),
            Instant.now(clock));
    operations.saveAndFlush(pending);
    return new ConfirmOutcome(false, null);
  }

  @Override
  @Transactional
  public void complete(PaymentSnapshot payment, String idempotencyKey, String eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    var existing =
        operations
            .findByUserIdAndOperationTypeAndClientKey(
                payment.senderUserId(), "PAYOUT_CONFIRM", idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("no pending payment reservation"));
    if (existing.responseData() != null && !existing.responseData().isBlank()) {
      throw new IllegalStateException("no pending payment reservation");
    }
    PaymentOperation done =
        new PaymentOperation(
            existing.id(),
            existing.userId(),
            existing.operationType(),
            existing.clientKey(),
            existing.normalizedRequest(),
            200,
            eventId,
            existing.paymentId(),
            existing.createdAt());
    operations.saveAndFlush(done);
  }

  @Override
  @Transactional
  public void release(PaymentSnapshot payment, String idempotencyKey) {
    operations
        .findByUserIdAndOperationTypeAndClientKey(
            payment.senderUserId(), "PAYOUT_CONFIRM", idempotencyKey)
        .filter(op -> op.responseData() == null || op.responseData().isBlank())
        .ifPresent(op -> operations.delete(op));
  }
}
