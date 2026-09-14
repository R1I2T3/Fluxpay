package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.fx.FrankfurterFxProvider;
import com.fluxpay.common.contracts.FxSnapshotSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Production-boundary proofs: no retired mock FX source remains on the production classpath, the
 * live FX configuration wires exactly one HTTP-backed snapshot source, and retired FX modes fail
 * fast instead of silently serving fake data. HTTP-level security proofs live in {@code
 * LocalAuthBypassRegressionTest}.
 */
class MockSegregationInventoryTest {
  @Test
  void retiredMockFxSourceIsAbsentFromProductionSources() {
    Path preferred =
        Path.of("backend/src/main/java/com/fluxpay/adapter/fx/MockFxRateProvider.java");
    Path fallback = Path.of("src/main/java/com/fluxpay/adapter/fx/MockFxRateProvider.java");
    Path rootPreferred = Path.of("backend/src/main/java");
    Path rootFallback = Path.of("src/main/java");
    Path root = Files.isDirectory(rootPreferred) ? rootPreferred : rootFallback;

    assertThat(Files.exists(preferred)).isFalse();
    assertThat(Files.exists(fallback)).isFalse();
    assertThat(Files.isDirectory(root)).isTrue();
  }

  @Test
  void liveConfigurationWiresTheHttpSnapshotSource() {
    new ApplicationContextRunner()
        .withUserConfiguration(ClockConfig.class, FxConfig.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("fluxpay.fx-provider-url=https://fx.invalid/latest")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(FxSnapshotSource.class);
              assertThat(context.getBean(FxSnapshotSource.class))
                  .isInstanceOf(FrankfurterFxProvider.class);
            });
  }

  @Test
  void retiredFxModesAreRejectedExplicitly() {
    for (String retiredMode : new String[] {"mock", "solo", "live"}) {
      new ApplicationContextRunner()
          .withUserConfiguration(ClockConfig.class, FxConfig.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withPropertyValues(
              "fluxpay.fx-mode=" + retiredMode, "fluxpay.fx-provider-url=https://fx.invalid/latest")
          .run(
              context ->
                  assertThat(context.getStartupFailure())
                      .hasStackTraceContaining("fluxpay.fx-mode/FLUXPAY_FX_MODE is retired"));
    }
  }
}
