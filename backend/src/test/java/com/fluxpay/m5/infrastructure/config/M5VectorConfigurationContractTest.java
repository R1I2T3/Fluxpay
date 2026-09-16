package com.fluxpay.m5.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class M5VectorConfigurationContractTest {

  @Test
  void keepsChunkerVersionInTheValidatedVectorConfiguration() throws IOException {
    try (var input = getClass().getResourceAsStream("/application.yml")) {
      assertThat(input).isNotNull();
      String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      boolean inVectorBlock = false;
      boolean hasChunkerVersion = false;
      for (String line : yaml.split("\\R")) {
        if (line.equals("    vector:")) {
          inVectorBlock = true;
        } else if (line.equals("    copilot:")) {
          inVectorBlock = false;
        } else if (inVectorBlock && line.trim().startsWith("chunker-version:")) {
          hasChunkerVersion = true;
        }
      }

      assertThat(hasChunkerVersion).isTrue();
    }
  }
}
