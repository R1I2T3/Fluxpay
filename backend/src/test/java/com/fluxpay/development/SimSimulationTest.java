package com.fluxpay.development;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.contracts.ComplianceScreeningInput;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.ExternalAccountDestination;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferProviderSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.TransferRouteSnapshot;
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
  void railSimulatorsExposeDeterministicAndUncertainTriggers() {
    var partner = new SimulatedPartnerNetworkRail().execute(command("50000.01"));
    var uncertain = new SimulatedBankNetworkRail(() -> "BANK_NETWORK").execute(command("100.00"));

    assertThat(partner.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
    assertThat(partner.providerRef()).startsWith("PARTNER-");
    assertThat(uncertain.outcome()).isEqualTo(TransferRailResult.Outcome.UNCERTAIN);
    assertThat(uncertain.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
  }

  @Test
  void bankRailFailCountThenSucceeds() {
    SimulatedBankNetworkRail rail = new SimulatedBankNetworkRail(() -> "BANK_NETWORK:2");
    UUID transfer = UUID.randomUUID();
    assertThat(rail.execute(command("100.00", transfer)).success()).isFalse();
    assertThat(rail.execute(command("100.00", transfer)).success()).isFalse();
    assertThat(rail.execute(command("100.00", transfer)).success()).isTrue();
  }

  @Test
  void repeatedRailKeyReplaysTheSameOutcomeForEveryRail() {
    for (TransferRail rail :
        java.util.List.<TransferRail>of(
            new SimulatedBankNetworkRail(() -> null),
            new SimulatedRealTimeNetworkRail(),
            new SimulatedPartnerNetworkRail())) {
      var cmd = command("100");
      var first = rail.execute(cmd);
      assertThat(rail.execute(cmd)).isEqualTo(first);
    }
  }

  @Test
  void repeatedFailedAttemptDoesNotConsumeAnotherSimulatedAttempt() {
    var rail = new SimulatedBankNetworkRail(() -> "BANK_NETWORK:1");
    UUID transfer = UUID.randomUUID();
    var cmd = command("100", transfer);
    var first = rail.execute(cmd);
    assertThat(first.success()).isFalse();
    assertThat(rail.execute(cmd)).isEqualTo(first);
    assertThat(rail.execute(command("100", transfer)).success()).isTrue();
  }

  private TransferRailCommand command(String amount) {
    return command(amount, UUID.randomUUID());
  }

  private TransferRailCommand command(String amount, UUID transfer) {
    UUID attempt = UUID.randomUUID();
    UUID providerId = UUID.randomUUID();
    var provider = new TransferProviderSnapshot(providerId, "SIM_BANK", RailType.BANK_NETWORK);
    var route =
        new TransferRouteSnapshot(
            UUID.randomUUID(), "SIM_ROUTE", DestinationType.EXTERNAL_ACCOUNT, providerId);
    return new TransferRailCommand(
        transfer,
        attempt,
        UUID.randomUUID(),
        new BigDecimal(amount),
        "USD",
        new BigDecimal("8000"),
        "KES",
        provider,
        route,
        new ExternalAccountDestination("acct", "Bank", "KE", "KES"),
        1,
        BigDecimal.ZERO,
        new BigDecimal("80"),
        "payout:" + attempt);
  }
}
