package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.ollama.OllamaEmbeddingAdapter;
import com.fluxpay.common.contracts.EmbeddingPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class VectorConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(VectorConfiguration.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withPropertyValues(
              "fluxpay.vector.ollama-base-url=http://127.0.0.1:11434",
              "fluxpay.vector.embedding-model=qwen3-embedding:4b",
              "fluxpay.vector.dimensions=1536",
              "fluxpay.vector.embedding-space-id=ollama/qwen3-embedding:4b/1536",
              "fluxpay.vector.chunker-version=m5-sentence-v1",
              "fluxpay.copilot.chat-model=qwen3:4b",
              "fluxpay.copilot.chat-temperature=0.2",
              "fluxpay.copilot.max-distance=0.65",
              "fluxpay.copilot.chat-timeout-seconds=90");

  @Test
  void exposesOnlyTheConcreteOllamaEmbeddingPort() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(EmbeddingPort.class);
          assertThat(context.getBean(EmbeddingPort.class))
              .isInstanceOf(OllamaEmbeddingAdapter.class);
        });
  }
}
