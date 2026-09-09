package com.fluxpay.service;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.dto.RoutePreference;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.dto.RouteRecommendation;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** PRD section 10.3 payout route recommendation over the active routes. */
@Service
public class RouteRecommender {

  private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
  private static final BigDecimal WEIGHT_RECIPIENT = new BigDecimal("0.45");
  private static final BigDecimal WEIGHT_SPEED = new BigDecimal("0.30");
  private static final BigDecimal WEIGHT_SUCCESS = new BigDecimal("0.25");

  public RouteRecommendation recommend(
      BigDecimal amount,
      RoutePreference preference,
      BigDecimal marketRate,
      List<PayoutRoute> routes) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    Objects.requireNonNull(preference, "preference must not be null");
    Objects.requireNonNull(marketRate, "marketRate must not be null");
    Objects.requireNonNull(routes, "routes must not be null");

    List<RouteQuote> quotes = new ArrayList<>();
    for (PayoutRoute route : routes) {
      if (route.isActive()) {
        quotes.add(quote(amount, marketRate, route));
      }
    }
    if (quotes.isEmpty()) {
      throw new IllegalStateException("no active payout routes");
    }

    List<RouteQuote> ranked = new ArrayList<>(quotes);
    ranked.sort(comparator(preference, ranked));
    return new RouteRecommendation(ranked.get(0).route(), List.copyOf(ranked));
  }

  private RouteQuote quote(BigDecimal amount, BigDecimal marketRate, PayoutRoute route) {
    BigDecimal offeredRate =
        marketRate.multiply(
            BigDecimal.ONE.subtract(
                BigDecimal.valueOf(route.fxSpreadPercentage().doubleValue())
                    .divide(new BigDecimal("100"), MathContext.DECIMAL64)));
    BigDecimal recipient = amount.multiply(offeredRate).subtract(route.baseFee());
    return new RouteQuote(
        route, marketRate, offeredRate, recipient.setScale(4, RoundingMode.HALF_EVEN));
  }

  private Comparator<RouteQuote> comparator(RoutePreference preference, List<RouteQuote> quotes) {
    return switch (preference) {
      case CHEAPEST ->
          Comparator.comparing(RouteQuote::recipientAmount, Comparator.reverseOrder())
              .thenComparing((RouteQuote q) -> q.route().successRate(), Comparator.reverseOrder())
              .thenComparing(q -> q.route().code());
      case FASTEST ->
          Comparator.comparingInt((RouteQuote q) -> q.route().estimatedMinutes())
              .thenComparing(q -> q.route().baseFee())
              .thenComparing(q -> q.route().code());
      case BALANCED -> balancedComparator(quotes);
    };
  }

  private Comparator<RouteQuote> balancedComparator(List<RouteQuote> quotes) {
    BigDecimal minRecipient = null;
    BigDecimal maxRecipient = null;
    BigDecimal minSpeed = null;
    BigDecimal maxSpeed = null;
    BigDecimal minSuccess = null;
    BigDecimal maxSuccess = null;
    Map<RouteQuote, BigDecimal> speeds = new IdentityHashMap<>();
    for (RouteQuote quote : quotes) {
      BigDecimal recipient = quote.recipientAmount();
      BigDecimal speed =
          BigDecimal.ONE.divide(
              BigDecimal.valueOf(quote.route().estimatedMinutes()), 12, RoundingMode.HALF_EVEN);
      BigDecimal success = quote.route().successRate();
      speeds.put(quote, speed);
      if (minRecipient == null || recipient.compareTo(minRecipient) < 0) {
        minRecipient = recipient;
      }
      if (maxRecipient == null || recipient.compareTo(maxRecipient) > 0) {
        maxRecipient = recipient;
      }
      if (minSpeed == null || speed.compareTo(minSpeed) < 0) {
        minSpeed = speed;
      }
      if (maxSpeed == null || speed.compareTo(maxSpeed) > 0) {
        maxSpeed = speed;
      }
      if (minSuccess == null || success.compareTo(minSuccess) < 0) {
        minSuccess = success;
      }
      if (maxSuccess == null || success.compareTo(maxSuccess) > 0) {
        maxSuccess = success;
      }
    }
    Map<RouteQuote, BigDecimal> scores = new IdentityHashMap<>();
    for (RouteQuote quote : quotes) {
      BigDecimal score =
          WEIGHT_RECIPIENT
              .multiply(normalize(quote.recipientAmount(), minRecipient, maxRecipient))
              .add(WEIGHT_SPEED.multiply(normalize(speeds.get(quote), minSpeed, maxSpeed)))
              .add(
                  WEIGHT_SUCCESS.multiply(
                      normalize(quote.route().successRate(), minSuccess, maxSuccess)));
      scores.put(quote, score);
    }
    return Comparator.<RouteQuote, BigDecimal>comparing(scores::get, Comparator.reverseOrder())
        .thenComparing(q -> q.route().code());
  }

  private BigDecimal normalize(BigDecimal value, BigDecimal min, BigDecimal max) {
    if (max.compareTo(min) == 0) {
      return BigDecimal.ONE;
    }
    return value.subtract(min).divide(max.subtract(min), 12, RoundingMode.HALF_EVEN);
  }
}
