package com.fluxpay.m5.infrastructure.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.m5.api.CopilotSource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaChatAdapterTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private HttpServer server;
  private OllamaChatAdapter adapter;

  @BeforeEach
  void startFixture() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = "{\"message\":{\"content\":\"Review the payment before release.\"}}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    adapter = new OllamaChatAdapter(HttpClient.newHttpClient(), objectMapper, "http://127.0.0.1:" + server.getAddress().getPort(), "qwen3:4b", 0.2d);
  }

  @AfterEach
  void stopFixture() {
    server.stop(0);
  }

  @Test
  void sendsGroundedNonStreamingRequest() throws Exception {
    String answer = adapter.answer("Can we release it?", List.of(new CopilotSource(UUID.randomUUID(), "High Value", 1, "Payments require review.")));

    assertThat(answer).isEqualTo("Review the payment before release.");
    JsonNode request = objectMapper.readTree(requestBody.get());
    assertThat(request.path("model").asText()).isEqualTo("qwen3:4b");
    assertThat(request.path("stream").asBoolean()).isFalse();
    assertThat(request.toString()).contains("Payments require review.").contains("Can we release it?");
  }

  @Test
  void appliesTheConfiguredChatDeadline() throws Exception {
    server.stop(0);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/chat",
        exchange -> {
          try {
            Thread.sleep(100);
            exchange.sendResponseHeaders(200, 0);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    adapter =
        new OllamaChatAdapter(
            HttpClient.newHttpClient(),
            objectMapper,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "qwen3:4b",
            0.2d,
            Duration.ofMillis(20));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> adapter.answer("Can we release it?", List.of()))
        .isInstanceOf(com.fluxpay.m5.domain.M5ChatException.class);
  }
}
