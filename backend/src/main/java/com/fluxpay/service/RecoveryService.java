package com.fluxpay.service;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.event.EventPublisher;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventPayload;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.dto.RecoveryResult;
import com.fluxpay.repository.PaymentEventStore;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retries failed payouts on the same route, switches them to a different active route, or posts a
 * balanced compensating refund.
 *
 * <p>Retry and switch never debit: this class has no {@code LedgerWriter} dependency. Both paths
 * delegate to {@link PayoutExecutionService#executeNewAttempt} which records the next attempt
 * without touching ledgers. Refund delegates to {@link RefundJournalService} and publishes a
 * deterministic {@code payment.refunded} event.
 */
@Service
@Transactional
public class RecoveryService {

  private final PaymentReader paymentReader;
  private final PayoutRouteRepository routes;
  private final PayoutAttemptRepository attempts;
  private final PayoutExecutionService execution;
  private final RefundJournalService refunds;
  private final PaymentEventStore eventStore;
  private final EventPublisher events;
  private final Clock clock;

  public RecoveryService(
      PaymentReader paymentReader,
      PayoutRouteRepository routes,
      PayoutAttemptRepository attempts,
      PayoutExecutionService execution) {
    this(paymentReader, routes, attempts, execution, null, null, null, null);
  }

  @Autowired
  public RecoveryService(
      PaymentReader paymentReader,
      PayoutRouteRepository routes,
      PayoutAttemptRepository attempts,
      PayoutExecutionService execution,
      RefundJournalService refunds,
      PaymentEventStore eventStore,
      EventPublisher events,
      Clock clock) {
    this.paymentReader = Objects.requireNonNull(paymentReader, "paymentReader must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
    this.execution = Objects.requireNonNull(execution, "execution must not be null");
    this.refunds = refunds;
    this.eventStore = eventStore;
    this.events = events;
    this.clock = clock;
  }

  public PayoutOutcome retry(String paymentId, String correlationId) {
    PayoutAttempt latest = latestFailed(paymentId);
    PaymentSnapshot payment = paymentReader.get(paymentId);
    assertNotRefunded(payment);
    PayoutRoute route =
        routes
            .findById(latest.routeId())
            .orElseThrow(
                () -> new NoSuchElementException("route " + latest.routeId() + " not found"));
    if (!route.isActive()) {
      throw new IllegalStateException("route " + route.getRouteCode() + " is inactive");
    }
    return execution.executeNewAttempt(
        payment, route, latest.attemptNumber() + 1, "RETRY", correlationId);
  }

  public PayoutOutcome switchRoute(String paymentId, String newRouteCode, String correlationId) {
    PayoutAttempt latest = latestFailed(paymentId);
    PaymentSnapshot earlyPayment = paymentReader.get(paymentId);
    assertNotRefunded(earlyPayment);
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
    PaymentSnapshot payment = earlyPayment;
    return execution.executeNewAttempt(
        payment, route, latest.attemptNumber() + 1, "SWITCH", correlationId);
  }

  public RecoveryResult refund(String paymentId, String correlationId) {
    Objects.requireNonNull(refunds, "refunds must not be null");
    Objects.requireNonNull(eventStore, "eventStore must not be null");
    Objects.requireNonNull(events, "events must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    PayoutAttempt latest = latestFailed(paymentId);
    Instant now = clock.instant();
    // A persisted timeline event proves publication succeeded. Ledger entries alone do not:
    // the mock ledger survives transaction rollback after a failed Kafka send.
    if (eventStore.contains(paymentId, EventTopics.PAYMENT_REFUNDED)) {
      String replayEventId = PaymentEventPayload.refund(paymentId, now, Map.of()).eventId();
      return new RecoveryResult(paymentId, replayEventId, true);
    }
    PaymentSnapshot payment = paymentReader.get(paymentId);
    boolean replay = refunds.isAlreadyRefunded(payment);
    if (!replay) {
      refunds.refund(payment);
    }
    String summary = "Refund issued for attempt " + latest.attemptNumber();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("attempt", latest.attemptNumber());
    details.put("currency", payment.sourceCurrency());
    details.put("amount", payment.amount());
    details.put("summary", summary);
    PaymentEventPayload payload = PaymentEventPayload.refund(paymentId, now, details);
    events.publish(EventTopics.PAYMENT_REFUNDED, payload, correlationId);
    return new RecoveryResult(paymentId, payload.eventId(), replay);
  }

  private PayoutAttempt latestFailed(String paymentId) {
    attempts
        .lockPayment(paymentId)
        .orElseThrow(
            () -> new NoSuchElementException("payout attempt for " + paymentId + " not found"));
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

  private void assertNotRefunded(PaymentSnapshot payment) {
    String paymentId = payment.paymentId();
    if (eventStore != null && eventStore.contains(paymentId, EventTopics.PAYMENT_REFUNDED)) {
      throw new IllegalStateException("payment " + paymentId + " already refunded");
    }
    if (refunds != null && refunds.isAlreadyRefunded(payment)) {
      throw new IllegalStateException("payment " + paymentId + " already refunded");
    }
  }
}
