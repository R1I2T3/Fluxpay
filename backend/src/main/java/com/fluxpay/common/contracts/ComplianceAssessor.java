package com.fluxpay.common.contracts;

import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public interface ComplianceAssessor {
  ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency);

  default ScreeningVerdict assess(ComplianceScreeningInput input) {
    Objects.requireNonNull(input, "input must not be null");
    return assess(input.userId(), input.amount(), input.currency());
  }

  default ComplianceAssessment assessDetailed(UUID userId, BigDecimal amount, String currency) {
    return ComplianceAssessment.fromVerdict(assess(userId, amount, currency));
  }

  default ComplianceAssessment assessDetailed(ComplianceScreeningInput input) {
    Objects.requireNonNull(input, "input must not be null");
    return assessDetailed(input.userId(), input.amount(), input.currency());
  }
}
