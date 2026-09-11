package com.fluxpay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
public class TimelineKafkaConfiguration {
  @Bean
  public DefaultErrorHandler timelineErrorHandler() {
    // Poison envelopes are explicitly quarantined by
    //  listener. Any exception escaping it
    // (including a failed quarantine send) must keep its offset retryable until infrastructure
    // recovers. Boot installs this handler on the auto-configured listener container factory.
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setClassifications(java.util.Map.of(), true);
    return handler;
  }
}
