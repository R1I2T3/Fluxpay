package com.fluxpay.service;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.dto.RouteRecommendation;
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
 * Catalog over {@code payout_routes}: lists rails, recommends one per payment via the frozen FX
 * rate, and applies admin updates with optimistic locking.
 */
@Service
public class RouteCatalogService {

  private final PaymentReader reader;
  private final FxRateProvider fx;
  private final RouteRecommender recommender;
  private final TransferRouteRepository routes;
  private final RouteMetrics metrics;
  private final RoutePricingService pricing;

  public RouteCatalogService(
      PaymentReader reader,
      FxRateProvider fx,
      RouteRecommender recommender,
      TransferRouteRepository routes,
      RouteMetrics metrics,
      RoutePricingService pricing) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.fx = Objects.requireNonNull(fx, "fx must not be null");
    this.recommender = Objects.requireNonNull(recommender, "recommender must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    this.pricing = pricing;
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
    List<TransferRoute> active = routes.findByActiveTrueOrderByRouteCodeAsc();
    return recommender.recommend(effective, pricing.price(payment.amount(), marketRate, active));
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

  public RouteMetrics.RouteMetric metricFor(UUID routeId) {
    return metrics.byRoute(routeId);
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
