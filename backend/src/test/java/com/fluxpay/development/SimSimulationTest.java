package com.fluxpay.development;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.contracts.ComplianceScreeningInput;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimSimulationTest {
  private final SimulatedComplianceAssessor assessor = new SimulatedComplianceAssessor();

  @Test
  void blocklistedRecipientProducesHighRiskSanctionsHit() {
    var input =
        new ComplianceScreeningInput(
            UUID.randomUUID(), "Vendor SANCTIONED_ACME Ltd", new BigDecimal("10.00"), "USD");

    var assessment = assessor.assessDetailed(input);

    assertThat(assessment.verdict()).isEqualTo(ScreeningVerdict.BLOCK);
    assertThat(assessment.risk()).isEqualTo(ComplianceRisk.HIGH);
    assertThat(assessment.reasons()).containsExactly("SANCTIONS_HIT");
    assertThat(assessment.suggestedAction()).contains("Stop payment");
    assertThat(assessor.assess(input)).isEqualTo(ScreeningVerdict.BLOCK);
  }

  @Test
  void sanctionsNameMatchIsCaseInsensitiveAndOrdinaryNamesApprove() {
    assertThat(
            assessor.assess(
                new ComplianceScreeningInput(
                    UUID.randomUUID(), "sanctioned_acme", BigDecimal.TEN, "USD")))
        .isEqualTo(ScreeningVerdict.BLOCK);
    assertThat(
            assessor.assess(
                new ComplianceScreeningInput(
                    UUID.randomUUID(), "Ordinary Recipient", BigDecimal.TEN, "USD")))
        .isEqualTo(ScreeningVerdict.APPROVE);
  }

  @Test
  void payoutSimulatorsExposeLimitAndUncertainTriggers() {
    var local = new SimulatedLocalPartnerProvider().submit(command("LOCAL_PARTNER", "50000.01"));
    var uncertain =
        new SimulatedStandardBankProvider(() -> "STANDARD_BANK")
            .submit(command("STANDARD_BANK", "100.00"));

    assertThat(local.outcome()).isEqualTo(PayoutResult.Outcome.FAILED);
    assertThat(local.errorCode()).isEqualTo("LIMIT_EXCEEDED");
    assertThat(uncertain.outcome()).isEqualTo(PayoutResult.Outcome.UNCERTAIN);
    assertThat(uncertain.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
  }

  private PayoutCmd command(String route, String amount) {
    UUID attempt = UUID.randomUUID();
    return new PayoutCmd(
        "P-001",
        new BigDecimal(amount),
        "USD",
        "KES",
        route,
        BigDecimal.ZERO,
        1,
        new BigDecimal("80"),
        new BigDecimal("8000"),
        attempt,
        "payout:" + attempt);
  }
}
