package com.fluxpay.service;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.dto.TransferRoutingContext;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog over {@code transfer_routes}: customer-facing listing, frozen-FX recommendation for one
 * payment, and learned-reliability metrics. Recommendations delegate to the shared smart-routing
 * orchestration so quotes and recommendations rank the same top three.
 */
@Service
public class RouteCatalogService {

  private final PaymentReader reader;
  private final FxRateProvider fx;
  private final TransferRouteRepository routes;
  private final RouteReliabilityService reliability;
  private final SmartRoutingService smart;

  public RouteCatalogService(
      PaymentReader reader,
      FxRateProvider fx,
      TransferRouteRepository routes,
      RouteReliabilityService reliability,
      SmartRoutingService smart) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.fx = Objects.requireNonNull(fx, "fx must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.reliability = Objects.requireNonNull(reliability, "reliability must not be null");
    this.smart = Objects.requireNonNull(smart, "smart must not be null");
  }

  @Transactional(readOnly = true)
  public List<TransferRoute> listRoutes() {
    return routes.findAllByOrderByRouteCodeAsc();
  }

  @Transactional(readOnly = true)
  public RouteRecommendation recommend(
      String paymentId, RoutePreference preference, String correlationId) {
    PaymentSnapshot payment = reader.get(paymentId);
    RoutePreference effective = preference == null ? RoutePreference.BALANCED : preference;
    BigDecimal marketRate = fx.rate(payment.sourceCurrency(), payment.targetCurrency());
    TransferRoutingContext context =
        new TransferRoutingContext(
            DestinationType.EXTERNAL_ACCOUNT,
            Objects.requireNonNull(payment.destination(), "payment has no frozen destination")
                .country(),
            payment.destination().currency(),
            payment.amount(),
            marketRate);
    return smart.recommend(context, effective);
  }

  @Transactional
  public TransferRoute updateRoute(String routeId, RouteApi.Update update) {
    Objects.requireNonNull(update, "update must not be null");
    requireValid(update);
    UUID id = parseRouteId(routeId);
    TransferRoute route =
        routes
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("route " + routeId + " not found"));
    if (!Objects.equals(route.getVersion(), update.version())) {
      throw new ObjectOptimisticLockingFailureException(TransferRoute.class, routeId);
    }
    route.update(
        update.baseFee(),
        update.fxSpreadPercentage(),
        update.estimatedMinutes(),
        update.successRate(),
        update.active());
    return routes.save(route);
  }

  public RouteReliabilityService.RouteReliability metricFor(TransferRoute route) {
    Objects.requireNonNull(route, "route must not be null");
    return reliability.effectiveFor(List.of(route)).get(route.getId());
  }

  public RouteReliabilityService.RouteReliability metricFor(UUID routeId) {
    TransferRoute route =
        routes
            .findById(routeId)
            .orElseThrow(() -> new NoSuchElementException("route " + routeId + " not found"));
    return metricFor(route);
  }

  private static UUID parseRouteId(String routeId) {
    try {
      return UUID.fromString(routeId);
    } catch (IllegalArgumentException e) {
      throw new NoSuchElementException("route " + routeId + " not found");
    }
  }

  private static void requireValid(RouteApi.Update update) {
    if (update.baseFee() == null) {
      throw new IllegalArgumentException("baseFee must not be null");
    }
    if (update.fxSpreadPercentage() == null) {
      throw new IllegalArgumentException("fxSpreadPercentage must not be null");
    }
    if (update.successRate() == null) {
      throw new IllegalArgumentException("successRate must not be null");
    }
  }
}
