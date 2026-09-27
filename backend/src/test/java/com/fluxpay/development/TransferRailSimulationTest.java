package com.fluxpay.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.config.DevelopmentPayoutSimulationProperties;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.ExternalAccountDestination;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferProviderSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.TransferRouteSnapshot;
import com.fluxpay.repository.PayoutAttemptRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Development simulator checks. Failure simulation lives only in the development rail
 * implementations; production transfer paths use explicit fixtures instead of environment probes.
 */
class TransferRailSimulationTest {

  private TransferRailCommand command(UUID transferId, String amount, String fee) {
    return command(transferId, "DEMO_BANK_KE", UUID.randomUUID(), 1, amount, fee);
  }

  private TransferRailCommand command(
      UUID transferId, String routeCode, UUID routeId, int attemptNumber) {
    return command(transferId, routeCode, routeId, attemptNumber, "1000.00", "5.00");
  }

  private TransferRailCommand command(
      UUID transferId,
      String routeCode,
      UUID routeId,
      int attemptNumber,
      String amount,
      String fee) {
    UUID attempt = UUID.randomUUID();
    UUID providerId = UUID.randomUUID();
    return new TransferRailCommand(
        transferId,
        attempt,
        UUID.randomUUID(),
        new BigDecimal(amount),
        "USD",
        new BigDecimal("7600"),
        "KES",
        new TransferProviderSnapshot(providerId, "DEMO_BANK", RailType.BANK_NETWORK),
        new TransferRouteSnapshot(routeId, routeCode, DestinationType.EXTERNAL_ACCOUNT, providerId),
        new ExternalAccountDestination("AC-123", "Demo Bank", "KE", "KES"),
        attemptNumber,
        new BigDecimal(fee),
        new BigDecimal("80"),
        "payout:" + attempt);
  }

  private TransferRailCommand command(String amount, String fee) {
    return command(UUID.randomUUID(), amount, fee);
  }

  @Test
  void bankSucceedsWhenFailureIsNotRequested() {
    TransferRailResult result =
        new SimulatedBankNetworkRail(() -> null).execute(command("1000.00", "5.00"));
    assertThat(result.success()).isTrue();
    assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
    assertThat(result.providerRef()).startsWith("BANK-");
  }

  @Test
  void bankFailureIsUncertain() {
    TransferRailResult result =
        new SimulatedBankNetworkRail(() -> "BANK_NETWORK").execute(command("1000.00", "5.00"));
    assertThat(result.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(result.errorMessage()).isEqualTo("Simulated bank timeout");
    assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.UNCERTAIN);
  }

  @Test
  void bankFailCountThenSucceeds() {
    SimulatedBankNetworkRail rail = new SimulatedBankNetworkRail(() -> "BANK_NETWORK:2");
    UUID transferId = UUID.randomUUID();
    assertThat(rail.execute(command(transferId, "1000.00", "5.00")).success()).isFalse();
    assertThat(rail.execute(command(transferId, "1000.00", "5.00")).success()).isFalse();
    assertThat(rail.execute(command(transferId, "1000.00", "5.00")).success()).isTrue();
  }

  @Test
  void configuredRouteFailsDefinitiveCountThenCompletes() {
    var attempts = mock(PayoutAttemptRepository.class);
    var properties =
        new DevelopmentPayoutSimulationProperties(true, "BANK_STANDARD", 2, null, 0, 120);
    var rail = new SimulatedBankNetworkRail(properties, attempts, () -> null);
    var routeId = UUID.randomUUID();
    when(attempts.countByPaymentIdAndRouteId(anyString(), eq(routeId))).thenReturn(1L, 2L, 3L);

    var first = rail.execute(command(UUID.randomUUID(), "BANK_STANDARD", routeId, 1));
    var second = rail.execute(command(UUID.randomUUID(), "BANK_STANDARD", routeId, 2));
    var third = rail.execute(command(UUID.randomUUID(), "BANK_STANDARD", routeId, 3));

    assertThat(first.outcome()).isEqualTo(TransferRailResult.Outcome.FAILED);
    assertThat(second.outcome()).isEqualTo(TransferRailResult.Outcome.FAILED);
    assertThat(third.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
    assertThat(first.errorCode()).isEqualTo("SIMULATED_PROVIDER_FAILURE");
    assertThat(first.errorMessage()).contains("BANK_STANDARD");
    assertThat(first.providerFee()).isEqualByComparingTo("5.00");
  }

  @Test
  void configuredDefinitivePolicyPrecedesLegacyUncertainProbe() {
    var attempts = mock(PayoutAttemptRepository.class);
    var routeId = UUID.randomUUID();
    when(attempts.countByPaymentIdAndRouteId(anyString(), eq(routeId))).thenReturn(1L);
    var properties = new DevelopmentPayoutSimulationProperties(true, "BANK_EXPRESS", 6, null, 0, 5);
    var rail = new SimulatedBankNetworkRail(properties, attempts, () -> "BANK_NETWORK");

    var result = rail.execute(command(UUID.randomUUID(), "BANK_EXPRESS", routeId, 1));

    assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.FAILED);
  }

  @Test
  void sameProviderKeyReplaysWithoutConsumingAnotherFailure() {
    var attempts = mock(PayoutAttemptRepository.class);
    var routeId = UUID.randomUUID();
    when(attempts.countByPaymentIdAndRouteId(anyString(), eq(routeId))).thenReturn(1L);
    var rail =
        new SimulatedBankNetworkRail(
            new DevelopmentPayoutSimulationProperties(true, "BANK_STANDARD", 2, null, 0, 120),
            attempts,
            () -> null);
    var command = command(UUID.randomUUID(), "BANK_STANDARD", routeId, 1);

    var first = rail.execute(command);
    var replay = rail.execute(command);

    assertThat(replay).isEqualTo(first);
    verify(attempts, times(1)).countByPaymentIdAndRouteId(anyString(), eq(routeId));
  }

  @Test
  void nonmatchingBankRouteSucceeds() {
    var attempts = mock(PayoutAttemptRepository.class);
    var rail =
        new SimulatedBankNetworkRail(
            new DevelopmentPayoutSimulationProperties(true, "BANK_STANDARD", 2, null, 0, 120),
            attempts,
            () -> null);

    var result = rail.execute(command(UUID.randomUUID(), "BANK_OTHER", UUID.randomUUID(), 1));

    assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
    verifyNoInteractions(attempts);
  }

  @Test
  void repeatedRailKeyReplaysTheSameOutcomeForEveryRail() {
    for (TransferRail rail :
        List.of(
            new SimulatedBankNetworkRail(() -> null),
            new SimulatedRealTimeNetworkRail(),
            new SimulatedPartnerNetworkRail())) {
      TransferRailCommand cmd = command("100", "5");
      TransferRailResult first = rail.execute(cmd);
      assertThat(rail.execute(cmd)).isEqualTo(first);
    }
  }

  @Test
  void repeatedFailedAttemptDoesNotConsumeAnotherSimulatedAttempt() {
    SimulatedBankNetworkRail rail = new SimulatedBankNetworkRail(() -> "BANK_NETWORK:1");
    UUID transferId = UUID.randomUUID();
    TransferRailCommand cmd = command(transferId, "100", "5");
    TransferRailResult first = rail.execute(cmd);
    assertThat(first.success()).isFalse();
    assertThat(rail.execute(cmd)).isEqualTo(first);
    assertThat(rail.execute(command(transferId, "100", "5")).success()).isTrue();
  }

  @Test
  void realTimeSucceedsDeterministically() {
    TransferRailResult result =
        new SimulatedRealTimeNetworkRail().execute(command("1000.00", "11.00"));
    assertThat(result.providerFee()).isEqualByComparingTo("11.00");
    assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
    assertThat(result.providerRef()).startsWith("REALTIME-");
  }

  @Test
  void partnerSucceedsWithoutAnAmountCeiling() {
    SimulatedPartnerNetworkRail rail = new SimulatedPartnerNetworkRail();
    assertThat(rail.execute(command("50000.01", "2.00")).success()).isTrue();
    assertThat(rail.execute(command("500000.00", "2.00")).success()).isTrue();
    assertThat(rail.execute(command("500000.00", "2.00")).providerRef()).startsWith("PARTNER-");
  }

  @Test
  void simulationRailsServeExternalDestinationsOnly() {
    assertThat(new SimulatedBankNetworkRail(() -> null).type()).isEqualTo(RailType.BANK_NETWORK);
    assertThat(new SimulatedRealTimeNetworkRail().type()).isEqualTo(RailType.REAL_TIME_NETWORK);
    assertThat(new SimulatedPartnerNetworkRail().type()).isEqualTo(RailType.PARTNER_NETWORK);
    for (TransferRail rail :
        List.of(
            new SimulatedBankNetworkRail(() -> null),
            new SimulatedRealTimeNetworkRail(),
            new SimulatedPartnerNetworkRail())) {
      assertThat(rail.supportedDestinations()).containsExactly(DestinationType.EXTERNAL_ACCOUNT);
    }
  }

  @Test
  void railCommandRejectsMissingOrMismatchedAttemptIdentity() {
    UUID attempt = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                new TransferRailCommand(
                    UUID.randomUUID(),
                    attempt,
                    UUID.randomUUID(),
                    BigDecimal.TEN,
                    "USD",
                    BigDecimal.ONE,
                    "INR",
                    new TransferProviderSnapshot(UUID.randomUUID(), "DEMO", RailType.BANK_NETWORK),
                    new TransferRouteSnapshot(
                        UUID.randomUUID(),
                        "DEMO_ROUTE",
                        DestinationType.EXTERNAL_ACCOUNT,
                        UUID.randomUUID()),
                    new ExternalAccountDestination("AC-1", "Demo", "IN", "INR"),
                    1,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    "arbitrary-key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransferRailCommand(
                    UUID.randomUUID(),
                    null,
                    UUID.randomUUID(),
                    BigDecimal.TEN,
                    "USD",
                    BigDecimal.ONE,
                    "INR",
                    new TransferProviderSnapshot(UUID.randomUUID(), "DEMO", RailType.BANK_NETWORK),
                    new TransferRouteSnapshot(
                        UUID.randomUUID(),
                        "DEMO_ROUTE",
                        DestinationType.EXTERNAL_ACCOUNT,
                        UUID.randomUUID()),
                    new ExternalAccountDestination("AC-1", "Demo", "IN", "INR"),
                    1,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void railResultFactoriesEnforceOutcomeInvariants() {
    assertThat(TransferRailResult.completed("BANK-1", BigDecimal.ONE).success()).isTrue();
    assertThatThrownBy(() -> TransferRailResult.completed(null, BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TransferRailResult.failed(null, "message", BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TransferRailResult.uncertain("CODE", null, BigDecimal.ONE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
