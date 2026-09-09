package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.dto.RoutePreference;
import com.fluxpay.dto.RouteQuote;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteRecommenderTest {
  private RouteRecommender recommender;
  private List<PayoutRoute> routes;

  @BeforeEach
  void setUp() {
    recommender = new RouteRecommender();
    routes =
        List.of(
            PayoutRoute.seed(
                "r-standard",
                "STANDARD_BANK",
                "Standard Bank Rail",
                "Standard Bank",
                "STANDARD",
                "5.00",
                "0.8",
                240,
                "99.50"),
            PayoutRoute.seed(
                "r-instant",
                "INSTANT_PAYOUT",
                "Instant Payout",
                "Instant Payout Co",
                "INSTANT",
                "8.50",
                "2.0",
                5,
                "98.00"),
            PayoutRoute.seed(
                "r-local",
                "LOCAL_PARTNER",
                "Local Partner",
                "Local Partner Ltd",
                "LOCAL_PARTNER",
                "2.00",
                "3.5",
                150,
                "96.50"));
  }

  @Test
  void cheapestAtOneHundredIsLocalPartner() {
    assertThat(
            recommender
                .recommend(
                    new BigDecimal("100.00"), RoutePreference.CHEAPEST, BigDecimal.ONE, routes)
                .recommended()
                .code())
        .isEqualTo("LOCAL_PARTNER");
  }

  @Test
  void fastestIsInstantPayout() {
    assertThat(
            recommender
                .recommend(
                    new BigDecimal("1000.00"), RoutePreference.FASTEST, BigDecimal.ONE, routes)
                .recommended()
                .code())
        .isEqualTo("INSTANT_PAYOUT");
  }

  @Test
  void balancedAtOneThousandIsStandardBank() {
    assertThat(
            recommender
                .recommend(
                    new BigDecimal("1000.00"), RoutePreference.BALANCED, BigDecimal.ONE, routes)
                .recommended()
                .code())
        .isEqualTo("STANDARD_BANK");
  }

  @Test
  void inactiveRoutesAreNeverEvaluated() {
    routes.get(0).update("5.00", "0.8", 240, "99.50", false);
    assertThat(
            recommender
                .recommend(
                    new BigDecimal("1000.00"), RoutePreference.BALANCED, BigDecimal.ONE, routes)
                .quotes())
        .noneMatch(q -> q.route().code().equals("STANDARD_BANK"));
  }

  @Test
  void quoteExposesMarketRateAndOfferedRate() {
    RouteQuote quote =
        recommender
            .recommend(
                new BigDecimal("1000.00"),
                RoutePreference.CHEAPEST,
                new BigDecimal("148.0000"),
                routes)
            .quotes()
            .stream()
            .filter(q -> q.route().code().equals("STANDARD_BANK"))
            .findFirst()
            .orElseThrow();
    assertThat(quote.marketRate()).isEqualByComparingTo("148.0000");
    assertThat(quote.offeredRate()).isEqualByComparingTo("146.8160");
  }
}
