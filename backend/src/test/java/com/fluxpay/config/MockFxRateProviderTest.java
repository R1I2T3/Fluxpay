package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.contracts.FxRateProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class MockFxRateProviderTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues("spring.profiles.active=mock")
          .withUserConfiguration(MockFxRateConfiguration.class);

  @Test
  void mockProfileProvidesFixedUsdToKesRateForAcceptanceTests() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(FxRateProvider.class);
          assertThat(context.getBean(FxRateProvider.class).rate("USD", "KES"))
              .isEqualByComparingTo("148.0000");
        });
  }

  @Test
  void existingFxProviderTakesPrecedenceOverMock() {
    FxRateProvider teamProvider = (from, to) -> BigDecimal.ONE;

    contextRunner
        .withBean(FxRateProvider.class, () -> teamProvider)
        .run(
            context -> {
              assertThat(context).hasSingleBean(FxRateProvider.class);
              assertThat(context.getBean(FxRateProvider.class)).isSameAs(teamProvider);
            });
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(
      basePackages = "com.fluxpay.config",
      useDefaultFilters = false,
      includeFilters =
          @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Configuration.class))
  static class MockFxRateConfiguration {}
}
