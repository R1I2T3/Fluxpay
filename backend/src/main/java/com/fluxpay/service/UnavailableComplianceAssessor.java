package com.fluxpay.service;

import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Rejects assessments with {@code 503 COMPLIANCE_UNAVAILABLE} when {@code
 * fluxpay.compliance.enabled=false}.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.compliance.enabled",
    havingValue = "false",
    matchIfMissing = false)
public class UnavailableComplianceAssessor implements ComplianceAssessor {
  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "No compliance integration is configured.");
  }
}
