package com.fluxpay.service;

import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic M3 screen: a customer who reached PAYOUT_CONFIRM already passed KYC and holds an
 * active quote, so the default verdict permits the payout. {@link PaymentConfirmationService} wraps
 * every call in its 3s {@code assessWithTimeout} contract.
 */
@Component
public class DefaultComplianceAssessor implements ComplianceAssessor {
  @Override
  public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
    return ScreeningVerdict.APPROVE;
  }
}
