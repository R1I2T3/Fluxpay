package com.fluxpay.service;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.event.EventPublisher;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventPayload;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes payouts against the route-matched provider and records every attempt.
 *
 * <p>{@link #executeNewAttempt} is package-visible so the Task 8 recovery service in this package
 * can retry or switch routes through the same INITIATED-flush-first path.
 *
 * <p><b>Known limitation (tech-debt, see kafka-payout-retries-design.md §Processing):</b> DB
 * writes, Kafka publishes (via {@code send().join()}), and blocking provider I/O currently share
 * one Spring transaction. A DB rollback after a Kafka send can leave ghost events; provider latency
 * holds a DB connection; a crash between provider success and commit can lose the result.
 * Production requires a transactional outbox + relay with provider I/O outside the DB tx and
 * idempotent {@code payout:<attemptId>} keys. M4 demo scope keeps the single-tx path but callers
 * must not assume atomicity.
 */
@Service
@Transactional
public class PayoutExecutionService {

  private final PaymentReader paymentReader;
  private final PayoutRouteRepository routes;
  private final PayoutAttemptRepository attempts;
  private final EventPublisher events;
  private final Map<String, PayoutProvider> providersByCode;
  private final Clock clock;

  public PayoutExecutionService(
      PaymentReader paymentReader,
      PayoutRouteRepository routes,
      PayoutAttemptRepository attempts,
      EventPublisher events,
      List<PayoutProvider> providers,
      Clock clock) {
    this.paymentReader = Objects.requireNonNull(paymentReader, "paymentReader must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    Objects.requireNonNull(providers, "providers must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.providersByCode =
        providers.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    PayoutProvider::code,
                    Function.identity(),
                    (first, second) -> {
                      throw new IllegalArgumentException(
                          "duplicate provider code: " + first.code());
                    }));
  }

  public PayoutOutcome submit(String paymentId, String routeCode, String correlationId) {
    requireCorrelationId(correlationId);
    PaymentSnapshot payment = paymentReader.get(paymentId);
    requirePayoutEligible(payment);
    attempts
        .findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId)
        .ifPresent(
            latest -> {
              throw new IllegalStateException(
                  "payment " + paymentId + " already has attempt " + latest.attemptNumber());
            });
    PayoutRoute route = loadActiveRoute(routeCode);
    PayoutProvider provider = loadProvider(routeCode);
    return executeNewAttempt(payment, route, provider, 1, "SUBMIT", correlationId);
  }

  PayoutOutcome executeNewAttempt(
      PaymentSnapshot payment,
      PayoutRoute route,
      PayoutProvider provider,
      int attemptNumber,
      String reason,
      String correlationId) {
    requireCorrelationId(correlationId);
    PayoutAttempt attempt =
        PayoutAttempt.initiated(
            UUID.randomUUID(), payment.paymentId(), attemptNumber, route.getId(), clock.instant());
    attempts.saveAndFlush(attempt);

    publish(
        EventTopics.PAYMENT_ROUTE_SELECTED,
        payment.paymentId(),
        routeCodeOf(route),
        attemptNumber,
        "Route " + routeCodeOf(route) + " selected (" + reason + ")",
        Map.of("reason", reason),
        correlationId);

    attempt.markProcessing();
    attempts.save(attempt);
    publish(
        EventTopics.PAYOUT_SUBMITTED,
        payment.paymentId(),
        routeCodeOf(route),
        attemptNumber,
        "Payout submitted via " + routeCodeOf(route),
        Map.of(),
        correlationId);

    PayoutResult result =
        provider.submit(
            new PayoutCmd(
                payment.paymentId(),
                payment.amount(),
                payment.sourceCurrency(),
                payment.targetCurrency(),
                routeCodeOf(route),
                route.getBaseFee(),
                attemptNumber));

    if (result.success()) {
      attempt.markCompleted(result.providerRef());
      attempts.save(attempt);
      Map<String, Object> terminal =
          Map.of("providerRef", result.providerRef(), "providerFee", result.providerFee());
      publish(
          EventTopics.PAYOUT_COMPLETED,
          payment.paymentId(),
          routeCodeOf(route),
          attemptNumber,
          "Payout completed via " + routeCodeOf(route),
          terminal,
          correlationId);
      return PayoutOutcome.completed();
    }

    attempt.markFailed(result.errorCode(), result.errorMessage());
    attempts.save(attempt);
    Map<String, Object> terminal =
        Map.of("error", result.errorCode(), "errorMessage", result.errorMessage());
    publish(
        EventTopics.PAYOUT_FAILED,
        payment.paymentId(),
        routeCodeOf(route),
        attemptNumber,
        "Payout failed via " + routeCodeOf(route) + ": " + result.errorCode(),
        terminal,
        correlationId);
    return PayoutOutcome.failed();
  }

  /**
   * Recovery path (Task 8): resolves the provider from the route code and delegates to the
   * INITIATED-flush-first path above. Package-visible so {@code RecoveryService} in this package
   * can retry or switch routes without provider plumbing.
   */
  PayoutOutcome executeNewAttempt(
      PaymentSnapshot payment,
      PayoutRoute route,
      int attemptNumber,
      String reason,
      String correlationId) {
    return executeNewAttempt(
        payment, route, loadProvider(routeCodeOf(route)), attemptNumber, reason, correlationId);
  }

  private PayoutRoute loadActiveRoute(String routeCode) {
    PayoutRoute route =
        routes
            .findByCode(routeCode)
            .orElseThrow(() -> new IllegalStateException("route " + routeCode + " not found"));
    if (!route.isActive()) {
      throw new IllegalStateException("route " + routeCode + " is inactive");
    }
    return route;
  }

  private PayoutProvider loadProvider(String routeCode) {
    PayoutProvider provider = providersByCode.get(routeCode);
    if (provider == null) {
      throw new IllegalStateException("provider " + routeCode + " is unavailable");
    }
    return provider;
  }

  private void requirePayoutEligible(PaymentSnapshot payment) {
    if (payment.status() == PaymentStatus.COMPLETED || payment.status() == PaymentStatus.REFUNDED) {
      throw new IllegalStateException("payment " + payment.paymentId() + " is not payout eligible");
    }
  }

  private void publish(
      String topic,
      String paymentId,
      String routeCode,
      int attemptNumber,
      String summary,
      Map<String, Object> extra,
      String correlationId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("routeCode", routeCode);
    details.put("attempt", attemptNumber);
    details.put("summary", summary);
    details.putAll(extra);
    events.publish(
        topic, PaymentEventPayload.random(paymentId, clock.instant(), details), correlationId);
  }

  private static String routeCodeOf(PayoutRoute route) {
    return route.getRouteCode();
  }

  private static void requireCorrelationId(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
  }
}
