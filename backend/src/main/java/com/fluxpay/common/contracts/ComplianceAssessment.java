package com.fluxpay.common.contracts;

import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import java.util.List;

/** Compliance decision enriched with the policy facts persisted for an operator review. */
public record ComplianceAssessment(
    ScreeningVerdict verdict, ComplianceRisk risk, List<String> reasons, String suggestedAction) {
  public ComplianceAssessment {
    if (verdict == null || risk == null || reasons == null || suggestedAction == null) {
      throw new IllegalArgumentException("Compliance assessment fields are required");
    }
    reasons = List.copyOf(reasons);
  }

  public static ComplianceAssessment fromVerdict(ScreeningVerdict verdict) {
    return verdict == ScreeningVerdict.APPROVE
        ? new ComplianceAssessment(
            ScreeningVerdict.APPROVE, ComplianceRisk.LOW, List.of(), "Proceed with payment processing.")
        : new ComplianceAssessment(
            verdict,
            ComplianceRisk.MEDIUM,
            List.of("MANUAL_COMPLIANCE_REVIEW_REQUIRED"),
            "Hold payment for manual compliance review before payout.");
  }
}
