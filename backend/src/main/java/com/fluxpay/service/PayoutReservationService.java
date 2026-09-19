package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.*;
import com.fluxpay.dto.TransferProviderSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRouteSnapshot;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reserves an execution inside the operation coordinator's transaction. */
@Service
public class PayoutReservationService {
  private final PaymentRepository payments;
  private final PaymentReader reader;
  private final PayoutAttemptRepository attempts;
  private final TransferRouteRepository routes;
  private final SelectedQuoteService quotes;
  private final Clock clock;
  private final com.fluxpay.common.contracts.LedgerWriter ledger;
  private final PayoutOutboxService outbox;
  private final RailRegistry rails;

  public PayoutReservationService(
      PaymentRepository payments,
      PaymentReader reader,
      PayoutAttemptRepository attempts,
      TransferRouteRepository routes,
      SelectedQuoteService quotes,
      Clock clock,
      com.fluxpay.common.contracts.LedgerWriter ledger,
      PayoutOutboxService outbox,
      RailRegistry rails) {
    this.payments = payments;
    this.reader = reader;
    this.attempts = attempts;
    this.routes = routes;
    this.quotes = quotes;
    this.clock = clock;
    this.ledger = ledger;
    this.outbox = outbox;
    this.rails = Objects.requireNonNull(rails, "rails must not be null");
  }

  public record Reserved(
      UUID userId,
      UUID paymentId,
      UUID attemptId,
      int attemptNumber,
      AcceptedQuote quote,
      TransferProviderSnapshot provider,
      TransferRouteSnapshot route,
      ExternalAccountDestination destination,
      TransferRailCommand command) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public Reserved reserve(
      UUID user,
      UUID paymentId,
      String action,
      String routeCode,
      UUID replacementQuote,
      String correlationId) {
    return reserveChecked(
        user, paymentId, action, routeCode, replacementQuote, correlationId, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Reserved reserveAutomatic(
      UUID user, UUID paymentId, int failedAttempt, String correlationId) {
    return reserveChecked(user, paymentId, "RETRY", null, null, correlationId, failedAttempt);
  }

  private Reserved reserveChecked(
      UUID user,
      UUID paymentId,
      String action,
      String routeCode,
      UUID replacementQuote,
      String correlationId,
      Integer failedAttempt) {
    var payment = payments.lockOwned(paymentId, user).orElseThrow();
    var snapshot = reader.get(paymentId.toString());
    if (payment.postedAt() == null || snapshot.posting() == null)
      throw new IllegalStateException("Only funded PROCESSING payments can submit a payout");
    if (snapshot.destination() == null)
      throw new IllegalStateException("Only payments with a frozen recipient can submit a payout");
    String refundReference = "refund:" + paymentId;
    if (ledger.contains(refundReference + ":sender:credit")
        || ledger.contains(refundReference + ":clearing:debit")
        || ledger.contains(refundReference + ":fee:debit"))
      throw new IllegalStateException("Original payment funding has already been reversed");
    var latest = attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId.toString());
    if (failedAttempt != null
        && (payment.status() != PaymentStatus.FAILED
            || latest.isEmpty()
            || latest.get().status() != PayoutAttemptStatus.FAILED
            || latest.get().attemptNumber() != failedAttempt))
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "STALE_RECOVERY",
          "A later payout or refund superseded this recovery command.");
    int number;
    TransferRoute route;
    if ("SUBMIT".equals(action)) {
      if (payment.status() != PaymentStatus.PROCESSING || latest.isPresent())
        throw new IllegalStateException("Payment is not eligible for initial payout");
      number = 1;
      route = requireActiveCatalogue(routeCode, action);
    } else if ("RETRY".equals(action) || "SWITCH".equals(action)) {
      if (payment.status() != PaymentStatus.FAILED
          || latest.isEmpty()
          || latest.get().status() != PayoutAttemptStatus.FAILED)
        throw new IllegalStateException("Only a final failed payout can be retried");
      if ("RETRY".equals(action)) {
        // Retry redelivers the failed attempt's route without reapplying active flags.
        route = routes.findById(latest.get().routeId()).orElseThrow();
        routeCode = route.getRouteCode();
      } else {
        route = requireActiveCatalogue(routeCode, action);
      }
      if ("SWITCH".equals(action) && route.getId().equals(latest.orElseThrow().routeId()))
        throw invalidSwitchCandidate();
      number = latest.get().attemptNumber() + 1;
    } else throw new IllegalArgumentException("Unsupported payout action");
    // Fail fast on rail capability before claiming a completed external action: the reservation
    // already rejects unknown rails with 503 and incompatible destinations with 400, so execution
    // never reserves an attempt it cannot deliver.
    rails.requireCompatible(route.provider().getRailType(), route.getDestinationType());
    var quote =
        "SWITCH".equals(action) || ("RETRY".equals(action) && replacementQuote != null)
            ? quotes.replacement(payment, snapshot, routeCode, replacementQuote)
            : "RETRY".equals(action)
                ? quotes.acceptedRetry(payment, snapshot, routeCode)
                : quotes.require(snapshot, routeCode);
    if (quote.feeAmount().compareTo(snapshot.posting().fee()) != 0
        || quote.netSourceAmount().compareTo(snapshot.posting().net()) != 0)
      throw new com.fluxpay.exception.BusinessException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REQUOTE_REQUIRED",
          "Selected quote must preserve the original source funding allocation.");
    var attempt =
        PayoutAttempt.initiated(
            UUID.randomUUID(), paymentId.toString(), number, route.getId(), clock.instant());
    payment.selectAndProcess(quote.quoteId(), clock.instant());
    attempt.markProcessing();
    attempts.saveAndFlush(attempt);
    outbox.enqueue(
        payment,
        com.fluxpay.messaging.EventTopics.PAYMENT_ROUTE_SELECTED,
        correlationId,
        java.util.Map.of(
            "attempt",
            number,
            "routeCode",
            routeCode,
            "reason",
            action,
            "summary",
            "Payout route selected"));
    outbox.enqueue(
        payment,
        com.fluxpay.messaging.EventTopics.PAYOUT_SUBMITTED,
        correlationId,
        java.util.Map.of(
            "attempt",
            number,
            "routeCode",
            routeCode,
            "attemptId",
            attempt.id().toString(),
            "summary",
            "Payout reserved for provider delivery"));
    var provider = route.provider();
    var providerSnapshot =
        new TransferProviderSnapshot(
            provider.getId(), provider.getProviderCode(), provider.getRailType());
    var routeSnapshot =
        new TransferRouteSnapshot(
            route.getId(), route.getRouteCode(), route.getDestinationType(), provider.getId());
    var destination = snapshot.destination();
    return new Reserved(
        user,
        paymentId,
        attempt.id(),
        number,
        quote,
        providerSnapshot,
        routeSnapshot,
        destination,
        new TransferRailCommand(
            paymentId,
            attempt.id(),
            user,
            snapshot.amount(),
            snapshot.sourceCurrency(),
            quote.recipientAmount(),
            snapshot.targetCurrency(),
            providerSnapshot,
            routeSnapshot,
            destination,
            number,
            quote.feeAmount(),
            quote.offeredRate(),
            "payout:" + attempt.id()));
  }

  /**
   * Initial and reswitch operations require an active catalogue: the route and its provider must
   * both be active and unarchived. A missing/inactive route is a server-side catalog problem (503
   * with PAYOUT_ROUTE_UNAVAILABLE). SWITCH keeps REQUOTE_REQUIRED so route recovery still flows
   * through an explicit replacement quote.
   */
  private TransferRoute requireActiveCatalogue(String routeCode, String action) {
    var route =
        routes
            .findByRouteCode(routeCode)
            .filter(TransferRoute::isActive)
            .filter(r -> r.getArchivedAt() == null)
            .filter(r -> r.provider() != null)
            .filter(r -> r.provider().isActive())
            .filter(r -> r.provider().getArchivedAt() == null)
            .orElseThrow(
                () ->
                    "SWITCH".equals(action)
                        ? invalidSwitchCandidate()
                        : new BusinessException(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "PAYOUT_ROUTE_UNAVAILABLE",
                            "No active payout route " + routeCode + " is configured."));
    return route;
  }

  private static com.fluxpay.exception.BusinessException invalidSwitchCandidate() {
    return new com.fluxpay.exception.BusinessException(
        org.springframework.http.HttpStatus.CONFLICT,
        "REQUOTE_REQUIRED",
        "Select a current replacement quote for a different available route.");
  }
}
