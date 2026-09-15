package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.fx.FrankfurterFxProvider;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.contracts.FxSnapshotSource;
import com.fluxpay.config.ClockConfig;
import com.fluxpay.config.FxConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Sole {@link FxRateProvider} is {@link FxQuoteService}, feeding the live {@code FxConfig}. */
class FxQuoteServicePrimaryTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "fluxpay.fx-provider-url=https://fx.invalid/latest",
              "fluxpay.system-user-id=00000000-0000-0000-0000-00000000d004")
          .withBean("objectMapper", ObjectMapper.class, ObjectMapper::new)
          .withUserConfiguration(FxConfig.class, ClockConfig.class)
          .withBean(FxQuoteService.class);

  @Test
  void liveConfigurationResolvesThroughFxQuoteService() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FxRateProvider.class);
          assertThat(context).hasSingleBean(FxSnapshotSource.class);
          assertThat(context.getBean(FxRateProvider.class)).isInstanceOf(FxQuoteService.class);
          // The only production source is the HTTP adapter; no mock fallback remains.
          assertThat(context.getBean(FxSnapshotSource.class))
              .isInstanceOf(FrankfurterFxProvider.class);
        });
  }

  @Test
  void retiredMockModeIsRejectedWithAnActionableMessage() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "fluxpay.fx-mode=mock", "fluxpay.fx-provider-url=https://fx.invalid/latest")
        .withBean("objectMapper", ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(FxConfig.class, ClockConfig.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("fluxpay.fx-mode/FLUXPAY_FX_MODE is retired");
            });
  }

  @Test
  void missingProviderUrlIsRejectedWithoutFakeFallback() {
    new ApplicationContextRunner()
        .withBean("objectMapper", ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(FxConfig.class, ClockConfig.class)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("fluxpay.fx-provider-url is required");
            });
  }
}
