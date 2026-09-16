package com.fluxpay.m5.infrastructure.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.m5.domain.M5EmbeddingException;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Ollama {@code /api/embed} adapter for the M5 policy corpus. */
public class OllamaEmbeddingAdapter implements M5EmbeddingPort {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final String QUERY_INSTRUCTION =
      "Instruct: Given a financial-compliance question, retrieve relevant policy passages.\nQuery: ";

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final URI endpoint;
  private final String model;
  private final int dimensions;

  public OllamaEmbeddingAdapter(
      HttpClient client, ObjectMapper objectMapper, String baseUrl, String model, int dimensions) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Ollama base URL is required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("Ollama embedding model is required");
    }
    if (dimensions <= 0) {
      throw new IllegalArgumentException("Ollama embedding dimensions must be positive");
    }
    this.client = client;
    this.objectMapper = objectMapper;
    this.endpoint = URI.create(baseUrl.replaceFirst("/+$", "") + "/api/embed");
    this.model = model;
    this.dimensions = dimensions;
  }

  @Override
  public float[] embedDocument(String document) {
    return embed(requireText(document, "document"));
  }

  @Override
  public float[] embedQuery(String query) {
    return embed(QUERY_INSTRUCTION + requireText(query, "query"));
  }

  private float[] embed(String input) {
    try {
      var requestBody = objectMapper.createObjectNode();
      requestBody.put("model", model);
      requestBody.put("input", input);
      requestBody.put("truncate", false);
      requestBody.put("dimensions", dimensions);

      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(REQUEST_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new M5EmbeddingException(
            "Ollama embedding provider returned HTTP " + response.statusCode());
      }
      return parseEmbedding(response.body());
    } catch (M5EmbeddingException exception) {
      throw exception;
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new M5EmbeddingException("Ollama embedding provider is unavailable", exception);
    }
  }

  private float[] parseEmbedding(String body) throws Exception {
    JsonNode vectors = objectMapper.readTree(body).path("embeddings");
    if (!vectors.isArray() || vectors.size() != 1 || !vectors.get(0).isArray()) {
      throw new M5EmbeddingException("Ollama response must contain exactly one embedding vector");
    }
    JsonNode vector = vectors.get(0);
    if (vector.size() != dimensions) {
      throw new M5EmbeddingException(
          "Ollama embedding dimension must be " + dimensions + " but was " + vector.size());
    }
    float[] result = new float[dimensions];
    for (int index = 0; index < dimensions; index++) {
      JsonNode value = vector.get(index);
      if (!value.isNumber()) {
        throw new M5EmbeddingException("Ollama embedding contains a nonnumeric value");
      }
      float component = value.floatValue();
      if (!Float.isFinite(component)) {
        throw new M5EmbeddingException("Ollama embedding contains a nonfinite value");
      }
      result[index] = component;
    }
    return result;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Ollama embedding " + name + " is required");
    }
    return value;
  }
}
