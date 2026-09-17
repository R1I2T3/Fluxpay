package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.ComplianceProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AmountComplianceAssessorTest {

  private final AmountComplianceAssessor assessor =
      new AmountComplianceAssessor(
          new ComplianceProperties(Map.of("USD", new BigDecimal("10000"))));

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

  @Test
  void providesConcreteRiskEvidenceForEveryReviewOutcome() {
    var thresholdReview =
        assessor.assessDetailed(UUID.randomUUID(), new BigDecimal("10000.01"), "USD");
    var unsupportedCurrency = assessor.assessDetailed(UUID.randomUUID(), BigDecimal.ONE, "EUR");

    assertThat(thresholdReview.risk()).isEqualTo(com.fluxpay.common.enums.ComplianceRisk.MEDIUM);
    assertThat(thresholdReview.reasons()).containsExactly("AMOUNT_EXCEEDS_REVIEW_THRESHOLD");
    assertThat(unsupportedCurrency.risk()).isEqualTo(com.fluxpay.common.enums.ComplianceRisk.HIGH);
    assertThat(unsupportedCurrency.reasons()).containsExactly("UNSUPPORTED_SOURCE_CURRENCY");
  }
}
