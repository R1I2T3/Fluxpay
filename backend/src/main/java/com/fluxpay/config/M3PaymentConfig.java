package com.fluxpay.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class M3PaymentConfig {
  @Bean
  Clock m3Clock() {
    return Clock.systemUTC();
  }
}
