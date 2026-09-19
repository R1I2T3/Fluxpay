package com.fluxpay.service;

import static java.math.RoundingMode.HALF_EVEN;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk learned reliability over terminal route outcomes. The configured success rate acts as a
 * prior equivalent to 20 terminal attempts; only terminal COMPLETED/FAILED outcomes shift it. All
 * money and scores stay in {@link BigDecimal} with HALF_EVEN rounding.
 */
@Service
public class RouteReliabilityService {

  private static final BigDecimal PRIOR_ATTEMPTS = new BigDecimal("20");

  /** Effective reliability plus the terminal counts behind it, for one route. */
  public record RouteReliability(
      UUID routeId, BigDecimal effectiveReliability, long completedCount, long failedCount) {}

  private final TransferRouteOutcomeRepository outcomes;

  public RouteReliabilityService(TransferRouteOutcomeRepository outcomes) {
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
  }

  public BigDecimal effective(BigDecimal configuredPercent, long completed, long failed) {
    Objects.requireNonNull(configuredPercent, "configuredPercent must not be null");
    BigDecimal prior = configuredPercent.movePointLeft(2).multiply(PRIOR_ATTEMPTS);
    BigDecimal probability =
        prior
            .add(BigDecimal.valueOf(completed))
            .divide(PRIOR_ATTEMPTS.add(BigDecimal.valueOf(completed + failed)), 8, HALF_EVEN);
    return probability.movePointRight(2).setScale(6, HALF_EVEN);
  }

  /**
   * Computes effective reliability for every candidate with a single grouped outcome query. Routes
   * without observations keep their configured rate with zero counts.
   */
  @Transactional(readOnly = true)
  public Map<UUID, RouteReliability> effectiveFor(List<TransferRoute> routes) {
    Objects.requireNonNull(routes, "routes must not be null");
    if (routes.isEmpty()) {
      return Map.of();
    }
    Map<UUID, TransferRouteOutcomeRepository.RouteOutcomeCounts> counts =
        outcomes.countByRouteIds(routes.stream().map(TransferRoute::getId).toList()).stream()
            .collect(
                Collectors.toMap(
                    TransferRouteOutcomeRepository.RouteOutcomeCounts::getRouteId,
                    row -> row,
                    (first, second) -> first));
    Map<UUID, RouteReliability> result = new LinkedHashMap<>();
    for (TransferRoute route : routes) {
      TransferRouteOutcomeRepository.RouteOutcomeCounts row = counts.get(route.getId());
      long completed = row == null ? 0 : row.getCompleted();
      long failed = row == null ? 0 : row.getFailed();
      result.put(
          route.getId(),
          new RouteReliability(
              route.getId(),
              effective(route.configuredSuccessRate(), completed, failed),
              completed,
              failed));
    }
    return Collections.unmodifiableMap(result);
  }
}
