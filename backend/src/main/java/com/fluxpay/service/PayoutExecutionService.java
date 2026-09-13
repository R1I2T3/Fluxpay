package com.fluxpay.service;

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
    if (correlationId == null || correlationId.isBlank())
      throw new IllegalArgumentException("correlationId must not be blank");
    var request = new LinkedHashMap<String, Object>();
    request.put("routeCode", route);
    request.put("quoteId", replacementQuote);
    var held = new AtomicReference<PayoutReservationService.Reserved>();
    var operation =
        operations.reserve(
            user,
            key,
            action,
            payment,
            request,
            PayoutApi.OutcomeResponse.class,
            () ->
                held.set(
                    reservations.reserve(
                        user,
                        payment,
                        action,
                        route,
                        replacementQuote,
                        correlationId,
                        providers::containsKey)));
    if (operation.replayed()) return operation.response();
    var reserved = held.get();
    PayoutResult result;
    try {
      result = providers.get(reserved.routeCode()).submit(reserved.command());
    } catch (RuntimeException uncertain) {
      throw pendingReconciliation();
    }
    if (result == null
        || (!result.success()
            && (result.errorCode().contains("TIMEOUT")
                || result.errorCode().contains("UNKNOWN")
                || result.errorCode().contains("AMBIGUOUS")))) throw pendingReconciliation();
    return finalization.finish(reserved, operation.id(), result, correlationId);
  }

  private static BusinessException pendingReconciliation() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        "PAYOUT_PENDING_RECONCILIATION",
        "Provider delivery is uncertain; reconciliation is required before another payout or refund.");
  }
}
