package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.dto.QuoteResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteService {
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final FxRateProvider fx;
  private final Clock clock;
  private final PayoutRouteRepository routes;
  private final RoutePricingService pricing;
  private final RouteRecommender recommender;

  public QuoteService(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      FxRateProvider fx,
      Clock clock,
      PayoutRouteRepository routes,
      RoutePricingService pricing,
      RouteRecommender recommender) {
    this.payments = payments;
    this.quotes = quotes;
    this.fx = fx;
    this.clock = clock;
    this.routes = routes;
    this.pricing = pricing;
    this.recommender = recommender;
  }

  @Transactional
  public QuoteResponse createOrCurrent(UUID userId, UUID paymentId) {
    Payment p = payments.lockOwned(paymentId, userId).orElseThrow(() -> notFound());
    if (p.flowVersion() != 1)
      throw new BusinessException(
          HttpStatus.CONFLICT, "LEGACY_PAYMENT", "Legacy payments cannot be modified.");
    if (p.status() != PaymentLifecycleStatus.DRAFT && p.status() != PaymentLifecycleStatus.QUOTED)
      throw conflict(
          "INVALID_PAYMENT_STATE", "Quotes can only be requested for draft or quoted payments.");
    Instant now = Instant.now(clock);
    if (p.currentQuoteGeneration() != null) {
      List<PaymentQuote> current =
          quotes.findByPaymentIdAndGenerationOrderByRouteAsc(p.id(), p.currentQuoteGeneration());
      if (!current.isEmpty() && current.stream().allMatch(q -> q.expiresAt().isAfter(now)))
        return response(p, current, now);
    }
    BigDecimal rate;
    try {
      rate = fx.rate(p.sourceCurrency(), p.payoutCurrency());
    } catch (Exception e) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "FX_UNAVAILABLE", "FX rates are unavailable.");
    }
    if (rate == null || rate.signum() <= 0)
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "FX_UNAVAILABLE", "FX rates are unavailable.");
    Instant expires = now.plus(Duration.ofMinutes(15));
    List<PaymentQuote> generated = new ArrayList<>();
    var recommendation =
        recommender.recommend(
            p.preference(),
            pricing.price(p.sourceAmount(), rate, routes.findByActiveTrueOrderByRouteCodeAsc()));
    int generation = p.nextQuoteGeneration();
    for (var priced : recommendation.quotes()) {
      PayoutRoute route = priced.route();
      generated.add(
          new PaymentQuote(
              UUID.randomUUID(),
              p.id(),
              generation,
              route.code(),
              priced.marketRate(),
              route.fxSpreadPercentage(),
              priced.offeredRate(),
              priced.feeAmount(),
              priced.recipientAmount(),
              route.estimatedMinutes(),
              route.code().equals(recommendation.recommended().code()),
              now,
              expires));
    }
    generated = quotes.saveAll(generated);
    p.quoted(generation, now);
    return response(p, generated, now);
  }

  @Transactional(readOnly = true)
  public QuoteResponse get(UUID userId, UUID paymentId) {
    Payment p = payments.findByIdAndSenderId(paymentId, userId).orElseThrow(() -> notFound());
    if (p.flowVersion() != 1)
      throw new BusinessException(
          HttpStatus.CONFLICT, "LEGACY_PAYMENT", "Legacy payments cannot be modified.");
    if (p.currentQuoteGeneration() == null)
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "QUOTES_NOT_FOUND", "No quotes exist for this payment.");
    return response(
        p,
        quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, p.currentQuoteGeneration()),
        Instant.now(clock));
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
                        q.route(),
                        q.marketRate().toPlainString(),
                        q.offeredRate().toPlainString(),
                        q.feeAmount().toPlainString(),
                        q.recipientAmount().toPlainString(),
                        q.estimatedMinutes(),
                        q.recommended()))
            .toList());
  }

  private BusinessException notFound() {
    return new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found.");
  }

  private BusinessException conflict(String c, String m) {
    return new BusinessException(HttpStatus.CONFLICT, c, m);
  }
}
