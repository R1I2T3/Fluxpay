package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.ExternalAccountDestination;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class RouteCatalogServiceTest {

  @Mock private PaymentReader reader;
  @Mock private FxRateProvider fx;
  @Mock private TransferRouteRepository routes;
  @Mock private TransferRouteOutcomeRepository outcomes;

  private RouteReliabilityService reliability;
  private RouteCatalogService service;
  private PaymentSnapshot payment;
  private TransferRoute standard;
  private TransferRoute instant;

  @BeforeEach
  void setUp() {
    reliability = new RouteReliabilityService(outcomes);
    SmartRoutingService smart =
        new SmartRoutingService(
            routes,
            new RouteEligibilityService(new RailRegistry(List.of(QuoteEntryPointsTest.fakeRail()))),
            reliability,
            new RoutePricingService(new com.fluxpay.domain.QuotePricingPolicy()),
            new RouteRecommender());
    service = new RouteCatalogService(reader, fx, routes, reliability, smart);
    payment =
        new PaymentSnapshot(
            "P-001",
            UUID.nameUUIDFromBytes("fluxpay:P-001:user".getBytes()),
            UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes()),
            UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes()),
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentStatus.PROCESSING,
            null,
            new ExternalAccountDestination("acct", "Bank", "KE", "KES"));
    TransferProvider provider =
        TransferProvider.create(
            UUID.nameUUIDFromBytes("fluxpay:provider:test".getBytes(StandardCharsets.UTF_8)),
            "TEST_BANK",
            "Test Bank",
            RailType.BANK_NETWORK,
            true,
            false,
            DbPaymentEligibilityGateFixture.NOW);
    standard =
        QuoteEntryPointsTest.external(
            "STANDARD_BANK", "KE", "KES", "5.00", "0.8", 240, "99.50", provider);
    instant =
        QuoteEntryPointsTest.external(
            "INSTANT_PAYOUT", "KE", "KES", "8.50", "2.0", 5, "98.00", provider);
  }

  @Test
  void sourceCurrencyFeeIsDeductedBeforeConversion() {
    PaymentSnapshot hundred =
        new PaymentSnapshot(
            "P-001",
            payment.senderUserId(),
            payment.senderWalletId(),
            payment.payoutClearingWalletId(),
            new BigDecimal("100.0000"),
            "USD",
            "KES",
            PaymentStatus.PROCESSING,
            null,
            new ExternalAccountDestination("acct", "Bank", "KE", "KES"));
    standard.update("5.0000", "0", 240, "99.50", true);
    when(reader.get("P-001")).thenReturn(hundred);
    when(fx.rate("USD", "KES")).thenReturn(new BigDecimal("80.000000"));
    when(outcomes.countByRouteIds(any())).thenReturn(List.of());
    when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of(standard));
    assertThat(
            service
                .recommend("P-001", RoutePreference.CHEAPEST, "c")
                .quotes()
                .get(0)
                .quote()
                .recipientAmount())
        .isEqualByComparingTo("7600.0000");
  }

  @Test
  void recommendationFetchesMarketRateFromFxProviderAndReturnsIt() {
    when(reader.get("P-001")).thenReturn(payment);
    when(fx.rate("USD", "KES")).thenReturn(new BigDecimal("148.0000"));
    when(outcomes.countByRouteIds(any())).thenReturn(List.of());
    when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of(instant, standard));

    RouteRecommendation recommendation =
        service.recommend("P-001", RoutePreference.BALANCED, "c-uuid");

    verify(fx).rate("USD", "KES");
    assertThat(recommendation.quotes()).hasSize(2);
    assertThat(recommendation.quotes().get(0).quote().marketRate())
        .isEqualByComparingTo("148.0000");
    assertThat(recommendation.quotes())
        .allMatch(q -> q.quote().marketRate().compareTo(new BigDecimal("148.0000")) == 0);
  }

  @Test
  void listRoutesReturnsRoutesOrderedByCode() {
    when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of(instant, standard));

    assertThat(service.listRoutes()).containsExactly(instant, standard);
  }

  @Test
  void updateRouteAppliesChangesAndSaves() {
    when(routes.findById(standard.getId())).thenReturn(Optional.of(standard));
    when(routes.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    RouteApi.Update update =
        new RouteApi.Update(
            new BigDecimal("6.00"), new BigDecimal("1.0"), 120, new BigDecimal("99.00"), true, 0L);

    TransferRoute updated = service.updateRoute(standard.getId().toString(), update);

    assertThat(updated.getBaseFee()).isEqualByComparingTo("6.00");
    assertThat(updated.getFxSpreadPercentage()).isEqualByComparingTo("1.0");
    assertThat(updated.getEstimatedMinutes()).isEqualTo(120);
    assertThat(updated.getSuccessRate()).isEqualByComparingTo("99.00");
    verify(routes).save(standard);
  }

  @Test
  void updateRouteWithStaleVersionThrowsOptimisticLockingFailure() {
    when(routes.findById(standard.getId())).thenReturn(Optional.of(standard));
    RouteApi.Update stale =
        new RouteApi.Update(
            new BigDecimal("6.00"), new BigDecimal("1.0"), 120, new BigDecimal("99.00"), true, 5L);

    assertThatThrownBy(() -> service.updateRoute(standard.getId().toString(), stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    verify(routes, never()).save(any());
  }

  @Test
  void updateRouteSaveConflictPropagatesAsOptimisticLockingFailure() {
    when(routes.findById(standard.getId())).thenReturn(Optional.of(standard));
    when(routes.save(any()))
        .thenThrow(
            new ObjectOptimisticLockingFailureException(
                TransferRoute.class, standard.getId().toString()));
    RouteApi.Update update =
        new RouteApi.Update(
            new BigDecimal("6.00"), new BigDecimal("1.0"), 120, new BigDecimal("99.00"), true, 0L);

    assertThatThrownBy(() -> service.updateRoute(standard.getId().toString(), update))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  void metricForUsesLoadedEntityWithoutRefetching() {
    RouteReliabilityService.RouteReliability metric = service.metricFor(standard);

    assertThat(metric.routeId()).isEqualTo(standard.getId());
    verify(routes, never()).findById(any());
  }
}
