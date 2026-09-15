package com.fluxpay.config;

import com.fluxpay.exception.SystemAccountUnavailableException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SystemAccountConfig {
  private final UUID systemUserId;

  public SystemAccountConfig(@Value("${fluxpay.system-user-id:}") String value) {
    if (value == null || value.isBlank()) {
      systemUserId = null;
    } else {
      try {
        systemUserId = UUID.fromString(value.trim());
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("fluxpay.system-user-id must be a UUID", exception);
      }
    }
  }

  public UUID requireSystemUserId() {
    if (systemUserId == null) {
      throw new SystemAccountUnavailableException();
    }
    return systemUserId;
  }
}
