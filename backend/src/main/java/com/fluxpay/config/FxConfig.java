package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.fx.FrankfurterFxProvider;
import com.fluxpay.adapter.fx.MockFxRateProvider;
import com.fluxpay.common.contracts.FxSnapshotSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link FxSnapshotSource}. {@code live} (default) uses {@link FrankfurterFxProvider};
 * {@code mock}/{@code solo} use the deterministic {@link com.fluxpay.adapter.fx.MockFxRateProvider}
 * for offline work and tests only.
 */
@Configuration
public class FxConfig {
  private final String mode;
  private final String providerUrl;

  public FxConfig(
      @Value("${fluxpay.fx-mode:live}") String mode,
      @Value("${fluxpay.fx-provider-url}") String providerUrl) {
    this.mode = mode == null ? "live" : mode.trim().toLowerCase(Locale.ROOT);
    this.providerUrl = providerUrl;
  }

  @Bean
  FxSnapshotSource fxSnapshotSource(ObjectMapper objectMapper, Clock fxClock) {
    if ("mock".equals(mode) || "solo".equals(mode)) {
      return new MockFxRateProvider(fxClock);
    }
    if (!"live".equals(mode)) {
      throw new IllegalArgumentException("fluxpay.fx-mode must be live, mock or solo");
    }
    if (providerUrl == null || providerUrl.isBlank()) {
      throw new IllegalArgumentException("fluxpay.fx-provider-url is required in live mode");
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return new FrankfurterFxProvider(client, objectMapper, providerUrl.trim(), fxClock);
  }
}
