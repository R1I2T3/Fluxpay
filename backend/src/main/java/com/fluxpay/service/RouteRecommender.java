package com.fluxpay.service;

import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RankedRouteQuote;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.dto.RouteRecommendation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Deterministic preference ranking over priced candidates. The entire eligible set is sorted, the
 * first three become positioned quotes, and several winners may belong to the same provider. All
 * scores stay in {@link BigDecimal} with HALF_EVEN rounding.
 */
@Service
public class RouteRecommender {

  private static final BigDecimal WEIGHT_RECIPIENT = new BigDecimal("0.45");
  private static final BigDecimal WEIGHT_SPEED = new BigDecimal("0.30");
  private static final BigDecimal WEIGHT_SUCCESS = new BigDecimal("0.25");
  private static final int MAX_QUOTES = 3;

  public RouteRecommendation recommend(RoutePreference preference, List<RouteQuote> quotes) {
    Objects.requireNonNull(preference, "preference must not be null");
    Objects.requireNonNull(quotes, "quotes must not be null");
    if (quotes.isEmpty()) {
      throw new IllegalStateException("no active payout routes");
    }

    Map<RouteQuote, BigDecimal> scores = scores(preference, quotes);
    List<RouteQuote> ranked = new ArrayList<>(quotes);
    ranked.sort(comparator(preference, scores));
    List<RankedRouteQuote> winners = new ArrayList<>();
    for (RouteQuote quote : ranked) {
      if (winners.size() >= MAX_QUOTES) {
        break;
      }
      winners.add(new RankedRouteQuote(quote, scores.get(quote), winners.size() + 1));
    }
    List<RankedRouteQuote> top = List.copyOf(winners);
    return new RouteRecommendation(top.get(0), top, reason(preference, top, quotes.size()));
  }

  private Map<RouteQuote, BigDecimal> scores(RoutePreference preference, List<RouteQuote> quotes) {
    return switch (preference) {
      case CHEAPEST -> {
        Map<RouteQuote, BigDecimal> scores = new IdentityHashMap<>();
        for (RouteQuote quote : quotes) {
          scores.put(quote, quote.recipientAmount());
        }
        yield scores;
      }
      case FASTEST -> {
        Map<RouteQuote, BigDecimal> scores = new IdentityHashMap<>();
        for (RouteQuote quote : quotes) {
          scores.put(quote, speed(quote));
        }
        yield scores;
      }
      case BALANCED -> balancedScores(quotes);
    };
  }

  private Comparator<RouteQuote> comparator(
      RoutePreference preference, Map<RouteQuote, BigDecimal> scores) {
    return switch (preference) {
      case CHEAPEST ->
          Comparator.comparing(RouteQuote::recipientAmount, Comparator.reverseOrder())
              .thenComparing(RouteQuote::effectiveReliability, Comparator.reverseOrder())
              .thenComparingInt(q -> q.route().estimatedMinutes())
              .thenComparing(q -> q.route().code());
      case FASTEST ->
          Comparator.comparingInt((RouteQuote q) -> q.route().estimatedMinutes())
              .thenComparing(RouteQuote::recipientAmount, Comparator.reverseOrder())
              .thenComparing(RouteQuote::effectiveReliability, Comparator.reverseOrder())
              .thenComparing(q -> q.route().code());
      case BALANCED ->
          Comparator.<RouteQuote, BigDecimal>comparing(scores::get, Comparator.reverseOrder())
              .thenComparing(q -> q.route().code());
    };
  }

  private Map<RouteQuote, BigDecimal> balancedScores(List<RouteQuote> quotes) {
    BigDecimal minRecipient = null;
    BigDecimal maxRecipient = null;
    BigDecimal minSpeed = null;
    BigDecimal maxSpeed = null;
    BigDecimal minSuccess = null;
    BigDecimal maxSuccess = null;
    Map<RouteQuote, BigDecimal> speeds = new IdentityHashMap<>();
    for (RouteQuote quote : quotes) {
      BigDecimal recipient = quote.recipientAmount();
      BigDecimal speed = speed(quote);
      BigDecimal success = quote.effectiveReliability();
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
                      normalize(quote.effectiveReliability(), minSuccess, maxSuccess)));
      scores.put(quote, score);
    }
    return scores;
  }

  private BigDecimal speed(RouteQuote quote) {
    return BigDecimal.ONE.divide(
        BigDecimal.valueOf(quote.route().estimatedMinutes()), 12, RoundingMode.HALF_EVEN);
  }

  private BigDecimal normalize(BigDecimal value, BigDecimal min, BigDecimal max) {
    if (max.compareTo(min) == 0) {
      return BigDecimal.ONE;
    }
    return value.subtract(min).divide(max.subtract(min), 12, RoundingMode.HALF_EVEN);
  }

  private String reason(
      RoutePreference preference, List<RankedRouteQuote> winners, int candidates) {
    RankedRouteQuote winner = winners.get(0);
    RouteQuote quote = winner.quote();
    return String.format(
        Locale.ROOT,
        "%s selected %s with recipient %s, ETA %d min, reliability %s and score %s from %d"
            + " candidate(s); showing top %d.",
        preference,
        quote.route().code(),
        quote.recipientAmount().toPlainString(),
        quote.route().estimatedMinutes(),
        quote.effectiveReliability().toPlainString(),
        winner.score().toPlainString(),
        candidates,
        winners.size());
  }
}
