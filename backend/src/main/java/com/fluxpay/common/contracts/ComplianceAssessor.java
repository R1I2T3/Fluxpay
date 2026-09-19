package com.fluxpay.common.contracts;

import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.UUID;

public interface ComplianceAssessor {
  ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency);

  default ComplianceAssessment assessDetailed(UUID userId, BigDecimal amount, String currency) {
    return ComplianceAssessment.fromVerdict(assess(userId, amount, currency));
  }

  default ComplianceAssessment assessDetailed(ComplianceScreeningContext context) {
    return assessDetailed(context.userId(), context.amount(), context.currency());
  }
}
