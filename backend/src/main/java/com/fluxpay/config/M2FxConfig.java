package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.service.FrankfurterFxProvider;
import com.fluxpay.service.FxSnapshotSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class M2FxConfig {
  private final String mode;
  private final String providerUrl;
  private final UUID systemUserId;

  public M2FxConfig(
      @Value("${fluxpay.fx-mode:live}") String mode,
      @Value("${fluxpay.fx-provider-url}") String providerUrl,
      @Value("${fluxpay.fx-system-user-id:${fluxpay.demo-system-user-id:}}") String systemUserId) {
    this.mode = mode == null ? "live" : mode.trim().toLowerCase(Locale.ROOT);
    this.providerUrl = providerUrl;
    this.systemUserId = parseSystemUserId(systemUserId);
  }

  @Bean
  Clock m2FxClock() {
    return Clock.systemUTC();
  }

  @Bean
  FxSnapshotSource m2FxSnapshotSource(ObjectMapper objectMapper, Clock m2FxClock) {
    if ("mock".equals(mode) || "solo".equals(mode)) {
      return new M2MockFxRateProvider(m2FxClock);
    }
    if (!"live".equals(mode)) {
      throw new IllegalArgumentException("fluxpay.fx-mode must be live, mock or solo");
    }
    if (providerUrl == null || providerUrl.isBlank()) {
      throw new IllegalArgumentException("fluxpay.fx-provider-url is required in live mode");
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return new FrankfurterFxProvider(client, objectMapper, providerUrl.trim(), m2FxClock);
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
      throw new IllegalArgumentException("FX system user ID must be a UUID", exception);
    }
  }
}
