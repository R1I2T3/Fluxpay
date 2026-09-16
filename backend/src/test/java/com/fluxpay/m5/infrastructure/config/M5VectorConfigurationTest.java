package com.fluxpay.m5.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.infrastructure.ollama.OllamaEmbeddingAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class M5VectorConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(M5VectorConfiguration.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withPropertyValues(
              "fluxpay.m5.vector.ollama-base-url=http://127.0.0.1:11434",
              "fluxpay.m5.vector.embedding-model=qwen3-embedding:4b",
              "fluxpay.m5.vector.dimensions=1536",
              "fluxpay.m5.vector.embedding-space-id=ollama/qwen3-embedding:4b/1536",
              "fluxpay.m5.vector.chunker-version=m5-sentence-v1",
              "fluxpay.m5.copilot.chat-model=qwen3:4b",
              "fluxpay.m5.copilot.chat-temperature=0.2",
              "fluxpay.m5.copilot.max-distance=0.65",
              "fluxpay.m5.copilot.chat-timeout-seconds=90");

  @Test
  void exposesOnlyTheConcreteOllamaEmbeddingPort() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(M5EmbeddingPort.class);
          assertThat(context.getBean(M5EmbeddingPort.class)).isInstanceOf(OllamaEmbeddingAdapter.class);
        });
  }
}
