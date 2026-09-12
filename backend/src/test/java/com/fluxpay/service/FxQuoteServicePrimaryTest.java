package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.config.M2FxConfig;
import com.fluxpay.config.M3PaymentConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Sole {@link FxRateProvider} is {@link FxQuoteService}, feeding the live {@code M2FxConfig}. */
class FxQuoteServicePrimaryTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "fluxpay.fx-mode=mock",
              "fluxpay.fx-provider-url=https://fx.invalid/latest",
              "fluxpay.fx-system-user-id=00000000-0000-0000-0000-00000000d004")
          .withBean("objectMapper", ObjectMapper.class, ObjectMapper::new)
          .withUserConfiguration(M2FxConfig.class, M3PaymentConfig.class)
          .withBean(FxQuoteService.class);

  @Test
  void mockModeResolvesThroughFxQuoteService() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(FxRateProvider.class);
          FxRateProvider fx = context.getBean(FxRateProvider.class);
          assertThat(fx).isInstanceOf(FxQuoteService.class);
          assertThat(fx.rate("USD", "INR")).isEqualByComparingTo(new BigDecimal("83.50"));
        });
  }
}
