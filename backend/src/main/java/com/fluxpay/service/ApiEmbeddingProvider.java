package com.fluxpay.service;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls an OpenAI-compatible {@code /embeddings} endpoint for real semantic embeddings. Only
 * active when {@code fluxpay.embedding-mode=api} (and {@code EMBEDDING_API_URL} /
 * {@code EMBEDDING_API_KEY} are set) -- {@link MockEmbeddingProvider} is the safe default so the
 * app runs with zero external config out of the box.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Component
@ConditionalOnProperty(name = "fluxpay.embedding-mode", havingValue = "api")
public class ApiEmbeddingProvider implements EmbeddingProvider {

  private final RestClient restClient;
  private final String apiKey;
  private final String model;
  private final int dimensions;

  public ApiEmbeddingProvider(
      @Value("${fluxpay.embedding-api-url}") String apiUrl,
      @Value("${fluxpay.embedding-api-key}") String apiKey,
      @Value("${fluxpay.embedding-model:text-embedding-3-small}") String model,
      @Value("${fluxpay.embedding-dimensions:1536}") int dimensions) {
    this.restClient = RestClient.builder().baseUrl(apiUrl).build();
    this.apiKey = apiKey;
    this.model = model;
    this.dimensions = dimensions;
  }

  @Override
  @SuppressWarnings("unchecked")
  public float[] embed(String text) {
    Map<String, Object> requestBody = Map.of("model", model, "input", text);
    Map<String, Object> response =
        restClient
            .post()
            .uri("/embeddings")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(Map.class);
    if (response == null || !response.containsKey("data")) {
      throw new IllegalStateException("Embedding API returned no data for request");
    }
    List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
    List<Double> embedding = (List<Double>) data.get(0).get("embedding");
    float[] result = new float[embedding.size()];
    for (int i = 0; i < embedding.size(); i++) {
      result[i] = embedding.get(i).floatValue();
    }
    return result;
  }

  @Override
  public int dimensions() {
    return dimensions;
  }
}
