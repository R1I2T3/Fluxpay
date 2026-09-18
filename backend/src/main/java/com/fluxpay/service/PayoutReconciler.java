package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Replays a reserved payout after an uncertain provider response. */
@Service
public class PayoutReconciler {
  private final PaymentOperationRepository operations;
  private final PayoutAttemptRepository attempts;
  private final PaymentRepository payments;
  private final ObjectMapper mapper;
  private final Map<String, PayoutProvider> providers;
  private final PayoutFinalizationService finalization;

  public PayoutReconciler(
      PaymentOperationRepository operations,
      PayoutAttemptRepository attempts,
      PaymentRepository payments,
      ObjectMapper mapper,
      List<PayoutProvider> providers,
      PayoutFinalizationService finalization) {
    this.operations = operations;
    this.attempts = attempts;
    this.payments = payments;
    this.mapper = mapper;
    this.providers =
        providers.stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    PayoutProvider::code, java.util.function.Function.identity()));
    this.finalization = finalization;
  }

  @Transactional(propagation = Propagation.NEVER)
  public PayoutApi.OutcomeResponse reconcile(UUID paymentId, String correlationId) {
    if (correlationId == null || correlationId.isBlank())
      throw new IllegalArgumentException("correlationId must not be blank");
    var payment = payments.findById(paymentId).orElseThrow();
    var latest =
        attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId.toString()).orElseThrow();
    if (payment.status() != PaymentStatus.PROCESSING
        || latest.status() != PayoutAttemptStatus.PROCESSING)
      throw new BusinessException(
          HttpStatus.CONFLICT, "NO_PENDING_PAYOUT", "There is no pending payout to reconcile.");
    var candidates =
        operations.findByPaymentIdAndStatus(paymentId, "IN_PROGRESS").stream()
            .filter(op -> op.payoutReservation() != null)
            .toList();
    if (candidates.size() != 1) throw invalidReservation();
    var operation = candidates.get(0);
    PayoutReservationService.Reserved reserved;
    try {
      reserved =
          mapper.readValue(operation.payoutReservation(), PayoutReservationService.Reserved.class);
      var command = reserved.command();
      if (!paymentId.equals(reserved.paymentId())
          || !payment.senderId().equals(reserved.userId())
          || !operation.userId().equals(reserved.userId())
          || !latest.id().equals(reserved.attemptId())
          || latest.attemptNumber() != reserved.attemptNumber()
          || !latest.id().equals(command.attemptId())
          || !paymentId.toString().equals(command.paymentId())
          || command.attemptNumber() != latest.attemptNumber()
          || !reserved.routeCode().equals(command.routeCode())
          || reserved.quote() == null) throw invalidReservation();
    } catch (Exception invalid) {
      throw invalidReservation();
    }
    var provider = providers.get(reserved.routeCode());
    if (provider == null)
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PAYOUT_PROVIDER_UNAVAILABLE",
          "The original payout provider is unavailable.");
    PayoutResult result;
    try {
      result = provider.submit(reserved.command());
    } catch (RuntimeException uncertain) {
      throw PayoutExecutionService.pendingReconciliation();
    }
    if (result == null || result.outcome() == PayoutResult.Outcome.UNCERTAIN)
      throw PayoutExecutionService.pendingReconciliation();
    return finalization.finish(reserved, operation.id(), result, correlationId);
  }

  private static BusinessException invalidReservation() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        "PAYOUT_RESERVATION_UNAVAILABLE",
        "The original durable payout command is unavailable or invalid; manual investigation is required.");
  }
}
