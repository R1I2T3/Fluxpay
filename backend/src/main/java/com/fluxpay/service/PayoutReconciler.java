package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Replays a reserved payout after an uncertain rail response. */
@Service
public class PayoutReconciler {
  private final PaymentOperationRepository operations;
  private final PayoutAttemptRepository attempts;
  private final PaymentRepository payments;
  private final ObjectMapper mapper;
  private final RailRegistry rails;
  private final PayoutFinalizationService finalization;

  public PayoutReconciler(
      PaymentOperationRepository operations,
      PayoutAttemptRepository attempts,
      PaymentRepository payments,
      ObjectMapper mapper,
      RailRegistry rails,
      PayoutFinalizationService finalization) {
    this.operations = operations;
    this.attempts = attempts;
    this.payments = payments;
    this.mapper = mapper;
    this.rails = Objects.requireNonNull(rails, "rails must not be null");
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
    // Reconciliation replays the serialized snapshot verbatim. Catalogue active flags are never
    // reapplied: an archived route or provider still reconciles the reserved command exactly once.
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
          || !latest.routeId().equals(reserved.route().id())
          || !latest.id().equals(command.attemptId())
          || !paymentId.equals(command.transferId())
          || command.attemptNumber() != latest.attemptNumber()
          || !latest.routeId().equals(command.route().id())
          || !reserved.route().id().equals(command.route().id())
          || !reserved.provider().id().equals(command.provider().id())
          || !reserved.provider().id().equals(reserved.route().providerId())
          || !("payout:" + reserved.attemptId()).equals(command.idempotencyKey())
          || reserved.quote() == null) throw invalidReservation();
    } catch (Exception invalid) {
      throw invalidReservation();
    }
    TransferRail rail =
        rails.requireCompatible(reserved.provider().railType(), reserved.route().destinationType());
    TransferRailResult result;
    try {
      result = rail.execute(reserved.command());
    } catch (RuntimeException uncertain) {
      throw PayoutExecutionService.pendingReconciliation();
    }
    if (result == null || result.outcome() == TransferRailResult.Outcome.UNCERTAIN)
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
