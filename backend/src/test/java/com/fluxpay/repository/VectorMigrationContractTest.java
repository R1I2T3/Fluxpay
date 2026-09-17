package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class VectorMigrationContractTest {

  @Test
  void evolvesTheExistingPolicyCorpusIntoTheOllamaVectorSpace() throws IOException {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V604__m5_ollama_vector_space.sql")) {
      assertThat(input).as("V604 Ollama migration").isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

      assertThat(sql).contains("CREATE TABLE policy_generations").contains("generation_id RAW(16)");
    }
  }
}
