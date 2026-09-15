package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteQuote;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
                UUID.nameUUIDFromBytes(
                    "fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8)),
                "STANDARD_BANK",
                "Standard Bank Rail",
                "Standard Bank",
                "STANDARD",
                "5.00",
                "0.8",
                240,
                "99.50"),
            PayoutRoute.seed(
                UUID.nameUUIDFromBytes(
                    "fluxpay:route:INSTANT_PAYOUT".getBytes(StandardCharsets.UTF_8)),
                "INSTANT_PAYOUT",
                "Instant Payout",
                "Instant Payout Co",
                "INSTANT",
                "8.50",
                "2.0",
                5,
                "98.00"),
            PayoutRoute.seed(
                UUID.nameUUIDFromBytes(
                    "fluxpay:route:LOCAL_PARTNER".getBytes(StandardCharsets.UTF_8)),
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
            recommend(new BigDecimal("100.00"), RoutePreference.CHEAPEST, BigDecimal.ONE, routes)
                .recommended()
                .code())
        .isEqualTo("LOCAL_PARTNER");
  }

  @Test
  void fastestIsInstantPayout() {
    assertThat(
            recommend(new BigDecimal("1000.00"), RoutePreference.FASTEST, BigDecimal.ONE, routes)
                .recommended()
                .code())
        .isEqualTo("INSTANT_PAYOUT");
  }

  @Test
  void balancedAtOneThousandIsStandardBank() {
    assertThat(
            recommend(new BigDecimal("1000.00"), RoutePreference.BALANCED, BigDecimal.ONE, routes)
                .recommended()
                .code())
        .isEqualTo("STANDARD_BANK");
  }

  @Test
  void inactiveRoutesAreNeverEvaluated() {
    routes.get(0).update("5.00", "0.8", 240, "99.50", false);
    assertThat(
            recommend(new BigDecimal("1000.00"), RoutePreference.BALANCED, BigDecimal.ONE, routes)
                .quotes())
        .noneMatch(q -> q.route().code().equals("STANDARD_BANK"));
  }

  @Test
  void quoteExposesMarketRateAndOfferedRate() {
    RouteQuote quote =
        recommend(
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
    assertThat(quote.recipientAmount()).isEqualByComparingTo("146081.9200");
  }

  private com.fluxpay.dto.RouteRecommendation recommend(
      BigDecimal amount, RoutePreference preference, BigDecimal rate, List<PayoutRoute> active) {
    return recommender.recommend(
        preference,
        new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy())
            .price(amount, rate, active));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(RoutePreference.class)
  void exactTiesUseRouteCodeRegardlessOfInputOrder(RoutePreference preference) {
    var z = PayoutRoute.seed(UUID.randomUUID(), "Z_BANK", "Z", "Z", "STANDARD", "5", "0", 10, "99");
    var a = PayoutRoute.seed(UUID.randomUUID(), "A_BANK", "A", "A", "STANDARD", "5", "0", 10, "99");
    assertThat(
            recommend(new BigDecimal("100"), preference, new BigDecimal("80"), List.of(z, a))
                .recommended()
                .code())
        .isEqualTo("A_BANK");
    assertThat(
            recommend(new BigDecimal("100"), preference, new BigDecimal("80"), List.of(a, z))
                .recommended()
                .code())
        .isEqualTo("A_BANK");
  }
}
