package com.fluxpay.service;

import com.fluxpay.common.contracts.ComplianceAssessment;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.ComplianceScreeningContext;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.ComplianceProperties;
import com.fluxpay.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Assesses amount and recipient-history risks using facts available at payment confirmation. */
public class PaymentRiskComplianceAssessor implements ComplianceAssessor {
  private final AmountComplianceAssessor amountAssessor;
  private final ComplianceProperties properties;
  private final PaymentRepository payments;

  public PaymentRiskComplianceAssessor(
      ComplianceProperties properties, PaymentRepository payments) {
    this.amountAssessor = new AmountComplianceAssessor(properties);
    this.properties = properties;
    this.payments = payments;
  }

  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    return amountAssessor.assess(userId, amount, currency);
  }

  @Override
  public ComplianceAssessment assessDetailed(ComplianceScreeningContext context) {
    ComplianceAssessment amountAssessment =
        amountAssessor.assessDetailed(context.userId(), context.amount(), context.currency());
    if (amountAssessment.risk() == ComplianceRisk.HIGH) {
      return amountAssessment;
    }

    List<String> reasons = new ArrayList<>(amountAssessment.reasons());
    if (context.recipientId() == null
        || context.paymentId() == null
        || context.assessedAt() == null) {
      return new ComplianceAssessment(
          ScreeningVerdict.REVIEW,
          ComplianceRisk.HIGH,
          List.of("INVALID_COMPLIANCE_INPUT"),
          "Hold payment and correct the compliance assessment input before payout.");
    }
    if (!payments.existsPriorSubmittedPaymentForRecipient(
        context.userId(), context.recipientId(), context.paymentId())) {
      reasons.add("FIRST_TRANSFER_TO_RECIPIENT");
    }
    if (isRecentlyAdded(context)) {
      reasons.add("RECIPIENT_ADDED_TODAY");
    }
    if (reasons.isEmpty()) {
      return amountAssessment;
    }

    ComplianceRisk risk = reasons.size() >= 2 ? ComplianceRisk.HIGH : ComplianceRisk.MEDIUM;
    return new ComplianceAssessment(
        ScreeningVerdict.REVIEW, risk, List.copyOf(reasons), suggestedAction(reasons, risk));
  }

  private boolean isRecentlyAdded(ComplianceScreeningContext context) {
    return context.recipientCreatedAt() != null
        && !context.recipientCreatedAt().isAfter(context.assessedAt())
        && Duration.between(context.recipientCreatedAt(), context.assessedAt()).toHours()
            < properties.recipientRecentHours();
  }

  private static String suggestedAction(List<String> reasons, ComplianceRisk risk) {
    if (risk == ComplianceRisk.HIGH) {
      return "Hold payment for manual compliance review. Verify recipient details, payment purpose, and source of funds before payout.";
    }
    if (reasons.contains("AMOUNT_EXCEEDS_REVIEW_THRESHOLD")) {
      return "Hold payment for manual compliance review before payout.";
    }
    return "Confirm recipient bank details and payment purpose before payout.";
  }
}
