package com.fluxpay.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Source-currency fees are deducted before conversion; money is rounded HALF_EVEN. */
@Component
public class QuotePricingPolicy {
  public PricedRoute price(
      BigDecimal gross, BigDecimal fee, BigDecimal spreadPercent, BigDecimal marketRate) {
    if (gross == null
        || fee == null
        || spreadPercent == null
        || marketRate == null
        || gross.signum() <= 0
        || fee.signum() < 0
        || spreadPercent.signum() < 0
        || spreadPercent.compareTo(new BigDecimal("100")) >= 0
        || marketRate.signum() <= 0) {
      throw new IllegalArgumentException("Invalid quote inputs");
    }
    BigDecimal net = gross.subtract(fee).setScale(4, RoundingMode.HALF_EVEN);
    BigDecimal offered =
        marketRate
            .multiply(BigDecimal.ONE.subtract(spreadPercent.movePointLeft(2)))
            .setScale(6, RoundingMode.HALF_EVEN);
    BigDecimal recipient = net.multiply(offered).setScale(4, RoundingMode.HALF_EVEN);
    if (net.signum() <= 0 || offered.signum() <= 0 || recipient.signum() <= 0) {
      throw new IllegalArgumentException("Quote net and recipient amounts must be positive");
    }
    return new PricedRoute(net, offered, recipient);
  }

  /** Existing express customer surcharge, frozen at quote creation rather than provider submit. */
  public BigDecimal customerFee(String routeCode, BigDecimal baseFee) {
    return baseFee
        .add("INSTANT_PAYOUT".equals(routeCode) ? new BigDecimal("2.50") : BigDecimal.ZERO)
        .setScale(4, RoundingMode.HALF_EVEN);
  }
}
