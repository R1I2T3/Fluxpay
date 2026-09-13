package com.fluxpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.config.ClockConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Clock wiring smoke test; full infrastructure acceptance belongs to the integration suite. */
class ProductionBootTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(ClockConfig.class);

  @Test
  void clockConfigurationProvidesOneClock() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(Clock.class);
        });
  }
}
