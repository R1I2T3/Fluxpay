package com.fluxpay.config;

import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.exception.QuoteMismatchException;
import com.fluxpay.service.PaymentEligibilityGate;
import com.fluxpay.service.PaymentSnapshot;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Provisional M4 {@link PaymentEligibilityGate} previewing the M3 contract end-to-end.
 *
 * <p>CHECKPOINT: M3 owns the production {@code PaymentEligibilityGate} over {@code
 * payment_quotes}/{@code Idempotency-Key}; this in-memory stub must be replaced when M3 merges. A
 * duplicate {@code Idempotency-Key} returns {@code alreadyConfirmed=true} with the original event
 * ID and never creates a second payment/attempt.
 */
@Component
@Profile("mock")
public class InMemoryPaymentEligibilityGate implements PaymentEligibilityGate {

  private record QuoteState(String routeCode, Instant expiresAt) {}

  private final Map<String, QuoteState> quotes = new HashMap<>();
  private final Map<String, String> confirmed = new HashMap<>();

  public InMemoryPaymentEligibilityGate() {
    quotes.put("P-001", new QuoteState("STANDARD_BANK", Instant.now().plusSeconds(15 * 60)));
    quotes.put("P-002", new QuoteState("STANDARD_BANK", Instant.now().plusSeconds(15 * 60)));
  }

  @Override
  public void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode) {
    QuoteState quote = quotes.get(payment.paymentId());
    if (quote == null || Instant.now().isAfter(quote.expiresAt())) {
      throw new QuoteExpiredException(
          "quote for " + payment.paymentId() + " is expired or missing");
    }
    if (!quote.routeCode().equals(requestedRouteCode)) {
      throw new QuoteMismatchException(
          "quote route " + quote.routeCode() + " does not match " + requestedRouteCode);
    }
  }

  @Override
  public ConfirmOutcome confirmIdempotent(PaymentSnapshot payment, String idempotencyKey) {
    String key = payment.paymentId() + ":" + idempotencyKey;
    synchronized (confirmed) {
      if (confirmed.containsKey(key)) {
        if (confirmed.get(key) == null) {
          throw new IllegalStateException("payment request is still in progress");
        }
        return new ConfirmOutcome(true, confirmed.get(key));
      }
      confirmed.put(key, null);
      return new ConfirmOutcome(false, null);
    }
  }

  @Override
  public void complete(PaymentSnapshot payment, String idempotencyKey, String eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    if (eventId.isBlank()) {
      throw new IllegalArgumentException("eventId must not be blank");
    }
    synchronized (confirmed) {
      String key = payment.paymentId() + ":" + idempotencyKey;
      if (!confirmed.containsKey(key) || confirmed.get(key) != null) {
        throw new IllegalStateException("no pending payment reservation");
      }
      confirmed.put(key, eventId);
    }
  }

  @Override
  public void release(PaymentSnapshot payment, String idempotencyKey) {
    synchronized (confirmed) {
      confirmed.remove(payment.paymentId() + ":" + idempotencyKey, null);
    }
  }
}
