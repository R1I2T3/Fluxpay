package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QuoteServiceTest extends DbPaymentEligibilityGateFixture {
  @Test
  void currentQuoteStaysFrozenAfterRouteEditsWithOnlyOneActiveRoute() {
    UUID user = UUID.randomUUID();
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            user,
            UUID.randomUUID(),
            recipient(user),
            new BigDecimal("100.0000"),
            "USD",
            "KES",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.CHEAPEST,
            "{}",
            NOW);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    PayoutRouteRepository routes = mock(PayoutRouteRepository.class);
    PayoutRoute route =
        PayoutRoute.seed(
            UUID.randomUUID(), "STANDARD_BANK", "Bank", "Bank", "STANDARD", "5", "0", 240, "99.5");
    when(routes.findByActiveTrueOrderByRouteCodeAsc()).thenReturn(List.of(route));
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
    var service =
        new QuoteService(
            payments,
            quotes,
            (s, t) -> new BigDecimal("80"),
            Clock.fixed(NOW, ZoneOffset.UTC),
            routes,
            new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy()),
            new RouteRecommender(),
            new PaymentOperationService(
                mock(PaymentOperationRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC(),
                mock(org.springframework.transaction.PlatformTransactionManager.class)),
            mock(PaymentRecoveryEligibility.class));
    var first = service.createOrCurrent(user, payment.id(), "quote-key");
    route.update("20", "5", 5, "90", true);
    var second = service.createOrCurrent(user, payment.id(), "quote-key");
    assertThat(second.quotes()).isEqualTo(first.quotes());
    assertThat(second.quotes().get(0).recipientAmount()).isEqualTo("7600.0000");
  }

  @Test
  void instantSurchargeIsIncludedBeforeConversion() {
    var instant =
        PayoutRoute.seed(
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
    assertThat(result.feeAmount()).isEqualByComparingTo("11.0000");
    assertThat(result.recipientAmount()).isEqualByComparingTo("7120.0000");
  }

  @Test
  void quoteUsesActiveProviderIdentityAndSourceCurrencyFee() {
    UUID user = UUID.randomUUID();
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            user,
            UUID.randomUUID(),
            recipient(user),
            new BigDecimal("100.0000"),
            "USD",
            "KES",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.CHEAPEST,
            "{}",
            NOW);
    PaymentRepository payments = mock(PaymentRepository.class);
    PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    FxRateProvider fx = (source, target) -> new BigDecimal("80.000000");
    when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
    when(quotes.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    PayoutRouteRepository routes = mock(PayoutRouteRepository.class);
    when(routes.findByActiveTrueOrderByRouteCodeAsc())
        .thenReturn(
            List.of(
                PayoutRoute.seed(
                    UUID.randomUUID(),
                    "STANDARD_BANK",
                    "Bank",
                    "Bank",
                    "STANDARD",
                    "5",
                    "0",
                    240,
                    "99.5")));
    var result =
        new QuoteService(
                payments,
                quotes,
                fx,
                Clock.fixed(NOW, ZoneOffset.UTC),
                routes,
                new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy()),
                new RouteRecommender(),
                new PaymentOperationService(
                    mock(PaymentOperationRepository.class),
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                    Clock.systemUTC(),
                    mock(org.springframework.transaction.PlatformTransactionManager.class)),
                mock(PaymentRecoveryEligibility.class))
            .createOrCurrent(user, payment.id(), "quote-key");
    assertThat(result.quotes()).extracting(q -> q.route()).containsExactly("STANDARD_BANK");
    assertThat(result.quotes().get(0).recipientAmount()).isEqualTo("7600.0000");
  }
}
