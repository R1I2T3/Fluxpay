package com.fluxpay.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoFundingConfig {
  private final boolean enabled;

  /**
   * Development funding is disabled by default ({@code fluxpay.demo-funding-enabled:false}). The
   * {@code /api/wallets/receive-demo} endpoint always requires JWT authentication; there is no
   * header-based identity bypass in any profile.
   */
  public DemoFundingConfig(@Value("${fluxpay.demo-funding-enabled:false}") boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
