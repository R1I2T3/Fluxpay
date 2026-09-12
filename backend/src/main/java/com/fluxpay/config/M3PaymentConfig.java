package com.fluxpay.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class M3PaymentConfig {
  /** Canonical {@link Clock} for the merged production context; all Clock injectors share it. */
  @Bean
  @Primary
  Clock m3Clock() {
    return Clock.systemUTC();
  }
}
