package com.fluxpay.config;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class M2DemoFundingConfig {
  private final boolean enabled;
  private final UUID systemUserId;

  public M2DemoFundingConfig(
      @Value("${fluxpay.demo-funding-enabled:false}") boolean enabled,
      @Value("${fluxpay.demo-system-user-id:}") String systemUserId) {
    this.enabled = enabled;
    this.systemUserId = parseSystemUserId(systemUserId);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public UUID getSystemUserId() {
    return systemUserId;
  }

  private static UUID parseSystemUserId(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("fluxpay.demo-system-user-id must be a UUID", exception);
    }
  }
}
