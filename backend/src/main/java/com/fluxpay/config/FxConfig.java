package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.fx.FrankfurterFxProvider;
import com.fluxpay.common.contracts.FxSnapshotSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single production {@link FxSnapshotSource}: the {@link FrankfurterFxProvider} HTTP
 * adapter. Retired {@code fluxpay.fx-mode} ({@code live}/{@code mock}/{@code solo}) selection and
 * deterministic mock sources were removed; tests inject their own {@link FxSnapshotSource} doubles
 * instead of selecting fake data through runtime configuration.
 */
@Configuration
public class FxConfig {
  private final String providerUrl;
  private final String retiredMode;
  private final String retiredModeEnv;

  public FxConfig(
      @Value("${fluxpay.fx-provider-url:}") String providerUrl,
      @Value("${fluxpay.fx-mode:}") String retiredMode,
      @Value("${FLUXPAY_FX_MODE:}") String retiredModeEnv) {
    this.providerUrl = providerUrl;
    this.retiredMode = retiredMode;
    this.retiredModeEnv = retiredModeEnv;
  }

  @Bean
  FxSnapshotSource fxSnapshotSource(ObjectMapper objectMapper, Clock fxClock) {
    String retired = !retiredMode.isBlank() ? retiredMode : retiredModeEnv.trim();
    if (!retired.isBlank()) {
      throw new IllegalArgumentException(
          "fluxpay.fx-mode/FLUXPAY_FX_MODE is retired (was '"
              + retired
              + "'): remove the setting and configure fluxpay.fx-provider-url "
              + "(for example https://api.frankfurter.dev/v1/latest) for live rates; "
              + "tests inject their own FxSnapshotSource doubles");
    }
    if (providerUrl == null || providerUrl.isBlank()) {
      throw new IllegalArgumentException(
          "fluxpay.fx-provider-url is required (for example "
              + "https://api.frankfurter.dev/v1/latest); fake FX fallback was removed");
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return new FrankfurterFxProvider(client, objectMapper, providerUrl.trim(), fxClock);
  }
}
