package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.*;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.QuoteResponse;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.TransferRoutingContext;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuoteEntryPointsTest extends DbPaymentEligibilityGateFixture {
  @Test
  void quoteAndRecommendationUseTheSameTopThree() {
    var f = new Fixture(RoutePreference.BALANCED);
    QuoteResponse created = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    RouteRecommendation ranked =
        f.smart.recommend(externalContext("IN", "INR"), RoutePreference.BALANCED);
    assertThat(created.quotes()).hasSize(3);
    assertThat(created.quotes())
        .extracting(QuoteResponse.Quote::routeCode)
        .containsExactlyElementsOf(
            ranked.quotes().stream().map(q -> q.quote().route().code()).toList());
  }

  @ParameterizedTest
  @CsvSource({"CHEAPEST,LOCAL_PARTNER", "FASTEST,INSTANT_PAYOUT", "BALANCED,STANDARD_BANK"})
  void bothEntryPointsUseTheSameHandRankedRoutes(RoutePreference preference, String winner) {
    var f = new Fixture(preference);
    var created = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    var catalog = f.catalog.recommend(f.payment.id().toString(), preference, "c");
    assertThat(catalog.recommended().quote().route().code()).isEqualTo(winner);
    assertThat(
            created.quotes().stream()
                .filter(q -> q.recommended())
                .findFirst()
                .orElseThrow()
                .routeCode())
        .isEqualTo(winner);
    Map<String, String> amounts =
        Map.of(
            "STANDARD_BANK",
            "7600.0000",
            "INSTANT_PAYOUT",
            "7320.0000",
            "LOCAL_PARTNER",
            "7840.0000");
    Map<String, String> fees =
        Map.of("STANDARD_BANK", "5.0000", "INSTANT_PAYOUT", "8.5000", "LOCAL_PARTNER", "2.0000");
    created
        .quotes()
        .forEach(
            q -> {
              assertThat(q.recipientAmount()).isEqualTo(amounts.get(q.routeCode()));
              assertThat(q.feeAmount()).isEqualTo(fees.get(q.routeCode()));
            });
    catalog
        .quotes()
        .forEach(
            q -> {
              assertThat(q.quote().recipientAmount())
                  .isEqualByComparingTo(amounts.get(q.quote().route().code()));
              assertThat(q.quote().feeAmount())
                  .isEqualByComparingTo(fees.get(q.quote().route().code()));
            });
  }

  @Test
  void corridorMismatchIsExcludedFromBothEntryPoints() {
    var f = new Fixture(RoutePreference.CHEAPEST);
    f.active.add(
        external(
            "KE_LURE",
            "KE",
            "KES",
            "0.5000",
            "0",
            1,
            "99.99",
            TransferProvider.create(
                UUID.randomUUID(),
                "LURE_BANK",
                "Lure Bank",
                RailType.BANK_NETWORK,
                true,
                false,
                NOW)));
    var created = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    var catalog = f.catalog.recommend(f.payment.id().toString(), RoutePreference.CHEAPEST, "c");
    assertThat(created.quotes())
        .extracting(QuoteResponse.Quote::routeCode)
        .doesNotContain("KE_LURE")
        .hasSize(3);
    assertThat(catalog.quotes())
        .extracting(q -> q.quote().route().code())
        .doesNotContain("KE_LURE");
    assertThat(created.quotes())
        .extracting(QuoteResponse.Quote::routeCode)
        .containsExactlyElementsOf(
            catalog.quotes().stream().map(q -> q.quote().route().code()).toList());
  }

  @Test
  void inactiveProviderIsExcludedFromBothEntryPoints() {
    var f = new Fixture(RoutePreference.FASTEST);
    TransferProvider idle =
        TransferProvider.create(
            UUID.randomUUID(), "IDLE_BANK", "Idle Bank", RailType.BANK_NETWORK, true, false, NOW);
    idle.update(idle.name(), idle.railType(), false, NOW);
    f.active.add(external("IDLE_LURE", "IN", "INR", "0.1000", "0", 1, "99.99", idle));
    var created = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    var catalog = f.catalog.recommend(f.payment.id().toString(), RoutePreference.FASTEST, "c");
    assertThat(created.quotes())
        .extracting(QuoteResponse.Quote::routeCode)
        .doesNotContain("IDLE_LURE")
        .hasSize(3);
    assertThat(catalog.quotes())
        .extracting(q -> q.quote().route().code())
        .doesNotContain("IDLE_LURE");
  }

  @Test
  void quotesStayFrozenAfterRouteEditsUntilExpiry() {
    var f = new Fixture(RoutePreference.BALANCED);
    var first = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    f.active.get(0).update("20", "5", 5, "90", true);
    var second =
        f.service(NOW.plusSeconds(60)).createOrCurrent(f.user, f.payment.id(), "quote-other-key");
    assertThat(second.quotes()).isEqualTo(first.quotes());
    assertThat(f.payment.currentQuoteGeneration()).isEqualTo(1);
  }

  @Test
  void noActiveRoutesFailsBothEntryPointsWithoutAdvancingGeneration() {
    var f = new Fixture(RoutePreference.BALANCED);
    f.active.clear();
    assertThatThrownBy(() -> f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("NO_ELIGIBLE_ROUTES"));
    assertThatThrownBy(() -> f.catalog.recommend(f.payment.id().toString(), null, "c"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("NO_ELIGIBLE_ROUTES"));
    assertThat(f.payment.currentQuoteGeneration()).isNull();
    assertThat(f.payment.nextQuoteGeneration()).isEqualTo(1);
  }

  @Test
  void nonpositiveNetOnEveryRouteFailsBothEntryPoints() {
    var f = new Fixture(RoutePreference.BALANCED);
    f.active.get(0).update("100", "0", 240, "99.5", true);
    f.active.get(1).update("100", "0", 5, "98", true);
    f.active.get(2).update("100", "0", 150, "96.5", true);
    assertThatThrownBy(() -> f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("NO_ELIGIBLE_ROUTES"));
    assertThatThrownBy(() -> f.catalog.recommend(f.payment.id().toString(), null, "c"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("NO_ELIGIBLE_ROUTES"));
  }

  @Test
  void expiredGenerationUsesNewRouteEconomicsAndSkipsDisabledRoutes() {
    var f = new Fixture(RoutePreference.BALANCED);
    var first = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    f.active.get(0).update("20", "5", 5, "90", true);
    f.active.get(1).update("8.5", "0", 5, "98", false);
    var second =
        f.service(NOW.plusSeconds(900))
            .createOrCurrent(f.user, f.payment.id(), "quote-next-generation");
    assertThat(f.payment.currentQuoteGeneration()).isEqualTo(2);
    assertThat(second.quotes())
        .extracting(QuoteResponse.Quote::routeCode)
        .containsExactlyInAnyOrder("STANDARD_BANK", "LOCAL_PARTNER");
    assertThat(
            second.quotes().stream()
                .filter(q -> q.routeCode().equals("STANDARD_BANK"))
                .findFirst()
                .orElseThrow()
                .recipientAmount())
        .isEqualTo("6080.0000");
    assertThat(
            first.quotes().stream()
                .filter(q -> q.routeCode().equals("STANDARD_BANK"))
                .findFirst()
                .orElseThrow()
                .recipientAmount())
        .isEqualTo("7600.0000");
  }

  @Test
  void sameKeyAfterExpiryReplaysStoredQuoteWithoutFetchingFx() {
    try (var db = new OperationDatabase()) {
      var f = new Fixture(RoutePreference.BALANCED);
      var operations =
          new PaymentOperationService(
              db.payments,
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
              Clock.systemUTC(),
              db.transactions);
      var initial =
          new QuoteService(
              f.payments,
              f.quotes,
              (s, t) -> new BigDecimal("80"),
              Clock.fixed(NOW, ZoneOffset.UTC),
              f.smart,
              operations,
              mock(PaymentRecoveryEligibility.class));
      var first = initial.createOrCurrent(f.user, f.payment.id(), "same-key");
      var later =
          new QuoteService(
              f.payments,
              f.quotes,
              (s, t) -> {
                throw new AssertionError("Stored quote replay must not fetch FX after expiry");
              },
              Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC),
              f.smart,
              operations,
              mock(PaymentRecoveryEligibility.class));
      assertThat(later.createOrCurrent(f.user, f.payment.id(), "same-key")).isEqualTo(first);
      assertThat(f.payment.currentQuoteGeneration()).isEqualTo(1);
      assertThat(db.payments.findAll()).hasSize(1);
    }
  }

  @Test
  void anotherOwnerCannotCreateQuotes() {
    var f = new Fixture(RoutePreference.CHEAPEST);
    assertThatThrownBy(
            () -> f.service(NOW).createOrCurrent(UUID.randomUUID(), f.payment.id(), "quote-key"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_NOT_FOUND"));
  }

  static TransferRoutingContext externalContext(String country, String currency) {
    return new TransferRoutingContext(
        DestinationType.EXTERNAL_ACCOUNT,
        country,
        currency,
        new BigDecimal("100.0000"),
        new BigDecimal("80"));
  }

  static TransferRoute external(
      String code,
      String country,
      String currency,
      String baseFee,
      String spread,
      int eta,
      String success,
      TransferProvider provider) {
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        code,
        code + " name",
        DestinationType.EXTERNAL_ACCOUNT,
        country,
        currency,
        new BigDecimal(baseFee),
        new BigDecimal(spread),
        eta,
        new BigDecimal(success),
        null,
        null,
        true,
        false,
        NOW);
  }

  static TransferRail fakeRail() {
    return new TransferRail() {
      @Override
      public RailType type() {
        return RailType.BANK_NETWORK;
      }

      @Override
      public Set<DestinationType> supportedDestinations() {
        return Set.of(DestinationType.EXTERNAL_ACCOUNT);
      }

      @Override
      public TransferRailResult execute(TransferRailCommand command) {
        throw new UnsupportedOperationException();
      }
    };
  }

  static String snapshot(String country, String currency) {
    return "{\"name\":\"A\",\"account\":\"acct\",\"bankName\":\"Bank\",\"country\":\""
        + country
        + "\",\"currency\":\""
        + currency
        + "\"}";
  }

  static class Fixture {
    final UUID user = UUID.randomUUID();
    final Payment payment;
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    final TransferRouteRepository routes = mock(TransferRouteRepository.class);
    final TransferRouteOutcomeRepository outcomes = mock(TransferRouteOutcomeRepository.class);
    final RoutePricingService pricing = new RoutePricingService(new QuotePricingPolicy());
    final RouteRecommender ranking = new RouteRecommender();
    final RouteEligibilityService eligibility =
        new RouteEligibilityService(new RailRegistry(List.of(fakeRail())));
    final RouteReliabilityService reliability = new RouteReliabilityService(outcomes);
    final SmartRoutingService smart;
    final List<TransferRoute> active;
    final RouteCatalogService catalog;

    Fixture(RoutePreference preference) {
      TransferProvider provider =
          TransferProvider.create(
              UUID.randomUUID(), "TEST_BANK", "Test Bank", RailType.BANK_NETWORK, true, false, NOW);
      active =
          new ArrayList<>(
              List.of(
                  external("STANDARD_BANK", "IN", "INR", "5", "0", 240, "99.5", provider),
                  external("INSTANT_PAYOUT", "IN", "INR", "8.5", "0", 5, "98", provider),
                  external("LOCAL_PARTNER", "IN", "INR", "2", "0", 150, "96.5", provider)));
      payment =
          new Payment(
              UUID.randomUUID(),
              user,
              UUID.randomUUID(),
              new Recipient(
                  UUID.randomUUID(),
                  user,
                  "A",
                  "acct",
                  "Bank",
                  "IN",
                  "INR",
                  RecipientStatus.ACTIVE,
                  NOW),
              new BigDecimal("100.0000"),
              "USD",
              "INR",
              PaymentPurpose.FAMILY_SUPPORT,
              preference,
              snapshot("IN", "INR"),
              NOW);
      smart = new SmartRoutingService(routes, eligibility, reliability, pricing, ranking);
      when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
      when(outcomes.countByRouteIds(any())).thenReturn(List.of());
      when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(active);
      when(routes.findByActiveTrueOrderByRouteCodeAsc()).thenReturn(active);
      when(quotes.saveAll(any()))
          .thenAnswer(
              call -> {
                List<PaymentQuote> saved = call.getArgument(0);
                when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(
                        payment.id(), saved.get(0).generation()))
                    .thenReturn(saved);
                return saved;
              });
      var reader = mock(PaymentReader.class);
      when(reader.get(payment.id().toString()))
          .thenReturn(
              new PaymentSnapshot(
                  payment.id().toString(),
                  user,
                  payment.sourceWalletId(),
                  UUID.randomUUID(),
                  payment.sourceAmount(),
                  "USD",
                  "INR",
                  PaymentStatus.PROCESSING,
                  null,
                  new ExternalAccountDestination("acct", "Bank", "IN", "INR")));
      catalog =
          new RouteCatalogService(
              reader, (s, t) -> new BigDecimal("80"), routes, reliability, smart);
    }

    QuoteService service(Instant time) {
      return new QuoteService(
          payments,
          quotes,
          (s, t) -> new BigDecimal("80"),
          Clock.fixed(time, ZoneOffset.UTC),
          smart,
          new PaymentOperationService(
              mock(PaymentOperationRepository.class),
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
              Clock.systemUTC(),
              mock(org.springframework.transaction.PlatformTransactionManager.class)),
          mock(PaymentRecoveryEligibility.class));
    }
  }
}
