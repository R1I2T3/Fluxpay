package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.domain.PaymentStatus;
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
  private final TransferRouteRepository routes;
  private final RoutePricingService pricing;
  private final RouteRecommender recommender;
  private final PaymentOperationService operations;
  private final PaymentRecoveryEligibility recoveryEligibility;

  public QuoteService(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      FxRateProvider fx,
      Clock clock,
      TransferRouteRepository routes,
      RoutePricingService pricing,
      RouteRecommender recommender,
      PaymentOperationService operations,
      PaymentRecoveryEligibility recoveryEligibility) {
    this.payments = payments;
    this.quotes = quotes;
    this.fx = fx;
    this.clock = clock;
    this.routes = routes;
    this.pricing = pricing;
    this.recommender = recommender;
    this.operations = operations;
    this.recoveryEligibility = recoveryEligibility;
  }

  public QuoteResponse createOrCurrent(UUID userId, UUID paymentId, String key) {
    return operations
        .execute(
            userId,
            key,
            "QUOTE",
            paymentId,
            Map.of(),
            QuoteResponse.class,
            () ->
                new PaymentOperationService.Result<>(
                    201, generateOrCurrent(userId, paymentId), paymentId))
        .response();
  }

  private QuoteResponse generateOrCurrent(UUID userId, UUID paymentId) {
    Payment p = payments.lockOwned(paymentId, userId).orElseThrow(() -> notFound());
    boolean recovering = p.status() == PaymentStatus.FAILED;
    if (!recovering && p.status() != PaymentStatus.DRAFT && p.status() != PaymentStatus.QUOTED)
      throw conflict(
          "INVALID_PAYMENT_STATE", "Quotes require a draft, quoted or final failed payment.");
    if (recovering) recoveryEligibility.require(p);
    Instant now = Instant.now(clock);
    if (!recovering && p.currentQuoteGeneration() != null) {
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
    var active = routes.findByActiveTrueOrderByRouteCodeAsc();
    var pricedRoutes = pricing.price(p.sourceAmount(), rate, active);
    var recommendation = recommender.recommend(p.preference(), pricedRoutes);
    int generation = p.nextQuoteGeneration();
    for (var priced : recommendation.quotes()) {
      // Task 7 owns this path — compile-restoration only
      var inner = priced.quote();
      TransferRoute route = inner.route();
      generated.add(
          new PaymentQuote(
              UUID.randomUUID(),
              p.id(),
              generation,
              route.code(),
              inner.marketRate(),
              route.fxSpreadPercentage(),
              inner.offeredRate(),
              inner.feeAmount(),
              inner.recipientAmount(),
              route.estimatedMinutes(),
              // Task 7 owns this path — compile-restoration only
              route.code().equals(recommendation.recommended().quote().route().code()),
              now,
              expires));
    }
    generated = quotes.saveAll(generated);
    if (recovering) p.recoveryQuoted(generation, now);
    else p.quoted(generation, now);
    return response(p, generated, now);
  }

  @Transactional(readOnly = true)
  public QuoteResponse get(UUID userId, UUID paymentId) {
    Payment p = payments.findByIdAndSenderId(paymentId, userId).orElseThrow(() -> notFound());
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
