package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.development.SimulatedInstantPayoutProvider;
import com.fluxpay.development.SimulatedLocalPartnerProvider;
import com.fluxpay.development.SimulatedStandardBankProvider;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Development simulator checks. Failure simulation lives only in the development implementations;
 * production payout paths and recovery tests use explicit fixtures instead of environment probes.
 */
class PayoutProviderTest {
  private PayoutCmd command(String route, String amount, String fee) {
    var attempt = java.util.UUID.randomUUID();
    return new PayoutCmd(
        "P-001",
        new BigDecimal(amount),
        "USD",
        "KES",
        route,
        new BigDecimal(fee),
        1,
        new BigDecimal("80"),
        new BigDecimal("7600"),
        attempt,
        "payout:" + attempt);
  }

  @Test
  void standardBankSucceedsWhenFailureIsNotRequested() {
    PayoutResult result =
        new SimulatedStandardBankProvider(() -> null)
            .submit(command("STANDARD_BANK", "1000.00", "5.00"));
    assertThat(result.success()).isTrue();
    assertThat(result.outcome()).isEqualTo(PayoutResult.Outcome.COMPLETED);
    assertThat(result.providerRef()).startsWith("SB-");
  }

  @Test
  void standardBankFailureIsDeterministic() {
    PayoutResult result =
        new SimulatedStandardBankProvider(() -> "STANDARD_BANK")
            .submit(command("STANDARD_BANK", "1000.00", "5.00"));
    assertThat(result.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(result.errorMessage()).isEqualTo("Simulated bank timeout");
    assertThat(result.outcome()).isEqualTo(PayoutResult.Outcome.UNCERTAIN);
  }

  @Test
  void standardBankFailCountThenSucceeds() {
    SimulatedStandardBankProvider adapter =
        new SimulatedStandardBankProvider(() -> "STANDARD_BANK:2");
    PayoutCmd cmd = command("STANDARD_BANK", "1000.00", "5.00");
    assertThat(adapter.submit(cmd).success()).isFalse();
    assertThat(adapter.submit(command("STANDARD_BANK", "1000.00", "5.00")).success()).isFalse();
    assertThat(adapter.submit(command("STANDARD_BANK", "1000.00", "5.00")).success()).isTrue();
  }

  @Test
  void repeatedProviderKeyReplaysTheSameOutcomeForEveryAdapter() {
    for (var provider :
        java.util.List.of(
            new SimulatedStandardBankProvider(() -> null),
            new SimulatedInstantPayoutProvider(),
            new SimulatedLocalPartnerProvider())) {
      var cmd = command(provider.code(), "100", "5");
      var first = provider.submit(cmd);
      assertThat(provider.submit(cmd)).isEqualTo(first);
    }
  }

  @Test
  void repeatedFailedAttemptDoesNotConsumeAnotherSimulatedAttempt() {
    var provider = new SimulatedStandardBankProvider(() -> "STANDARD_BANK:1");
    var cmd = command(provider.code(), "100", "5");
    var first = provider.submit(cmd);
    assertThat(first.success()).isFalse();
    assertThat(provider.submit(cmd)).isEqualTo(first);
    assertThat(provider.submit(command(provider.code(), "100", "5")).success()).isTrue();
  }

  @Test
  void providerCommandRejectsMissingOrMismatchedAttemptIdentity() {
    var attempt = java.util.UUID.randomUUID();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new PayoutCmd(
                    "payment",
                    BigDecimal.TEN,
                    "USD",
                    "INR",
                    "BANK",
                    BigDecimal.ONE,
                    1,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    attempt,
                    "arbitrary-key"))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new PayoutCmd(
                    "payment",
                    BigDecimal.TEN,
                    "USD",
                    "INR",
                    "BANK",
                    BigDecimal.ONE,
                    1,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void instantDoesNotAddAFeeAfterCustomerAcceptedTheQuote() {
    PayoutResult result =
        new SimulatedInstantPayoutProvider().submit(command("INSTANT_PAYOUT", "1000.00", "11.00"));
    assertThat(result.providerFee()).isEqualByComparingTo("11.00");
    assertThat(result.outcome()).isEqualTo(PayoutResult.Outcome.COMPLETED);
  }

  @Test
  void localPartnerRejectsAmountsOverFiftyThousand() {
    PayoutResult result =
        new SimulatedLocalPartnerProvider().submit(command("LOCAL_PARTNER", "50000.01", "2.00"));
    assertThat(result.errorCode()).isEqualTo("LIMIT_EXCEEDED");
    assertThat(result.outcome()).isEqualTo(PayoutResult.Outcome.FAILED);
  }
}
