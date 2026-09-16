package com.fluxpay.m5.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.m5.application.M5PolicyChunker;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.domain.M5ChatPort;
import com.fluxpay.m5.infrastructure.ollama.OllamaChatAdapter;
import com.fluxpay.m5.infrastructure.ollama.OllamaEmbeddingAdapter;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the production Ollama embedding adapter used by M5 indexing and policy retrieval. */
@Configuration
@EnableConfigurationProperties({M5VectorProperties.class, M5CopilotProperties.class})
public class M5VectorConfiguration {
  @Bean
  M5PolicyChunker m5PolicyChunker() {
    return new M5PolicyChunker();
  }

  @Bean
  M5EmbeddingPort m5EmbeddingPort(M5VectorProperties properties, ObjectMapper objectMapper) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return new OllamaEmbeddingAdapter(
        client,
        objectMapper,
        properties.ollamaBaseUrl(),
        properties.embeddingModel(),
        properties.dimensions());
  }

  @Bean
  M5ChatPort m5ChatPort(
      M5VectorProperties vectorProperties,
      M5CopilotProperties copilotProperties,
      ObjectMapper objectMapper) {
    return new OllamaChatAdapter(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        objectMapper,
        vectorProperties.ollamaBaseUrl(),
        copilotProperties.chatModel(),
        copilotProperties.chatTemperature(),
        Duration.ofSeconds(copilotProperties.chatTimeoutSeconds()));
  }
}
