package com.fluxpay.development;

import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only always-approve compliance assessor.
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
  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    return ScreeningVerdict.APPROVE;
  }
}
