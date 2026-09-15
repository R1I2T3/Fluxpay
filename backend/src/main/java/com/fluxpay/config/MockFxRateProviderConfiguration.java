package com.fluxpay.config;

import com.fluxpay.common.contracts.FxRateProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Provisional mock FX wiring; the owning team's provider takes precedence when available. */
@Configuration(proxyBeanMethods = false)
@Profile("mock")
public class MockFxRateProviderConfiguration {

  @Bean
  @ConditionalOnMissingBean(FxRateProvider.class)
  public FxRateProvider mockFxRateProvider() {
    return new MockFxRateProvider();
  }
}
