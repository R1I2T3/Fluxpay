package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.contracts.LedgerWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockLedgerWriterConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withPropertyValues("spring.profiles.active=mock")
          .withUserConfiguration(MockLedgerWriterConfiguration.class);

  @Test
  void providesTheMockProfileFallbackLedgerWriter() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(LedgerWriter.class));
  }
}
