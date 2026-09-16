package com.fluxpay.m5.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.m5.infrastructure.config.M5ComplianceProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M5AmountComplianceAssessorTest {

  private final M5AmountComplianceAssessor assessor =
      new M5AmountComplianceAssessor(
          new M5ComplianceProperties(Map.of("USD", new BigDecimal("10000"))));

  @Test
  void approvesAnAmountWithinTheConfiguredCurrencyThreshold() {
    assertThat(assessor.assess(UUID.randomUUID(), new BigDecimal("10000.00"), "USD"))
        .isEqualTo(ScreeningVerdict.APPROVE);
  }

  @Test
  void routesHighOrUnknownCurrencyAmountsToManualReview() {
    assertThat(assessor.assess(UUID.randomUUID(), new BigDecimal("10000.01"), "USD"))
        .isEqualTo(ScreeningVerdict.REVIEW);
    assertThat(assessor.assess(UUID.randomUUID(), BigDecimal.ONE, "EUR"))
        .isEqualTo(ScreeningVerdict.REVIEW);
  }
}
