package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.M3PaymentOperation;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.exception.QuoteMismatchException;
import com.fluxpay.repository.M3PaymentOperationRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbPaymentEligibilityGate implements PaymentEligibilityGate {
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final M3PaymentOperationRepository operations;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public DbPaymentEligibilityGate(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      M3PaymentOperationRepository operations,
      Clock clock,
      ObjectMapper objectMapper) {
    this.payments = Objects.requireNonNull(payments, "payments must not be null");
    this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
    this.operations = Objects.requireNonNull(operations, "operations must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode) {
    UUID id = UUID.fromString(payment.paymentId());
    var owned =
        payments.findById(id).orElseThrow(() -> new QuoteExpiredException("quote is missing"));
    if (owned.currentQuoteGeneration() == null) {
      throw new QuoteExpiredException("quote for " + payment.paymentId() + " is missing");
    }
    var current =
        quotes.findByPaymentIdAndGenerationOrderByRouteAsc(id, owned.currentQuoteGeneration());
    PaymentQuote match =
        current.stream()
            .filter(q -> q.route().name().equals(requestedRouteCode))
            .findFirst()
            .orElseThrow(
                () ->
                    new QuoteMismatchException("quote route does not match " + requestedRouteCode));
    if (!Instant.now(clock).isBefore(match.expiresAt())) {
      throw new QuoteExpiredException("quote for " + payment.paymentId() + " is expired");
    }
  }

  @Override
  @Transactional
  public ConfirmOutcome confirmIdempotent(PaymentSnapshot payment, String idempotencyKey) {
    var existing =
        operations.findByUserIdAndOperationTypeAndClientKey(
            payment.senderUserId(), "PAYOUT_CONFIRM", idempotencyKey);
    if (existing.isPresent()) {
      String stored = existing.get().responseData();
      if (stored == null || stored.isBlank()) {
        throw new IllegalStateException("payment request is still in progress");
      }
      return new ConfirmOutcome(true, stored);
    }
    M3PaymentOperation pending =
        new M3PaymentOperation(
            UUID.randomUUID(),
            payment.senderUserId(),
            "PAYOUT_CONFIRM",
            idempotencyKey,
            payment.paymentId(),
            202,
            "",
            UUID.fromString(payment.paymentId()),
            Instant.now(clock));
    operations.saveAndFlush(pending);
    return new ConfirmOutcome(false, null);
  }

  @Override
  @Transactional
  public void complete(PaymentSnapshot payment, String idempotencyKey, String eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    var existing =
        operations
            .findByUserIdAndOperationTypeAndClientKey(
                payment.senderUserId(), "PAYOUT_CONFIRM", idempotencyKey)
            .orElseThrow(() -> new IllegalStateException("no pending payment reservation"));
    if (existing.responseData() != null && !existing.responseData().isBlank()) {
      throw new IllegalStateException("no pending payment reservation");
    }
    M3PaymentOperation done =
        new M3PaymentOperation(
            existing.id(),
            existing.userId(),
            existing.operationType(),
            existing.clientKey(),
            existing.normalizedRequest(),
            200,
            eventId,
            existing.paymentId(),
            existing.createdAt());
    operations.saveAndFlush(done);
  }

  @Override
  @Transactional
  public void release(PaymentSnapshot payment, String idempotencyKey) {
    operations
        .findByUserIdAndOperationTypeAndClientKey(
            payment.senderUserId(), "PAYOUT_CONFIRM", idempotencyKey)
        .filter(op -> op.responseData() == null || op.responseData().isBlank())
        .ifPresent(op -> operations.delete(op));
  }
}
