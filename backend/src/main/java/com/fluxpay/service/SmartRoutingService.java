package com.fluxpay.service;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.dto.TransferRoutingContext;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared smart-routing orchestration for transfer quotes and recommendations. Both entry points
 * filter the catalogue by the frozen destination corridor, price the survivors with learned
 * reliability, and rank the top three. Reads use the provider-fetching catalogue query.
 */
@Service
public class SmartRoutingService {

  private final TransferRouteRepository routes;
  private final RouteEligibilityService eligibility;
  private final RouteReliabilityService reliability;
  private final RoutePricingService pricing;
  private final RouteRecommender recommender;

  public SmartRoutingService(
      TransferRouteRepository routes,
      RouteEligibilityService eligibility,
      RouteReliabilityService reliability,
      RoutePricingService pricing,
      RouteRecommender recommender) {
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.eligibility = Objects.requireNonNull(eligibility, "eligibility must not be null");
    this.reliability = Objects.requireNonNull(reliability, "reliability must not be null");
    this.pricing = Objects.requireNonNull(pricing, "pricing must not be null");
    this.recommender = Objects.requireNonNull(recommender, "recommender must not be null");
  }

  @Transactional(readOnly = true)
  public RouteRecommendation recommend(TransferRoutingContext context, RoutePreference preference) {
    Objects.requireNonNull(context, "context must not be null");
    RoutePreference effective = preference == null ? RoutePreference.BALANCED : preference;
    List<TransferRoute> eligible =
        eligibility.filter(routes.findAllByOrderByRouteCodeAsc(), context).stream()
            .filter(route -> route.getEstimatedMinutes() > 0)
            .toList();
    Map<UUID, BigDecimal> rates =
        reliability.effectiveFor(eligible).entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey, entry -> entry.getValue().effectiveReliability()));
    List<RouteQuote> priced = pricing.price(context.gross(), context.marketRate(), eligible, rates);
    if (priced.isEmpty()) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "NO_ELIGIBLE_ROUTES",
          "No transfer route is eligible for this destination.");
    }
    return recommender.recommend(effective, priced);
  }
}
