package com.fluxpay.config;

import com.fluxpay.common.contracts.LedgerWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("mock")
public class MockLedgerWriterConfiguration {

  @Bean
  @ConditionalOnMissingBean(LedgerWriter.class)
  LedgerWriter mockLedgerWriter() {
    return new MockLedgerWriter();
  }
}
