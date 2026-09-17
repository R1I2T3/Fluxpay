package com.fluxpay.adapter.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.exception.EmbeddingException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaEmbeddingAdapterTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private HttpServer server;
  private AtomicReference<String> responseBody;
  private OllamaEmbeddingAdapter adapter;

  @BeforeEach
  void startOllamaFixture() throws Exception {
    responseBody = new AtomicReference<>(embeddingResponse(1536));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/embed",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    adapter =
        new OllamaEmbeddingAdapter(
            HttpClient.newHttpClient(),
            objectMapper,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "qwen3-embedding:4b",
            1536);
  }

  @AfterEach
  void stopOllamaFixture() {
    server.stop(0);
  }

  @Test
  void embedsDocumentsUsingTheConfiguredOllamaModelAndDimensions() throws Exception {
    assertThat(adapter.embedDocument("Payments over $10,000 require review.")).hasSize(1536);

    JsonNode request = objectMapper.readTree(requestBody.get());
    assertThat(request.path("model").asText()).isEqualTo("qwen3-embedding:4b");
    assertThat(request.path("dimensions").asInt()).isEqualTo(1536);
    assertThat(request.path("truncate").asBoolean()).isFalse();
    assertThat(request.path("input").asText()).isEqualTo("Payments over $10,000 require review.");
  }

  @Test
  void embedsQueriesWithARetrievalInstruction() throws Exception {
    assertThat(adapter.embedQuery("When must I escalate a payment?")).hasSize(1536);

    JsonNode request = objectMapper.readTree(requestBody.get());
    assertThat(request.path("input").asText())
        .startsWith(
            "Instruct: Given a financial-compliance question, retrieve relevant policy passages.\nQuery: ")
        .endsWith("When must I escalate a payment?");
  }

  @Test
  void rejectsAnOllamaResponseWithTheWrongVectorDimension() {
    responseBody.set(embeddingResponse(1535));

    assertThatThrownBy(() -> adapter.embedDocument("policy"))
        .isInstanceOf(EmbeddingException.class)
        .hasMessageContaining("1536");
  }

  private static String embeddingResponse(int dimensions) {
    String vector =
        IntStream.range(0, dimensions).mapToObj(index -> "0.125").collect(Collectors.joining(","));
    return "{\"embeddings\":[[" + vector + "]]}";
  }
}
