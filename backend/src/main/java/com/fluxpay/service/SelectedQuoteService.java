package com.fluxpay.service;

import com.fluxpay.domain.AcceptedQuote;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.exception.QuoteMismatchException;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SelectedQuoteService {
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final Clock clock;
  private final TransferRouteRepository routes;

  public SelectedQuoteService(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      Clock clock,
      TransferRouteRepository routes) {
    this.payments = payments;
    this.quotes = quotes;
    this.clock = clock;
    this.routes = routes;
  }

  @Transactional(readOnly = true)
  public AcceptedQuote require(PaymentSnapshot snapshot, String requestedRouteCode) {
    UUID id = UUID.fromString(snapshot.paymentId());
    var payment =
        payments.findById(id).orElseThrow(() -> new QuoteExpiredException("quote is missing"));
    if (!payment.senderId().equals(snapshot.senderUserId())) {
      throw new QuoteMismatchException("quote owner does not match payment owner");
    }
    if (payment.currentQuoteGeneration() == null) {
      throw new QuoteExpiredException("quote generation is missing");
    }
    if (payment.selectedQuoteId() == null) {
      throw new QuoteMismatchException("no quote has been selected");
    }
    var selected =
        quotes
            .findByPaymentIdAndGenerationOrderByRouteAsc(id, payment.currentQuoteGeneration())
            .stream()
            .filter(q -> q.id().equals(payment.selectedQuoteId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new QuoteMismatchException("selected quote is not in the current generation"));
    if (!selected.paymentId().equals(id)
        || !Objects.equals(payment.currentQuoteGeneration(), selected.generation())
        || !selected.route().equals(requestedRouteCode)) {
      throw new QuoteMismatchException(
          "selected quote payment, generation or route does not match");
    }
    if (!clock.instant().isBefore(selected.expiresAt())) {
      throw new QuoteExpiredException("selected quote has expired");
    }
    routes
        .findByRouteCode(selected.route())
        .filter(r -> r.isActive())
        .filter(r -> r.getArchivedAt() == null)
        .filter(r -> r.provider() != null)
        .filter(r -> r.provider().isActive())
        .filter(r -> r.provider().getArchivedAt() == null)
        .orElseThrow(() -> new QuoteMismatchException("selected quote route is unavailable"));
    return new AcceptedQuote(
        selected.id(),
        selected.route(),
        selected.feeAmount(),
        payment.sourceAmount().subtract(selected.feeAmount()).setScale(4, RoundingMode.HALF_EVEN),
        selected.offeredRate(),
        selected.recipientAmount());
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public AcceptedQuote acceptedRetry(
      com.fluxpay.beans.Payment payment, PaymentSnapshot snapshot, String route) {
    // Expiry gates new acceptance, not redelivery of already accepted, funded economics.
    // Reservation has locked the payment and requires a definitive failed attempt on this route.
    var quote =
        payment.selectedQuoteId() == null
            ? null
            : quotes.findByIdAndPaymentId(payment.selectedQuoteId(), payment.id()).orElse(null);
    if (quote == null
        || !payment.senderId().equals(snapshot.senderUserId())
        || !quote.route().equals(route)
        || snapshot.posting() == null
        || payment.sourceAmount().compareTo(snapshot.posting().gross()) != 0) {
      throw new com.fluxpay.exception.BusinessException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REQUOTE_REQUIRED",
          "Retry requires the previously accepted quote for the same funded payment and route.");
    }
    return new AcceptedQuote(
        quote.id(),
        quote.route(),
        quote.feeAmount(),
        payment.sourceAmount().subtract(quote.feeAmount()).setScale(4, RoundingMode.HALF_EVEN),
        quote.offeredRate(),
        quote.recipientAmount());
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public AcceptedQuote replacement(
      com.fluxpay.beans.Payment payment, PaymentSnapshot snapshot, String route, UUID quoteId) {
    var quote =
        quoteId == null ? null : quotes.findByIdAndPaymentId(quoteId, payment.id()).orElse(null);
    if (quote == null
        || !payment.senderId().equals(snapshot.senderUserId())
        || !Objects.equals(payment.currentQuoteGeneration(), quote.generation())
        || !quote.route().equals(route)
        || !clock.instant().isBefore(quote.expiresAt())
        || snapshot.posting() == null
        || quote.feeAmount().compareTo(snapshot.posting().fee()) != 0
        || payment.sourceAmount().compareTo(snapshot.posting().gross()) != 0
        || payment.sourceAmount().subtract(quote.feeAmount()).compareTo(snapshot.posting().net())
            != 0)
      throw new com.fluxpay.exception.BusinessException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REQUOTE_REQUIRED",
          "Select a current replacement quote for this payment and route with the same source funding allocation.");
    return new AcceptedQuote(
        quote.id(),
        quote.route(),
        quote.feeAmount(),
        snapshot.posting().net(),
        quote.offeredRate(),
        quote.recipientAmount());
  }
}
