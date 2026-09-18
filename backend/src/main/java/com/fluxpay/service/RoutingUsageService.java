package com.fluxpay.service;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers whether catalogue records have been referenced by quotes or execution attempts.
 *
 * <p>Route codes are immutable, so the interim quote foreign key on {@code route_code} (replaced
 * with {@code route_id} in a later task) is safe to query by code.
 */
@Service
public class RoutingUsageService {
  private final TransferRouteRepository routes;
  private final PaymentQuoteRepository quotes;
  private final PayoutAttemptRepository attempts;
  private final TransferRouteOutcomeRepository outcomes;

  public RoutingUsageService(
      TransferRouteRepository routes,
      PaymentQuoteRepository quotes,
      PayoutAttemptRepository attempts,
      TransferRouteOutcomeRepository outcomes) {
    this.routes = routes;
    this.quotes = quotes;
    this.attempts = attempts;
    this.outcomes = outcomes;
  }

  @Transactional(readOnly = true)
  public boolean routeUsed(UUID routeId) {
    return routes.findById(routeId).map(route -> isUsed(routeId, route.code())).orElse(false);
  }

  @Transactional(readOnly = true)
  public boolean providerUsed(UUID providerId) {
    List<TransferRoute> children = routes.findByProviderIdOrderByRouteCodeAsc(providerId);
    for (TransferRoute child : children) {
      if (isUsed(child.id(), child.code())) {
        return true;
      }
    }
    return false;
  }

  private boolean isUsed(UUID routeId, String routeCode) {
    return quotes.existsByRoute(routeCode)
        || attempts.existsByRouteId(routeId)
        || outcomes.existsByRouteId(routeId);
  }
}
