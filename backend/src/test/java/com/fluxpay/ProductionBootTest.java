package com.fluxpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.config.M3PaymentConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Task 9 verification: the merged production context boots with no Spring profile. {@code mock},
 * {@code local} and {@code solo} are no longer required anywhere.
 */
class ProductionBootTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(M3PaymentConfig.class)
          .withBean("eventClock", Clock.class, () -> Clock.systemUTC())
          .withBean("m2FxClock", Clock.class, () -> Clock.systemUTC());

  @Test
  void mergedContextHasExactlyOnePrimaryClock() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(Clock.class)).isNotNull();
        });
  }
}
