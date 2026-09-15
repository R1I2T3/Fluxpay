package com.fluxpay.development;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Development-only always-approve assessor; never active in the default context. */
class SimulatedComplianceAssessorTest {
  private final SimulatedComplianceAssessor assessor = new SimulatedComplianceAssessor();

  @Test
  void approvesAnyConfirmedPayout() {
    assertThat(assessor.assess(UUID.randomUUID(), new BigDecimal("1000.00"), "USD"))
        .isEqualTo(ScreeningVerdict.APPROVE);
  }
}
