package com.fluxpay.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
  /** Canonical {@link Clock} for the merged production context; all Clock injectors share it. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
