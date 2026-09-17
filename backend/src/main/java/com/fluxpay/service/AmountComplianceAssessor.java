package com.fluxpay.service;

import com.fluxpay.common.contracts.ComplianceAssessment;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.ComplianceProperties;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/** Concrete adapter for the current payment-confirmation compliance port. */
public class AmountComplianceAssessor implements ComplianceAssessor {
  private final ComplianceProperties properties;

  public AmountComplianceAssessor(ComplianceProperties properties) {
    this.properties = properties;
  }

  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    return assessDetailed(userId, amount, currency).verdict();
  }

  @Override
  public ComplianceAssessment assessDetailed(UUID userId, BigDecimal amount, String currency) {
    if (userId == null
        || amount == null
        || amount.signum() <= 0
        || currency == null
        || currency.isBlank()) {
      return new ComplianceAssessment(
          ScreeningVerdict.REVIEW,
          ComplianceRisk.HIGH,
          java.util.List.of("INVALID_COMPLIANCE_INPUT"),
          "Hold payment and correct the compliance assessment input before payout.");
    }
    BigDecimal threshold = properties.reviewThresholds().get(currency.toUpperCase(Locale.ROOT));
    if (threshold == null) {
      return new ComplianceAssessment(
          ScreeningVerdict.REVIEW,
          ComplianceRisk.HIGH,
          java.util.List.of("UNSUPPORTED_SOURCE_CURRENCY"),
          "Hold payment for manual review because no threshold is configured for the source currency.");
    }
    if (amount.compareTo(threshold) > 0) {
      return new ComplianceAssessment(
          ScreeningVerdict.REVIEW,
          ComplianceRisk.MEDIUM,
          java.util.List.of("AMOUNT_EXCEEDS_REVIEW_THRESHOLD"),
          "Hold payment for manual compliance review before payout.");
    }
    return new ComplianceAssessment(
        ScreeningVerdict.APPROVE,
        ComplianceRisk.LOW,
        java.util.List.of(),
        "Proceed with payment processing.");
  }
}
