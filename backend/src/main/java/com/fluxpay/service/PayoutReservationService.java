package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.*;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reserves an execution inside the operation coordinator's transaction. */
@Service
public class PayoutReservationService {
  private final PaymentRepository payments;
  private final PaymentReader reader;
  private final PayoutAttemptRepository attempts;
  private final PayoutRouteRepository routes;
  private final SelectedQuoteService quotes;
  private final Clock clock;
  private final com.fluxpay.common.contracts.LedgerWriter ledger;
  private final PayoutOutboxService outbox;

  public PayoutReservationService(
      PaymentRepository payments,
      PaymentReader reader,
      PayoutAttemptRepository attempts,
      PayoutRouteRepository routes,
      SelectedQuoteService quotes,
      Clock clock,
      com.fluxpay.common.contracts.LedgerWriter ledger,
      PayoutOutboxService outbox) {
    this.payments = payments;
    this.reader = reader;
    this.attempts = attempts;
    this.routes = routes;
    this.quotes = quotes;
    this.clock = clock;
    this.ledger = ledger;
    this.outbox = outbox;
  }

  public record Reserved(
      UUID userId,
      UUID paymentId,
      UUID attemptId,
      String routeCode,
      int attemptNumber,
      AcceptedQuote quote,
      PayoutCmd command) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public Reserved reserve(
      UUID user,
      UUID paymentId,
      String action,
      String routeCode,
      UUID replacementQuote,
      String correlationId,
      Predicate<String> providerAvailable) {
    var payment = payments.lockOwned(paymentId, user).orElseThrow();
    var snapshot = reader.get(paymentId.toString());
    if (payment.postedAt() == null || snapshot.posting() == null)
      throw new IllegalStateException("Only funded PROCESSING payments can submit a payout");
    String refundReference = "refund:" + paymentId;
    if (ledger.contains(refundReference + ":sender:credit")
        || ledger.contains(refundReference + ":clearing:debit")
        || ledger.contains(refundReference + ":fee:debit"))
      throw new IllegalStateException("Original payment funding has already been reversed");
    var latest = attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId.toString());
    int number;
    if ("SUBMIT".equals(action)) {
      if (payment.status() != PaymentStatus.PROCESSING || latest.isPresent())
        throw new IllegalStateException("Payment is not eligible for initial payout");
      number = 1;
    } else if ("RETRY".equals(action) || "SWITCH".equals(action)) {
      if (payment.status() != PaymentStatus.FAILED
          || latest.isEmpty()
          || latest.get().status() != PayoutAttemptStatus.FAILED)
        throw new IllegalStateException("Only a final failed payout can be retried");
      if ("RETRY".equals(action))
        routeCode = routes.findById(latest.get().routeId()).orElseThrow().getRouteCode();
      number = latest.get().attemptNumber() + 1;
    } else throw new IllegalArgumentException("Unsupported payout action");
    var route =
        routes
            .findByCode(routeCode)
            .filter(PayoutRoute::isActive)
            .orElseThrow(
                () ->
                    "SWITCH".equals(action)
                        ? invalidSwitchCandidate()
                        : new IllegalStateException("Payout route is unavailable"));
    if (!providerAvailable.test(routeCode)) {
      if ("SWITCH".equals(action)) throw invalidSwitchCandidate();
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PAYOUT_PROVIDER_UNAVAILABLE",
          "No payout provider is configured for route " + routeCode + ".");
    }
    if ("SWITCH".equals(action) && route.getId().equals(latest.orElseThrow().routeId()))
      throw invalidSwitchCandidate();
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
    return new Reserved(
        user,
        paymentId,
        attempt.id(),
        routeCode,
        number,
        quote,
        new PayoutCmd(
            paymentId.toString(),
            snapshot.amount(),
            snapshot.sourceCurrency(),
            snapshot.targetCurrency(),
            routeCode,
            quote.feeAmount(),
            number,
            quote.offeredRate(),
            quote.recipientAmount(),
            attempt.id(),
            "payout:" + attempt.id()));
  }

  private static com.fluxpay.exception.BusinessException invalidSwitchCandidate() {
    return new com.fluxpay.exception.BusinessException(
        org.springframework.http.HttpStatus.CONFLICT,
        "REQUOTE_REQUIRED",
        "Select a current replacement quote for a different available route.");
  }
}
