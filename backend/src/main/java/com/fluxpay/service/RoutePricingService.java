package com.fluxpay.service;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.QuotePricingPolicy;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Pure route-driven quote economics. Fees and spreads come entirely from the route row; an invalid
 * candidate is skipped without failing otherwise valid candidates. The request fails with {@code
 * 422 NO_ELIGIBLE_ROUTES} only when no candidate survives.
 */
@Service
public class RoutePricingService {
  private final QuotePricingPolicy pricing;

  public RoutePricingService(QuotePricingPolicy pricing) {
    this.pricing = pricing;
  }

  public List<RouteQuote> price(
      BigDecimal gross, BigDecimal marketRate, List<TransferRoute> routes) {
    return price(gross, marketRate, routes, Map.of());
  }

  public List<RouteQuote> price(
      BigDecimal gross,
      BigDecimal marketRate,
      List<TransferRoute> routes,
      Map<UUID, BigDecimal> effectiveReliability) {
    List<RouteQuote> priced = new ArrayList<>();
    for (TransferRoute route : routes) {
      if (!route.isActive()) {
        continue;
      }
      try {
        BigDecimal fee = route.baseFee().setScale(4, RoundingMode.HALF_EVEN);
        var quote = pricing.price(gross, fee, route.fxSpreadPercentage(), marketRate);
        if (route.minimumRecipientAmount() != null
            && quote.recipientAmount().compareTo(route.minimumRecipientAmount()) < 0) {
          continue;
        }
        if (route.maximumRecipientAmount() != null
            && quote.recipientAmount().compareTo(route.maximumRecipientAmount()) > 0) {
          continue;
        }
        priced.add(
            new RouteQuote(
                route,
                marketRate.setScale(6, RoundingMode.HALF_EVEN),
                quote.offeredRate(),
                quote.recipientAmount(),
                fee,
                quote.netSourceAmount(),
                effectiveReliability.getOrDefault(route.id(), route.configuredSuccessRate())));
      } catch (IllegalArgumentException e) {
        continue;
      }
    }
    if (priced.isEmpty()) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "NO_ELIGIBLE_ROUTES",
          "No transfer route is eligible for this destination.");
    }
    return priced;
  }
}
