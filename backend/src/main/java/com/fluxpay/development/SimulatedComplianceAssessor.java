package com.fluxpay.development;

import com.fluxpay.common.contracts.ComplianceAssessment;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.ComplianceScreeningInput;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only deterministic compliance assessor.
 *
 * <p>Active only when {@code fluxpay.development.simulated-compliance-enabled=true} (default {@code
 * false}). With simulation disabled, {@link com.fluxpay.service.AmountComplianceAssessor} handles
 * assessments by default; disabling compliance selects {@link
 * com.fluxpay.service.UnavailableComplianceAssessor} instead.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.development.simulated-compliance-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SimulatedComplianceAssessor implements ComplianceAssessor {
  private static final String SANCTIONS_FRAGMENT = "SANCTIONED_ACME";

  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    return ScreeningVerdict.APPROVE;
  }

  @Override
  public ScreeningVerdict assess(ComplianceScreeningInput input) {
    return assessDetailed(input).verdict();
  }

  @Override
  public ComplianceAssessment assessDetailed(ComplianceScreeningInput input) {
    Objects.requireNonNull(input, "input must not be null");
    if (input.recipientName().toUpperCase(Locale.ROOT).contains(SANCTIONS_FRAGMENT)) {
      return new ComplianceAssessment(
          ScreeningVerdict.BLOCK,
          ComplianceRisk.HIGH,
          List.of("SANCTIONS_HIT"),
          "Stop payment and escalate the sanctions hit for investigation.");
    }
    return new ComplianceAssessment(
        ScreeningVerdict.APPROVE,
        ComplianceRisk.LOW,
        List.of(),
        "Proceed with payment processing.");
  }
}
