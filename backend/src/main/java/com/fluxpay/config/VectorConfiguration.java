package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.ollama.OllamaChatAdapter;
import com.fluxpay.adapter.ollama.OllamaEmbeddingAdapter;
import com.fluxpay.common.contracts.ChatPort;
import com.fluxpay.common.contracts.EmbeddingPort;
import com.fluxpay.service.PolicyChunker;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the production Ollama embedding adapter used by indexing and policy retrieval. */
@Configuration
@EnableConfigurationProperties({VectorProperties.class, CopilotProperties.class})
public class VectorConfiguration {
  @Bean
  PolicyChunker policyChunker() {
    return new PolicyChunker();
  }

  @Bean
  EmbeddingPort embeddingPort(VectorProperties properties, ObjectMapper objectMapper) {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    return new OllamaEmbeddingAdapter(
        client,
        objectMapper,
        properties.ollamaBaseUrl(),
        properties.embeddingModel(),
        properties.dimensions());
  }

  @Bean
  ChatPort chatPort(
      VectorProperties vectorProperties,
      CopilotProperties copilotProperties,
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
