package com.fluxpay.service;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.QuotePricingPolicy;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RoutePricingService {
  private final QuotePricingPolicy pricing;

  public RoutePricingService(QuotePricingPolicy pricing) {
    this.pricing = pricing;
  }

  public List<RouteQuote> price(
      BigDecimal gross, BigDecimal marketRate, List<TransferRoute> routes) {
    List<TransferRoute> active = routes.stream().filter(TransferRoute::isActive).toList();
    if (active.isEmpty()) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "NO_ACTIVE_ROUTES",
          "No active payout routes are available.");
    }
    try {
      return active.stream()
          .map(
              route -> {
                BigDecimal fee = pricing.customerFee(route.code(), route.baseFee());
                var priced = pricing.price(gross, fee, route.fxSpreadPercentage(), marketRate);
                return new RouteQuote(
                    route,
                    marketRate.setScale(6, RoundingMode.HALF_EVEN),
                    priced.offeredRate(),
                    priced.recipientAmount(),
                    fee,
                    priced.netSourceAmount());
              })
          .toList();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVALID_AMOUNT",
          "Amount must produce positive net and recipient amounts for every route.");
    }
  }
}
