package com.fluxpay.service;

import com.fluxpay.beans.PaymentOperation.Namespace;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.exception.BusinessException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reserve and commit, deliver without a transaction, then finalize in a separate transaction. */
@Service
public class PayoutExecutionService {
  private final PaymentOperationService operations;
  private final PayoutReservationService reservations;
  private final PayoutFinalizationService finalization;
  private final RailRegistry rails;

  public PayoutExecutionService(
      PaymentOperationService operations,
      PayoutReservationService reservations,
      PayoutFinalizationService finalization,
      RailRegistry rails) {
    this.operations = operations;
    this.reservations = reservations;
    this.finalization = finalization;
    this.rails = Objects.requireNonNull(rails, "rails must not be null");
  }

  @Transactional(propagation = Propagation.NEVER)
  public PayoutApi.OutcomeResponse perform(
      UUID user,
      String key,
      String action,
      UUID payment,
      String route,
      UUID replacementQuote,
      String correlationId) {
    return performReserved(
        user, key, action, payment, route, replacementQuote, correlationId, null);
  }

  @Transactional(propagation = Propagation.NEVER)
  public PayoutApi.OutcomeResponse performAutomatic(
      UUID user, UUID payment, int failedAttempt, int ordinal, String correlationId) {
    if (ordinal < 1 || ordinal > 5)
      throw new IllegalArgumentException("Automatic retry ordinal must be 1 to 5");
    return performReserved(
        user,
        "auto:retry:" + payment + ":" + ordinal,
        "AUTO_RETRY",
        payment,
        null,
        null,
        correlationId,
        failedAttempt);
  }

  private PayoutApi.OutcomeResponse performReserved(
      UUID user,
      String key,
      String action,
      UUID payment,
      String route,
      UUID replacementQuote,
      String correlationId,
      Integer failedAttempt) {
    if (correlationId == null || correlationId.isBlank())
      throw new IllegalArgumentException("correlationId must not be blank");
    var request = new LinkedHashMap<String, Object>();
    request.put("routeCode", route);
    request.put("quoteId", replacementQuote);
    // The automatic ordinal identifies the operation. The expected failed attempt is only
    // a lock-checked precondition for a new reservation; old messages replay a spent ordinal.
    var held = new AtomicReference<PayoutReservationService.Reserved>();
    Runnable validate =
        () -> {
          held.set(
              failedAttempt == null
                  ? reservations.reserve(
                      user, payment, action, route, replacementQuote, correlationId)
                  : reservations.reserveAutomatic(user, payment, failedAttempt, correlationId));
          operations.capturePayoutReservation(
              user, failedAttempt == null ? Namespace.PUBLIC : Namespace.INTERNAL, key, held.get());
        };
    var operation =
        failedAttempt == null
            ? operations.reserve(
                user, key, action, payment, request, PayoutApi.OutcomeResponse.class, validate)
            : operations.reserveInternal(
                user, key, action, payment, request, PayoutApi.OutcomeResponse.class, validate);
    if (operation.replayed()) return operation.response();
    var reserved = held.get();
    // Resolve the rail from the frozen reservation snapshot: one code-shipped rail serves every
    // catalogue provider bound to its rail type, and the reservation already rejected missing
    // rails (503) and incompatible destinations (400) before persisting the attempt. A rail that
    // disappeared after reservation still fails honestly instead of throwing
    // NullPointerException.
    // Expected state on this path: the operation row stays pending and the reserved attempt stays
    // PROCESSING because finalization never ran — no money moved and no terminal outbox event was
    // enqueued. A same-key retry therefore surfaces OPERATION_IN_PROGRESS (retryable, never a
    // second attempt), and reconciliation replays its stored command once a rail is installed.
    // This is
    // deliberately not pendingReconciliation (409): no rail was contacted, so delivery is not
    // uncertain — it never started.
    TransferRail rail =
        rails.requireCompatible(reserved.provider().railType(), reserved.route().destinationType());
    TransferRailResult result;
    try {
      result = rail.execute(reserved.command());
    } catch (RuntimeException uncertain) {
      throw pendingReconciliation();
    }
    if (result == null || result.outcome() == TransferRailResult.Outcome.UNCERTAIN)
      throw pendingReconciliation();
    return finalization.finish(reserved, operation.id(), result, correlationId);
  }

  static BusinessException pendingReconciliation() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        "PAYOUT_PENDING_RECONCILIATION",
        "Provider delivery is uncertain; reconciliation is required before another payout or refund.");
  }
}
