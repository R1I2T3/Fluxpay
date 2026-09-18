package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RankedRouteQuote;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.dto.RouteRecommendation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteRecommenderTest {

  private static final UUID HDFC_ID =
      UUID.nameUUIDFromBytes("fluxpay:provider:HDFC".getBytes(StandardCharsets.UTF_8));
  private static final UUID SBI_ID =
      UUID.nameUUIDFromBytes("fluxpay:provider:SBI".getBytes(StandardCharsets.UTF_8));
  private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

  private RouteRecommender recommender;

  @BeforeEach
  void setUp() {
    recommender = new RouteRecommender();
  }

  private RouteQuote quote(
      String code, UUID providerId, String recipient, int eta, String reliability) {
    String providerCode = providerId.equals(HDFC_ID) ? "HDFC" : "SBI";
    TransferProvider provider =
        TransferProvider.create(
            providerId,
            providerCode,
            providerCode + " Bank",
            RailType.BANK_NETWORK,
            true,
            false,
            FIXED);
    TransferRoute route =
        TransferRoute.create(
            UUID.nameUUIDFromBytes(("fluxpay:route:" + code).getBytes(StandardCharsets.UTF_8)),
            provider,
            code,
            code + " Rail",
            DestinationType.EXTERNAL_ACCOUNT,
            "IN",
            "INR",
            new BigDecimal("5.00"),
            new BigDecimal("0.80"),
            eta,
            new BigDecimal(reliability),
            null,
            null,
            true,
            false,
            FIXED);
    return new RouteQuote(
        route,
        new BigDecimal("80.000000"),
        new BigDecimal("79.360000"),
        new BigDecimal(recipient),
        new BigDecimal("5.0000"),
        new BigDecimal("95.0000"),
        new BigDecimal(reliability));
  }

  @Test
  void sameProviderMayOwnAllThreeWinners() {
    List<RouteQuote> candidates =
        List.of(
            quote("HDFC_A", HDFC_ID, "100", 30, "99"),
            quote("HDFC_B", HDFC_ID, "99", 20, "98"),
            quote("HDFC_C", HDFC_ID, "98", 10, "97"),
            quote("SBI_A", SBI_ID, "97", 5, "96"));
    RouteRecommendation result = recommender.recommend(RoutePreference.CHEAPEST, candidates);
    assertThat(result.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("HDFC_A", "HDFC_B", "HDFC_C");
  }

  @Test
  void cheapestSortsByRecipientDescending() {
    List<RouteQuote> candidates =
        List.of(
            quote("LOW", HDFC_ID, "100", 5, "99"),
            quote("HIGH", SBI_ID, "300", 60, "90"),
            quote("MID", HDFC_ID, "200", 30, "95"));
    RouteRecommendation result = recommender.recommend(RoutePreference.CHEAPEST, candidates);
    assertThat(result.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("HIGH", "MID", "LOW");
    assertThat(result.recommended()).isEqualTo(result.quotes().get(0));
    assertThat(result.quotes())
        .extracting(RankedRouteQuote::score)
        .containsExactly(new BigDecimal("300"), new BigDecimal("200"), new BigDecimal("100"));
  }

  @Test
  void cheapestTiesUseReliabilityThenEtaThenCode() {
    RouteQuote lowReliability = quote("TIE_A", HDFC_ID, "100", 10, "90");
    RouteQuote highReliability = quote("TIE_B", SBI_ID, "100", 60, "99");
    RouteRecommendation byReliability =
        recommender.recommend(RoutePreference.CHEAPEST, List.of(lowReliability, highReliability));
    assertThat(byReliability.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("TIE_B", "TIE_A");

    RouteQuote slow = quote("TIE_C", HDFC_ID, "100", 60, "99");
    RouteQuote fast = quote("TIE_D", SBI_ID, "100", 10, "99");
    RouteRecommendation byEta =
        recommender.recommend(RoutePreference.CHEAPEST, List.of(slow, fast));
    assertThat(byEta.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("TIE_D", "TIE_C");
  }

  @Test
  void fastestSortsByEtaAscending() {
    List<RouteQuote> candidates =
        List.of(
            quote("SLOW", HDFC_ID, "300", 60, "90"),
            quote("FAST", SBI_ID, "100", 5, "90"),
            quote("MID", HDFC_ID, "200", 30, "90"));
    RouteRecommendation result = recommender.recommend(RoutePreference.FASTEST, candidates);
    assertThat(result.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("FAST", "MID", "SLOW");
    assertThat(result.recommended()).isEqualTo(result.quotes().get(0));
    assertThat(result.quotes())
        .extracting(RankedRouteQuote::score)
        .containsExactly(inverseEta(5), inverseEta(30), inverseEta(60));
  }

  @Test
  void fastestTiesUseRecipientThenReliabilityThenCode() {
    RouteQuote lowPayout = quote("TIE_A", HDFC_ID, "100", 10, "99");
    RouteQuote highPayout = quote("TIE_B", SBI_ID, "200", 10, "90");
    RouteRecommendation byRecipient =
        recommender.recommend(RoutePreference.FASTEST, List.of(lowPayout, highPayout));
    assertThat(byRecipient.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("TIE_B", "TIE_A");

    RouteQuote lowReliability = quote("TIE_C", HDFC_ID, "100", 10, "90");
    RouteQuote highReliability = quote("TIE_D", SBI_ID, "100", 10, "99");
    RouteRecommendation byReliability =
        recommender.recommend(RoutePreference.FASTEST, List.of(lowReliability, highReliability));
    assertThat(byReliability.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("TIE_D", "TIE_C");
  }

  @Test
  void balancedApplies45_30_25Weights() {
    // A wins FASTEST (ETA 10), B wins CHEAPEST (recipient 200); the weighted blend must pick C.
    List<RouteQuote> candidates =
        List.of(
            quote("BAL_A", HDFC_ID, "100", 10, "90"),
            quote("BAL_B", SBI_ID, "200", 100, "80"),
            quote("BAL_C", HDFC_ID, "150", 50, "99"));
    RouteRecommendation result = recommender.recommend(RoutePreference.BALANCED, candidates);
    assertThat(result.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("BAL_C", "BAL_B", "BAL_A");
    assertThat(result.recommended()).isEqualTo(result.quotes().get(0));
    for (RankedRouteQuote ranked : result.quotes()) {
      assertThat(
              ranked.score().compareTo(BigDecimal.ZERO) >= 0
                  && ranked.score().compareTo(BigDecimal.ONE) <= 0)
          .as("balanced score within [0, 1] but was %s", ranked.score())
          .isTrue();
    }
  }

  @Test
  void balancedDegenerateDimensionContributesFullWeightEqually() {
    RouteRecommendation single =
        recommender.recommend(
            RoutePreference.BALANCED, List.of(quote("ONLY", HDFC_ID, "100", 10, "99")));
    assertThat(single.quotes()).hasSize(1);
    assertThat(single.quotes().get(0).score()).isEqualByComparingTo("1");

    // Identical recipient amounts: both earn the full 0.45 recipient weight, so the faster,
    // less reliable quote still wins 0.75 to 0.70.
    List<RouteQuote> candidates =
        List.of(quote("DEG_X", HDFC_ID, "100", 10, "80"), quote("DEG_Y", SBI_ID, "100", 100, "90"));
    RouteRecommendation result = recommender.recommend(RoutePreference.BALANCED, candidates);
    assertThat(result.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("DEG_X", "DEG_Y");
    assertThat(result.quotes().get(0).score()).isEqualByComparingTo("0.75");
    assertThat(result.quotes().get(1).score()).isEqualByComparingTo("0.70");
  }

  @Test
  void capsAtThreeAcrossFiveCandidates() {
    List<RouteQuote> candidates =
        List.of(
            quote("CAP_1", HDFC_ID, "100", 50, "90"),
            quote("CAP_2", HDFC_ID, "200", 40, "91"),
            quote("CAP_3", SBI_ID, "300", 30, "92"),
            quote("CAP_4", SBI_ID, "400", 20, "93"),
            quote("CAP_5", HDFC_ID, "500", 10, "94"));
    RouteRecommendation result = recommender.recommend(RoutePreference.CHEAPEST, candidates);
    assertThat(result.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("CAP_5", "CAP_4", "CAP_3");
    assertThat(result.quotes()).extracting(RankedRouteQuote::position).containsExactly(1, 2, 3);
    assertThat(result.recommended()).isEqualTo(result.quotes().get(0));
  }

  @Test
  void oneOrTwoCandidatesKeepPositions() {
    RouteRecommendation single =
        recommender.recommend(
            RoutePreference.FASTEST, List.of(quote("ONLY", HDFC_ID, "100", 10, "99")));
    assertThat(single.quotes()).extracting(RankedRouteQuote::position).containsExactly(1);
    assertThat(single.recommended()).isEqualTo(single.quotes().get(0));

    RouteRecommendation pair =
        recommender.recommend(
            RoutePreference.FASTEST,
            List.of(
                quote("SLOW", HDFC_ID, "100", 60, "99"), quote("FAST", SBI_ID, "100", 5, "99")));
    assertThat(pair.quotes())
        .extracting(q -> q.quote().route().code())
        .containsExactly("FAST", "SLOW");
    assertThat(pair.quotes()).extracting(RankedRouteQuote::position).containsExactly(1, 2);
    assertThat(pair.recommended()).isEqualTo(pair.quotes().get(0));
  }

  @Test
  void exactTiesUseRouteCodeRegardlessOfInputOrder() {
    for (RoutePreference preference : RoutePreference.values()) {
      RouteQuote zulu = quote("Z_BANK", HDFC_ID, "100", 10, "99");
      RouteQuote alpha = quote("A_BANK", SBI_ID, "100", 10, "99");
      assertThat(
              recommender
                  .recommend(preference, List.of(zulu, alpha))
                  .recommended()
                  .quote()
                  .route()
                  .code())
          .isEqualTo("A_BANK");
      assertThat(
              recommender
                  .recommend(preference, List.of(alpha, zulu))
                  .recommended()
                  .quote()
                  .route()
                  .code())
          .isEqualTo("A_BANK");
    }
  }

  @Test
  void reasonNamesWinnerFactsAndScore() {
    for (RoutePreference preference : RoutePreference.values()) {
      List<RouteQuote> candidates =
          List.of(
              quote("HDFC_A", HDFC_ID, "100", 30, "99"), quote("SBI_A", SBI_ID, "200", 5, "90"));
      RouteRecommendation result = recommender.recommend(preference, candidates);
      RankedRouteQuote winner = result.recommended();
      assertThat(result.reason())
          .contains(preference.name())
          .contains(winner.quote().route().code())
          .contains(winner.quote().recipientAmount().toPlainString())
          .contains(String.valueOf(winner.quote().route().estimatedMinutes()))
          .contains(winner.quote().effectiveReliability().toPlainString())
          .contains(winner.score().toPlainString());
    }
  }

  @Test
  void recommendationRejectsEmptyMismatchedOrOversizedRankings() {
    RouteQuote quote = quote("ONLY", HDFC_ID, "100", 10, "99");
    RankedRouteQuote first = new RankedRouteQuote(quote, new BigDecimal("100"), 1);
    assertThatThrownBy(() -> new RouteRecommendation(first, List.of(), "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    RankedRouteQuote second = new RankedRouteQuote(quote, new BigDecimal("99"), 2);
    RankedRouteQuote third = new RankedRouteQuote(quote, new BigDecimal("98"), 3);
    RankedRouteQuote fourth = new RankedRouteQuote(quote, new BigDecimal("97"), 4);
    assertThatThrownBy(
            () -> new RouteRecommendation(first, List.of(first, second, third, fourth), "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RouteRecommendation(second, List.of(first, second), "reason"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void emptyCandidatesThrow() {
    assertThatThrownBy(() -> recommender.recommend(RoutePreference.CHEAPEST, List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nullInputsThrow() {
    RouteQuote quote = quote("ONLY", HDFC_ID, "100", 10, "99");
    assertThatThrownBy(() -> recommender.recommend(null, List.of(quote)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> recommender.recommend(RoutePreference.CHEAPEST, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static BigDecimal inverseEta(int minutes) {
    return BigDecimal.ONE.divide(BigDecimal.valueOf(minutes), 12, RoundingMode.HALF_EVEN);
  }
}
