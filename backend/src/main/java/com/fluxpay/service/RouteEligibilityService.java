package com.fluxpay.service;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.dto.TransferRoutingContext;
import com.fluxpay.exception.BusinessException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Pure contextual filtering over the catalogue. A candidate survives only when the provider and
 * route are active and unarchived, the destination corridor matches, and an installed rail supports
 * the destination type. Pricing and limits are applied later; this stage never fails the request,
 * it only narrows the candidates.
 */
@Service
public class RouteEligibilityService {

  private final RailRegistry rails;

  public RouteEligibilityService(RailRegistry rails) {
    this.rails = Objects.requireNonNull(rails, "rails must not be null");
  }

  public List<TransferRoute> filter(List<TransferRoute> routes, TransferRoutingContext context) {
    Objects.requireNonNull(routes, "routes must not be null");
    Objects.requireNonNull(context, "context must not be null");
    return routes.stream().filter(route -> eligible(route, context)).toList();
  }

  private boolean eligible(TransferRoute route, TransferRoutingContext context) {
    if (route == null || !route.isActive() || route.getArchivedAt() != null) {
      return false;
    }
    TransferProvider provider = route.provider();
    if (provider == null || !provider.isActive() || provider.getArchivedAt() != null) {
      return false;
    }
    if (route.getDestinationType() != context.destinationType()) {
      return false;
    }
    // A null route country is the internal all-country wildcard; otherwise it must match.
    if (route.getDestinationCountry() != null
        && !route.getDestinationCountry().equals(context.destinationCountry())) {
      return false;
    }
    if (!route.getPayoutCurrency().equals(context.payoutCurrency())) {
      return false;
    }
    try {
      rails.requireCompatible(provider.getRailType(), context.destinationType());
    } catch (BusinessException e) {
      return false;
    }
    return true;
  }
}
