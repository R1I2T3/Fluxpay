package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RouteReliabilityServiceTest {

  @Mock private TransferRouteOutcomeRepository outcomes;

  private RouteReliabilityService service;

  @BeforeEach
  void setUp() {
    service = new RouteReliabilityService(outcomes);
  }

  @Test
  void observationsBlendWithTwentyAttemptPrior() {
    // 80% * 20 virtual attempts + 9 completed + 1 failed.
    assertThat(service.effective(new BigDecimal("80.00"), 9, 1)).isEqualByComparingTo("83.333333");
  }

  @Test
  void priorAloneWhenNoObservations() {
    assertThat(service.effective(new BigDecimal("99.50"), 0, 0)).isEqualByComparingTo("99.500000");
  }

  @Test
  void observationsDominateOverTime() {
    assertThat(service.effective(new BigDecimal("50.00"), 90, 10))
        .isEqualByComparingTo("83.333333");
  }

  @Test
  void effectiveForUsesOneGroupedQueryAndBlendsPerRoute() {
    TransferRoute observed =
        TransferRoute.seed(
            UUID.randomUUID(),
            "OBSERVED_ROUTE",
            "Observed",
            "Bank",
            "STANDARD",
            "5",
            "0",
            60,
            "80.00");
    TransferRoute fresh =
        TransferRoute.seed(
            UUID.randomUUID(), "FRESH_ROUTE", "Fresh", "Bank", "STANDARD", "5", "0", 60, "50.00");
    var row = counts(observed.getId(), 9, 1);
    when(outcomes.countByRouteIds(any())).thenReturn(List.of(row));

    Map<UUID, RouteReliabilityService.RouteReliability> result =
        service.effectiveFor(List.of(observed, fresh));

    verify(outcomes).countByRouteIds(List.of(observed.getId(), fresh.getId()));
    assertThat(result.get(observed.getId()).effectiveReliability())
        .isEqualByComparingTo("83.333333");
    assertThat(result.get(observed.getId()).completedCount()).isEqualTo(9);
    assertThat(result.get(observed.getId()).failedCount()).isEqualTo(1);
    assertThat(result.get(fresh.getId()).effectiveReliability()).isEqualByComparingTo("50.000000");
    assertThat(result.get(fresh.getId()).completedCount()).isZero();
    assertThat(result.get(fresh.getId()).failedCount()).isZero();
  }

  @Test
  void effectiveForEmptyReturnsEmptyWithoutQuery() {
    assertThat(service.effectiveFor(List.of())).isEmpty();
    verifyNoInteractions(outcomes);
  }

  private static TransferRouteOutcomeRepository.RouteOutcomeCounts counts(
      UUID routeId, long completed, long failed) {
    var row = mock(TransferRouteOutcomeRepository.RouteOutcomeCounts.class);
    when(row.getRouteId()).thenReturn(routeId);
    when(row.getCompleted()).thenReturn(completed);
    when(row.getFailed()).thenReturn(failed);
    return row;
  }
}
