package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.QuoteResponse;
import com.fluxpay.repository.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteService {
  private static final BigDecimal BPS = BigDecimal.valueOf(10000);
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final FxRateProvider fx;
  private final Clock clock;

  public QuoteService(
      PaymentRepository payments, PaymentQuoteRepository quotes, FxRateProvider fx, Clock clock) {
    this.payments = payments;
    this.quotes = quotes;
    this.fx = fx;
    this.clock = clock;
  }

  @Transactional
  public QuoteResponse createOrCurrent(UUID userId, UUID paymentId) {
    Payment p = payments.lockOwned(paymentId, userId).orElseThrow(() -> notFound());
    if (p.flowVersion() != 1)
      throw new M3BusinessException(
          HttpStatus.CONFLICT, "LEGACY_PAYMENT", "Legacy payments cannot be modified.");
    if (p.status() != PaymentLifecycleStatus.DRAFT && p.status() != PaymentLifecycleStatus.QUOTED)
      throw conflict(
          "INVALID_PAYMENT_STATE", "Quotes can only be requested for draft or quoted payments.");
    Instant now = Instant.now(clock);
    if (p.currentQuoteGeneration() != null) {
      List<PaymentQuote> current =
          quotes.findByPaymentIdAndGenerationOrderByRouteAsc(p.id(), p.currentQuoteGeneration());
      if (current.size() == 3 && current.get(0).expiresAt().isAfter(now))
        return response(p, current, now);
    }
    BigDecimal rate;
    try {
      rate = fx.rate(p.sourceCurrency(), p.payoutCurrency());
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "FX_UNAVAILABLE", "FX rates are unavailable.");
    }
    if (rate == null || rate.signum() <= 0)
      throw new M3BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "FX_UNAVAILABLE", "FX rates are unavailable.");
    int generation = p.nextQuoteGeneration();
    Instant expires = now.plus(Duration.ofMinutes(15));
    List<PaymentQuote> generated = new ArrayList<>();
    for (QuoteRoute route : QuoteRoute.values()) {
      int spread = route == QuoteRoute.CHEAPEST ? 30 : route == QuoteRoute.BALANCED ? 60 : 110;
      BigDecimal fee =
          route == QuoteRoute.CHEAPEST
              ? new BigDecimal("1.9900")
              : route == QuoteRoute.BALANCED ? new BigDecimal("0.9900") : new BigDecimal("2.9900");
      if (p.sourceAmount().compareTo(fee) <= 0)
        throw new M3BusinessException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVALID_AMOUNT",
            "Amount must exceed every route fee.");
      BigDecimal offered =
          rate.multiply(
                  BigDecimal.ONE.subtract(
                      BigDecimal.valueOf(spread).divide(BPS, 10, RoundingMode.HALF_UP)))
              .setScale(6, RoundingMode.HALF_UP);
      BigDecimal recipient =
          p.sourceAmount().subtract(fee).multiply(offered).setScale(4, RoundingMode.HALF_UP);
      if (recipient.signum() <= 0)
        throw new M3BusinessException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVALID_AMOUNT",
            "Quote recipient amount must be positive.");
      generated.add(
          new PaymentQuote(
              UUID.randomUUID(),
              p.id(),
              generation,
              route,
              rate.setScale(6, RoundingMode.HALF_UP),
              spread,
              offered,
              fee,
              recipient,
              route == QuoteRoute.CHEAPEST ? 1440 : route == QuoteRoute.BALANCED ? 240 : 15,
              false,
              now,
              expires));
    }
    PaymentQuote winner = recommend(p.preference(), generated);
    generated.replaceAll(q -> q.withRecommendation(q.id().equals(winner.id())));
    generated = quotes.saveAll(generated);
    p.quoted(generation, now);
    return response(p, generated, now);
  }

  @Transactional(readOnly = true)
  public QuoteResponse get(UUID userId, UUID paymentId) {
    Payment p = payments.findByIdAndSenderId(paymentId, userId).orElseThrow(() -> notFound());
    if (p.flowVersion() != 1)
      throw new M3BusinessException(
          HttpStatus.CONFLICT, "LEGACY_PAYMENT", "Legacy payments cannot be modified.");
    if (p.currentQuoteGeneration() == null)
      throw new M3BusinessException(
          HttpStatus.NOT_FOUND, "QUOTES_NOT_FOUND", "No quotes exist for this payment.");
    return response(
        p,
        quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, p.currentQuoteGeneration()),
        Instant.now(clock));
  }

  private PaymentQuote recommend(QuoteRoute preference, List<PaymentQuote> all) {
    Comparator<PaymentQuote> c =
        preference == QuoteRoute.FASTEST
            ? Comparator.comparingInt(PaymentQuote::estimatedMinutes)
                .thenComparing(PaymentQuote::recipientAmount, Comparator.reverseOrder())
                .thenComparing(q -> q.route().name())
            : preference == QuoteRoute.BALANCED
                ? Comparator.comparing((PaymentQuote q) -> q.route() != QuoteRoute.BALANCED)
                    .thenComparing(PaymentQuote::recipientAmount, Comparator.reverseOrder())
                    .thenComparing(q -> q.route().name())
                : Comparator.comparing(PaymentQuote::recipientAmount, Comparator.reverseOrder())
                    .thenComparingInt(PaymentQuote::estimatedMinutes)
                    .thenComparing(q -> q.route().name());
    return all.stream().sorted(c).findFirst().orElseThrow();
  }

  private QuoteResponse response(Payment p, List<PaymentQuote> qs, Instant now) {
    UUID recommended =
        qs.stream()
            .filter(PaymentQuote::recommended)
            .map(PaymentQuote::id)
            .findFirst()
            .orElse(null);
    return new QuoteResponse(
        p.id(),
        recommended,
        "Recommendation follows the selected " + p.preference() + " preference.",
        qs.isEmpty() ? null : qs.get(0).expiresAt(),
        now,
        qs.stream()
            .map(
                q ->
                    new QuoteResponse.Quote(
                        q.id(),
                        q.route().name(),
                        q.marketRate().toPlainString(),
                        q.offeredRate().toPlainString(),
                        q.feeAmount().toPlainString(),
                        q.recipientAmount().toPlainString(),
                        q.estimatedMinutes(),
                        q.recommended()))
            .toList());
  }

  private M3BusinessException notFound() {
    return new M3BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found.");
  }

  private M3BusinessException conflict(String c, String m) {
    return new M3BusinessException(HttpStatus.CONFLICT, c, m);
  }
}
