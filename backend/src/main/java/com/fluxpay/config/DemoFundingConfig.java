package com.fluxpay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoFundingConfig {
  private final boolean enabled;

  public DemoFundingConfig(@Value("${fluxpay.demo-funding-enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
