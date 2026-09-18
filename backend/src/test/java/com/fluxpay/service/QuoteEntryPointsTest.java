package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.*;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuoteEntryPointsTest extends DbPaymentEligibilityGateFixture {
  @ParameterizedTest
  @CsvSource({"CHEAPEST,LOCAL_PARTNER", "FASTEST,INSTANT_PAYOUT", "BALANCED,STANDARD_BANK"})
  void bothEntryPointsUseTheSameHandRankedRoutes(RoutePreference preference, String winner) {
    var f = new Fixture(preference);
    var created = f.service(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    var catalog = f.catalog.recommend(f.payment.id().toString(), preference, "c");
    assertThat(catalog.recommended().code()).isEqualTo(winner);
    assertThat(
            created.quotes().stream()
                .filter(q -> q.recommended())
                .findFirst()
                .orElseThrow()
                .route())
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
              assertThat(q.recipientAmount()).isEqualTo(amounts.get(q.route()));
              assertThat(q.feeAmount()).isEqualTo(fees.get(q.route()));
            });
    catalog
        .quotes()
        .forEach(
            q -> {
              assertThat(q.recipientAmount()).isEqualByComparingTo(amounts.get(q.route().code()));
              assertThat(q.feeAmount()).isEqualByComparingTo(fees.get(q.route().code()));
            });
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
        .extracting(q -> q.route())
        .containsExactlyInAnyOrder("STANDARD_BANK", "LOCAL_PARTNER");
    assertThat(
            second.quotes().stream()
                .filter(q -> q.route().equals("STANDARD_BANK"))
                .findFirst()
                .orElseThrow()
                .recipientAmount())
        .isEqualTo("6080.0000");
    assertThat(
            first.quotes().stream()
                .filter(q -> q.route().equals("STANDARD_BANK"))
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
              f.routes,
              f.pricing,
              f.ranking,
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
              f.routes,
              f.pricing,
              f.ranking,
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

  static class Fixture {
    final UUID user = UUID.randomUUID();
    final Payment payment;
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    final TransferRouteRepository routes = mock(TransferRouteRepository.class);
    final RoutePricingService pricing = new RoutePricingService(new QuotePricingPolicy());
    final RouteRecommender ranking = new RouteRecommender();
    final List<TransferRoute> active =
        new ArrayList<>(
            List.of(
                TransferRoute.seed(
                    UUID.randomUUID(),
                    "STANDARD_BANK",
                    "Bank",
                    "Bank",
                    "STANDARD",
                    "5",
                    "0",
                    240,
                    "99.5"),
                TransferRoute.seed(
                    UUID.randomUUID(),
                    "INSTANT_PAYOUT",
                    "Instant",
                    "Instant",
                    "INSTANT",
                    "8.5",
                    "0",
                    5,
                    "98"),
                TransferRoute.seed(
                    UUID.randomUUID(),
                    "LOCAL_PARTNER",
                    "Local",
                    "Local",
                    "LOCAL",
                    "2",
                    "0",
                    150,
                    "96.5")));
    final RouteCatalogService catalog;

    Fixture(RoutePreference preference) {
      payment =
          new Payment(
              UUID.randomUUID(),
              user,
              UUID.randomUUID(),
              recipient(user),
              new BigDecimal("100.0000"),
              "USD",
              "KES",
              PaymentPurpose.FAMILY_SUPPORT,
              preference,
              "{}",
              NOW);
      when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
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
                  "KES",
                  PaymentStatus.PROCESSING));
      catalog =
          new RouteCatalogService(
              reader,
              (s, t) -> new BigDecimal("80"),
              ranking,
              routes,
              mock(RouteReliabilityService.class),
              pricing);
    }

    QuoteService service(Instant time) {
      return new QuoteService(
          payments,
          quotes,
          (s, t) -> new BigDecimal("80"),
          Clock.fixed(time, ZoneOffset.UTC),
          routes,
          pricing,
          ranking,
          new PaymentOperationService(
              mock(PaymentOperationRepository.class),
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
              Clock.systemUTC(),
              mock(org.springframework.transaction.PlatformTransactionManager.class)),
          mock(PaymentRecoveryEligibility.class));
    }
  }
}
