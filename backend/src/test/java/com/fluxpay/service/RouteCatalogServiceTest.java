package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.dto.RoutePreference;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.repository.PayoutRouteRepository;
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
  @Mock private PayoutRouteRepository routes;
  @Mock private RouteMetrics metrics;

  private RouteCatalogService service;
  private PaymentSnapshot payment;
  private PayoutRoute standard;
  private PayoutRoute instant;

  @BeforeEach
  void setUp() {
    service = new RouteCatalogService(reader, fx, new RouteRecommender(), routes, metrics);
    payment =
        new PaymentSnapshot(
            "P-001",
            UUID.nameUUIDFromBytes("fluxpay:P-001:user".getBytes()),
            UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes()),
            UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes()),
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentStatus.ROUTED);
    standard =
        PayoutRoute.seed(
            UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8)),
            "STANDARD_BANK",
            "Standard Bank Rail",
            "Standard Bank",
            "STANDARD",
            "5.00",
            "0.8",
            240,
            "99.50");
    instant =
        PayoutRoute.seed(
            UUID.nameUUIDFromBytes("fluxpay:route:INSTANT_PAYOUT".getBytes(StandardCharsets.UTF_8)),
            "INSTANT_PAYOUT",
            "Instant Payout",
            "Instant Payout Co",
            "INSTANT",
            "8.50",
            "2.0",
            5,
            "98.00");
  }

  @Test
  void recommendationFetchesMarketRateFromFxProviderAndReturnsIt() {
    when(reader.get("P-001")).thenReturn(payment);
    when(fx.rate("USD", "KES")).thenReturn(new BigDecimal("148.0000"));
    when(routes.findByActiveTrueOrderByRouteCodeAsc()).thenReturn(List.of(instant, standard));

    RouteRecommendation recommendation =
        service.recommend("P-001", RoutePreference.BALANCED, "c-uuid");

    verify(fx).rate("USD", "KES");
    assertThat(recommendation.quotes()).hasSize(2);
    assertThat(recommendation.quotes().get(0).marketRate()).isEqualByComparingTo("148.0000");
    assertThat(recommendation.quotes())
        .allMatch(q -> q.marketRate().compareTo(new BigDecimal("148.0000")) == 0);
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

    PayoutRoute updated = service.updateRoute(standard.getId().toString(), update);

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
                PayoutRoute.class, standard.getId().toString()));
    RouteApi.Update update =
        new RouteApi.Update(
            new BigDecimal("6.00"), new BigDecimal("1.0"), 120, new BigDecimal("99.00"), true, 0L);

    assertThatThrownBy(() -> service.updateRoute(standard.getId().toString(), update))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }
}
