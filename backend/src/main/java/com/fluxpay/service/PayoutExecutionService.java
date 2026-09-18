package com.fluxpay.service;

import com.fluxpay.beans.PaymentOperation.Namespace;
import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.exception.BusinessException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reserve and commit, deliver without a transaction, then finalize in a separate transaction. */
@Service
public class PayoutExecutionService {
  private final PaymentOperationService operations;
  private final PayoutReservationService reservations;
  private final PayoutFinalizationService finalization;
  private final Map<String, PayoutProvider> providers;

  public PayoutExecutionService(
      PaymentOperationService operations,
      PayoutReservationService reservations,
      PayoutFinalizationService finalization,
      List<PayoutProvider> providers) {
    this.operations = operations;
    this.reservations = reservations;
    this.finalization = finalization;
    this.providers =
        providers.stream()
            .collect(Collectors.toUnmodifiableMap(PayoutProvider::code, Function.identity()));
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
                      user,
                      payment,
                      action,
                      route,
                      replacementQuote,
                      correlationId,
                      providers::containsKey)
                  : reservations.reserveAutomatic(
                      user, payment, failedAttempt, correlationId, providers::containsKey));
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
    // Validate capability before claiming a completed external action: the reservation already
    // rejected unknown providers with 503, but a provider that disappeared after reservation must
    // still fail honestly instead of throwing NullPointerException.
    // Expected state on this path: the operation row stays pending and the reserved attempt stays
    // PROCESSING because finalization never ran — no money moved and no terminal outbox event was
    // enqueued. A same-key retry therefore surfaces OPERATION_IN_PROGRESS (retryable, never a
    // second attempt), and reconciliation replays its stored command once a provider is configured.
    // This is
    // deliberately not pendingReconciliation (409): no provider was contacted, so delivery is not
    // uncertain — it never started.
    var provider = providers.get(reserved.routeCode());
    if (provider == null) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PAYOUT_PROVIDER_UNAVAILABLE",
          "No payout provider is configured for route " + reserved.routeCode() + ".");
    }
    PayoutResult result;
    try {
      result = provider.submit(reserved.command());
    } catch (RuntimeException uncertain) {
      throw pendingReconciliation();
    }
    if (result == null || result.outcome() == PayoutResult.Outcome.UNCERTAIN)
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
