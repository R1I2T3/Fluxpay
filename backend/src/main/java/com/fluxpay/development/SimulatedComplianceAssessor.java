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
 * false}). Outside that mode confirmation returns {@code 503 COMPLIANCE_UNAVAILABLE} via {@link
 * com.fluxpay.service.UnavailableComplianceAssessor}; unfinished compliance is never silently
 * treated as approval.
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
