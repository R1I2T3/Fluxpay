package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevelopmentPayoutSimulationPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(DevelopmentPayoutSimulationConfiguration.class);

  @Test
  void defaultsAreSafeAndRecoveryDelayIs120Seconds() {
    runner.run(
        context -> {
          var properties = context.getBean(DevelopmentPayoutSimulationProperties.class);
          assertThat(properties.simulatedPayoutsEnabled()).isFalse();
          assertThat(properties.retrySuccessFailureAttempts()).isZero();
          assertThat(properties.refundFailureAttempts()).isZero();
          assertThat(properties.recoveryDelaySeconds()).isEqualTo(120);
        });
  }

  @Test
  void bindsTwoCaseSensitiveRoutePolicies() {
    runner
        .withPropertyValues(
            "fluxpay.development.simulated-payouts-enabled=true",
            "fluxpay.development.retry-success-route-code= BANK_STANDARD ",
            "fluxpay.development.retry-success-failure-attempts=2",
            "fluxpay.development.refund-route-code=BANK_EXPRESS",
            "fluxpay.development.refund-failure-attempts=6",
            "fluxpay.development.recovery-delay-seconds=5")
        .run(
            context -> {
              var properties = context.getBean(DevelopmentPayoutSimulationProperties.class);
              assertThat(properties.failurePolicyFor("BANK_STANDARD"))
                  .contains(
                      new DevelopmentPayoutSimulationProperties.RouteFailurePolicy(
                          "BANK_STANDARD", 2));
              assertThat(properties.failurePolicyFor("bank_standard")).isEmpty();
              assertThat(properties.failurePolicyFor("BANK_EXPRESS"))
                  .contains(
                      new DevelopmentPayoutSimulationProperties.RouteFailurePolicy(
                          "BANK_EXPRESS", 6));
              assertThat(properties.recoveryDelaySeconds()).isEqualTo(5);
            });
  }

  @Test
  void rejectsUnsafeCrossFieldConfiguration() {
    assertThatThrownBy(
            () ->
                new DevelopmentPayoutSimulationProperties(false, "BANK_STANDARD", 2, null, 0, 120))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("simulated-payouts-enabled");
    assertThatThrownBy(
            () -> new DevelopmentPayoutSimulationProperties(true, null, -1, null, 0, 120))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("failure attempts");
    assertThatThrownBy(() -> new DevelopmentPayoutSimulationProperties(true, null, 0, null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recovery delay");
  }
}
