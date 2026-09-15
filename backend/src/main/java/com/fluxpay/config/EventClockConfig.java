package com.fluxpay.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventClockConfig {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock eventClock() {
    return Clock.systemUTC();
  }
}
