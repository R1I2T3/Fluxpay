package com.fluxpay.service;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls a locally running Ollama instance's embeddings endpoint for real semantic embeddings --
 * no API key, no external network call, everything stays on the developer's machine. Active when
 * {@code fluxpay.embedding-mode=ollama}.
 *
 * <p><b>Do you need a bigger model?</b> No. The default model, {@code nomic-embed-text}, is a
 * small (~274MB) embedding-only model that produces 768-dimension vectors and is purpose-built
 * for retrieval. Embedding quality does not scale with parameter count the way chat quality
 * does -- {@code nomic-embed-text} outperforms much larger general-purpose chat models on
 * retrieval benchmarks precisely because it was trained for this one job. Pull it once with
 * {@code ollama pull nomic-embed-text}; nothing bigger is needed for the vector-search side of
 * this feature. (A bigger *chat* model only matters if you also turn on generated -- as opposed
 * to extractive -- Copilot answers; see {@link OllamaAnswerGenerator}.)
 *
 * <p>{@code nomic-embed-text} was trained with asymmetric task prefixes: text being indexed
 * should be prefixed with {@code "search_document: "} and text being searched for should be
 * prefixed with {@code "search_query: "}. Using the matching prefix measurably improves
 * retrieval quality over embedding raw text, so {@link #embedDocument(String)} and {@link
 * #embedQuery(String)} apply them automatically; {@link #embed(String)} defaults to the document
 * prefix so any caller using the plain interface still gets a sensible embedding.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Component
@ConditionalOnProperty(name = "fluxpay.embedding-mode", havingValue = "ollama")
public class OllamaEmbeddingProvider implements EmbeddingProvider {

  private final RestClient restClient;
  private final String model;
  private final int dimensions;

  public OllamaEmbeddingProvider(
      @Value("${fluxpay.embedding-api-url:http://localhost:11434}") String baseUrl,
      @Value("${fluxpay.embedding-model:nomic-embed-text}") String model,
      @Value("${fluxpay.embedding-dimensions:768}") int dimensions) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(5_000);
    // local CPU inference (especially a cold model that isn't resident in memory yet) can take
    // a few seconds per call -- give it real headroom instead of failing indexing halfway through.
    requestFactory.setReadTimeout(60_000);
    this.restClient =
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.model = model;
    this.dimensions = dimensions;
  }

  @Override
  public float[] embed(String text) {
    return embedDocument(text);
  }

  @Override
  public float[] embedDocument(String text) {
    return call("search_document: " + text);
  }

  @Override
  public float[] embedQuery(String text) {
    return call("search_query: " + text);
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  @SuppressWarnings("unchecked")
  private float[] call(String prompt) {
    Map<String, Object> requestBody = Map.of("model", model, "prompt", prompt);
    Map<String, Object> response;
    try {
      response =
          restClient
              .post()
              .uri("/api/embeddings")
              .contentType(MediaType.APPLICATION_JSON)
              .body(requestBody)
              .retrieve()
              .body(Map.class);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not reach Ollama at the configured fluxpay.embedding-api-url. Is `ollama serve` "
              + "running? (Windows/Mac app installs run this automatically in the background.)",
          e);
    }
    if (response == null || !response.containsKey("embedding")) {
      throw new IllegalStateException(
          "Ollama returned no embedding for model '"
              + model
              + "'. Run `ollama pull "
              + model
              + "` and try again.");
    }
    List<Double> embedding = (List<Double>) response.get("embedding");
    if (embedding.size() != dimensions) {
      throw new IllegalStateException(
          "Model '"
              + model
              + "' returned a "
              + embedding.size()
              + "-dimension vector but fluxpay.embedding-dimensions is configured as "
              + dimensions
              + ". Update the property (and the policy_chunks.embedding VECTOR column width) to "
              + "match the model you are actually using.");
    }
    float[] result = new float[embedding.size()];
    for (int i = 0; i < embedding.size(); i++) {
      result[i] = embedding.get(i).floatValue();
    }
    return result;
  }
}
