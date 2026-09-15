package com.fluxpay.adapter.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.exception.FxUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FrankfurterFxProviderTest {
  private static final Instant NOW = Instant.parse("2026-09-11T01:02:03Z");

  @Test
  @SuppressWarnings("unchecked")
  void fetchesConfiguredPairWithTimeoutAndPreservesFetchTime() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"amount\":1.0,\"base\":\"USD\",\"rates\":{\"INR\":83.5}}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    FrankfurterFxProvider provider =
        new FrankfurterFxProvider(
            client,
            new ObjectMapper(),
            "https://fx.example.test/latest",
            Clock.fixed(NOW, ZoneOffset.UTC));

    var snapshot = provider.fetch("USD", "INR");

    assertEquals(new BigDecimal("83.5"), snapshot.rate());
    assertEquals(NOW, snapshot.fetchedAt());
    ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertEquals(
        "https://fx.example.test/latest?base=USD&symbols=INR", request.getValue().uri().toString());
    assertEquals(Duration.ofSeconds(3), request.getValue().timeout().orElseThrow());
  }

  @Test
  void parsesDocumentedLatestEndpointThroughLocalHttpServer() throws Exception {
    // Mirrors the official v1 docs (https://frankfurter.dev/v1/):
    // GET {base}/latest?base=USD&symbols=INR returns {"base":"USD","rates":{"INR":...}}.
    // A local fixture alone cannot prove the request URI matches the documented endpoint,
    // so this test asserts the exact path and query the adapter sends.
    AtomicReference<String> observedTarget = new AtomicReference<>();
    byte[] body =
        "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-09-11\",\"rates\":{\"INR\":83.5}}"
            .getBytes(StandardCharsets.UTF_8);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/v1/latest",
          exchange -> {
            observedTarget.set(
                exchange.getRequestURI().getPath()
                    + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();

      FrankfurterFxProvider provider =
          new FrankfurterFxProvider(
              HttpClient.newHttpClient(),
              new ObjectMapper(),
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/latest",
              Clock.fixed(NOW, ZoneOffset.UTC));

      var snapshot = provider.fetch("USD", "INR");

      assertEquals("USD", snapshot.from());
      assertEquals("INR", snapshot.to());
      assertEquals(new BigDecimal("83.5"), snapshot.rate());
      assertEquals(NOW, snapshot.fetchedAt());
      assertEquals("/v1/latest?base=USD&symbols=INR", observedTarget.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void rejectsNonpositiveOrMalformedUpstreamRate() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"base\":\"USD\",\"rates\":{\"INR\":0}}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    FrankfurterFxProvider provider =
        new FrankfurterFxProvider(
            client,
            new ObjectMapper(),
            "https://fx.example.test/latest",
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThrows(FxUnavailableException.class, () -> provider.fetch("USD", "INR"));
  }
}
