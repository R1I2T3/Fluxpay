package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class QuoteServiceTest extends DbPaymentEligibilityGateFixture {
  @Test
  void currentQuoteStaysFrozenAfterRouteEditsWithOnlyOneActiveRoute() {
    UUID user = UUID.randomUUID();
    TransferProvider provider =
        TransferProvider.create(
            UUID.randomUUID(), "TEST_BANK", "Test Bank", RailType.BANK_NETWORK, true, false, NOW);
    Payment payment = payment(user, RoutePreference.CHEAPEST, provider);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    TransferRouteRepository routes = mock(TransferRouteRepository.class);
    TransferRoute route =
        QuoteEntryPointsTest.external(
            "STANDARD_BANK", "KE", "KES", "5", "0", 240, "99.5", provider);
    when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of(route));
    when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
    when(quotes.saveAll(any()))
        .thenAnswer(
            i -> {
              List<PaymentQuote> saved = i.getArgument(0);
              when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(
                      payment.id(), saved.get(0).generation()))
                  .thenReturn(saved);
              return saved;
            });
    var service = service(payments, quotes, routes, provider);
    var first = service.createOrCurrent(user, payment.id(), "quote-key");
    route.update("20", "5", 5, "90", true);
    var second = service.createOrCurrent(user, payment.id(), "quote-key");
    assertThat(second.quotes()).isEqualTo(first.quotes());
    assertThat(second.quotes().get(0).recipientAmount()).isEqualTo("7600.0000");
  }

  @Test
  void routeCodeNeverChangesConfiguredFee() {
    RoutePricingService pricing =
        new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy());
    TransferRoute any = route("ANY_DYNAMIC_CODE", "5", "0", "7000", "9000");
    RouteQuote quote =
        pricing
            .price(
                new BigDecimal("100"),
                new BigDecimal("80"),
                List.of(any),
                Map.of(any.getId(), new BigDecimal("99")))
            .get(0);
    assertThat(quote.feeAmount()).isEqualByComparingTo("5.0000");
    assertThat(quote.recipientAmount()).isEqualByComparingTo("7600.0000");
    assertThat(quote.effectiveReliability()).isEqualByComparingTo("99");
  }

  @Test
  void invalidAndOutOfLimitCandidatesAreSkippedWithoutFailingValidCandidates() {
    RoutePricingService pricing =
        new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy());
    TransferRoute broke = route("BROKE_ROUTE", "500", "0", null, null);
    TransferRoute capped = route("CAPPED_ROUTE", "5", "0", "8000", null);
    TransferRoute valid = route("VALID_ROUTE", "5", "0", null, null);

    List<RouteQuote> priced =
        pricing.price(
            new BigDecimal("100"), new BigDecimal("80"), List.of(broke, capped, valid), Map.of());

    assertThat(priced).extracting(q -> q.route().code()).containsExactly("VALID_ROUTE");
    assertThat(priced.get(0).effectiveReliability())
        .isEqualByComparingTo(valid.configuredSuccessRate());
  }

  @Test
  void emptyPipelineThrowsNoEligibleRoutes() {
    RoutePricingService pricing =
        new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy());

    assertThatThrownBy(
            () -> pricing.price(new BigDecimal("100"), new BigDecimal("80"), List.of(), Map.of()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("NO_ELIGIBLE_ROUTES");
              assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            });
  }

  private static TransferRoute route(
      String code, String baseFee, String spread, String minimum, String maximum) {
    TransferProvider provider =
        TransferProvider.create(
            UUID.randomUUID(), "TEST_BANK", "Test Bank", RailType.BANK_NETWORK, true, false, NOW);
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        code,
        code + " name",
        DestinationType.EXTERNAL_ACCOUNT,
        "KE",
        "KES",
        new BigDecimal(baseFee),
        new BigDecimal(spread),
        60,
        new BigDecimal("99.00"),
        minimum == null ? null : new BigDecimal(minimum),
        maximum == null ? null : new BigDecimal(maximum),
        true,
        false,
        NOW);
  }

  @Test
  void instantRouteUsesBaseFeeWithoutSurcharge() {
    var instant =
        TransferRoute.seed(
            UUID.randomUUID(),
            "INSTANT_PAYOUT",
            "Instant",
            "Instant",
            "INSTANT",
            "8.50",
            "0",
            5,
            "98");
    var result =
        new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy())
            .price(new BigDecimal("100"), new BigDecimal("80"), List.of(instant))
            .get(0);
    assertThat(result.feeAmount()).isEqualByComparingTo("8.5000");
    assertThat(result.recipientAmount()).isEqualByComparingTo("7320.0000");
  }

  @Test
  void quoteUsesActiveProviderIdentityAndSourceCurrencyFee() {
    UUID user = UUID.randomUUID();
    TransferProvider provider =
        TransferProvider.create(
            UUID.randomUUID(), "TEST_BANK", "Test Bank", RailType.BANK_NETWORK, true, false, NOW);
    Payment payment = payment(user, RoutePreference.CHEAPEST, provider);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
    when(quotes.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    TransferRouteRepository routes = mock(TransferRouteRepository.class);
    when(routes.findAllByOrderByRouteCodeAsc())
        .thenReturn(
            List.of(
                QuoteEntryPointsTest.external(
                    "STANDARD_BANK", "KE", "KES", "5", "0", 240, "99.5", provider)));
    var result =
        service(payments, quotes, routes, provider)
            .createOrCurrent(user, payment.id(), "quote-key");
    assertThat(result.quotes()).extracting(q -> q.routeCode()).containsExactly("STANDARD_BANK");
    assertThat(result.quotes().get(0).recipientAmount()).isEqualTo("7600.0000");
    assertThat(result.quotes().get(0).routeId())
        .isEqualTo(routes.findAllByOrderByRouteCodeAsc().get(0).getId());
    assertThat(result.quotes().get(0).providerId()).isEqualTo(provider.getId());
    assertThat(result.quotes().get(0).rankingPosition()).isEqualTo(1);
  }

  private static Payment payment(UUID user, RoutePreference preference, TransferProvider provider) {
    return new Payment(
        UUID.randomUUID(),
        user,
        UUID.randomUUID(),
        recipient(user),
        new BigDecimal("100.0000"),
        "USD",
        "KES",
        PaymentPurpose.FAMILY_SUPPORT,
        preference,
        QuoteEntryPointsTest.snapshot("KE", "KES"),
        NOW);
  }

  private static QuoteService service(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      TransferRouteRepository routes,
      TransferProvider provider) {
    TransferRouteOutcomeRepository outcomes = mock(TransferRouteOutcomeRepository.class);
    when(outcomes.countByRouteIds(any())).thenReturn(List.of());
    SmartRoutingService smart =
        new SmartRoutingService(
            routes,
            new RouteEligibilityService(new RailRegistry(List.of(QuoteEntryPointsTest.fakeRail()))),
            new RouteReliabilityService(outcomes),
            new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy()),
            new RouteRecommender());
    FxRateProvider fx = (source, target) -> new BigDecimal("80.000000");
    return new QuoteService(
        payments,
        quotes,
        fx,
        Clock.fixed(NOW, ZoneOffset.UTC),
        smart,
        new PaymentOperationService(
            mock(PaymentOperationRepository.class),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            mock(org.springframework.transaction.PlatformTransactionManager.class)),
        mock(PaymentRecoveryEligibility.class));
  }
}
