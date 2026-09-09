package com.fluxpay.service;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retries failed payouts on the same route or switches them to a different active route.
 *
 * <p>Retry and switch never debit: this class has no {@code LedgerWriter} dependency. Both paths
 * delegate to {@link PayoutExecutionService#executeNewAttempt} which records the next attempt
 * without touching ledgers.
 *
 * <p>Forward-compat (Task 9): refund support plus a {@code RecoveryResult} wrapper will be added
 * via an overloaded constructor carrying the refund journal, event store and publisher. The
 * 4-argument constructor below stays as the stable retry/switch path so Task 8 tests keep
 * compiling.
 */
@Service
@Transactional
public class RecoveryService {

  private final PaymentReader paymentReader;
  private final PayoutRouteRepository routes;
  private final PayoutAttemptRepository attempts;
  private final PayoutExecutionService execution;

  public RecoveryService(
      PaymentReader paymentReader,
      PayoutRouteRepository routes,
      PayoutAttemptRepository attempts,
      PayoutExecutionService execution) {
    this.paymentReader = Objects.requireNonNull(paymentReader, "paymentReader must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
    this.execution = Objects.requireNonNull(execution, "execution must not be null");
  }

  public PayoutOutcome retry(String paymentId, String correlationId) {
    PayoutAttempt latest = latestFailed(paymentId);
    PaymentSnapshot payment = paymentReader.get(paymentId);
    PayoutRoute route =
        routes
            .findById(latest.routeId())
            .orElseThrow(
                () -> new NoSuchElementException("route " + latest.routeId() + " not found"));
    return execution.executeNewAttempt(
        payment, route, latest.attemptNumber() + 1, "RETRY", correlationId);
  }

  public PayoutOutcome switchRoute(String paymentId, String newRouteCode, String correlationId) {
    PayoutAttempt latest = latestFailed(paymentId);
    PayoutRoute route =
        routes
            .findByCode(newRouteCode)
            .orElseThrow(() -> new NoSuchElementException("route " + newRouteCode + " not found"));
    if (!route.isActive()) {
      throw new IllegalStateException("route " + newRouteCode + " is inactive");
    }
    if (route.getId().equals(latest.routeId())) {
      throw new IllegalArgumentException("switch route must differ from failed route");
    }
    PaymentSnapshot payment = paymentReader.get(paymentId);
    return execution.executeNewAttempt(
        payment, route, latest.attemptNumber() + 1, "SWITCH", correlationId);
  }

  private PayoutAttempt latestFailed(String paymentId) {
    PayoutAttempt latest =
        attempts
            .findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId)
            .orElseThrow(
                () -> new NoSuchElementException("payout attempt for " + paymentId + " not found"));
    if (latest.status() != PayoutAttemptStatus.FAILED) {
      throw new IllegalStateException("latest payout attempt is not failed");
    }
    return latest;
  }
}
