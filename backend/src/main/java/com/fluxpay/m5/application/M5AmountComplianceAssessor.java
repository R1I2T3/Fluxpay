package com.fluxpay.m5.application;

import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.m5.infrastructure.config.M5ComplianceProperties;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/** Concrete M5 adapter for the current payment-confirmation compliance port. */
public class M5AmountComplianceAssessor implements ComplianceAssessor {
  private final M5ComplianceProperties properties;

  public M5AmountComplianceAssessor(M5ComplianceProperties properties) {
    this.properties = properties;
  }

  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    if (userId == null || amount == null || amount.signum() <= 0 || currency == null) {
      return ScreeningVerdict.REVIEW;
    }
    BigDecimal threshold = properties.reviewThresholds().get(currency.toUpperCase(Locale.ROOT));
    return threshold != null && amount.compareTo(threshold) <= 0
        ? ScreeningVerdict.APPROVE
        : ScreeningVerdict.REVIEW;
  }
}
