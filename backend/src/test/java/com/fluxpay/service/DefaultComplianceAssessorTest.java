package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultComplianceAssessorTest {
  private final DefaultComplianceAssessor assessor = new DefaultComplianceAssessor();

  @Test
  void approvesAnyConfirmedPayout() {
    assertThat(assessor.assess(UUID.randomUUID(), new BigDecimal("1000.00"), "USD"))
        .isEqualTo(ScreeningVerdict.APPROVE);
  }
}
