package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    assertEquals(new java.math.BigDecimal("83.5"), snapshot.rate());
    assertEquals(NOW, snapshot.fetchedAt());
    ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
    org.mockito.Mockito.verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertEquals(
        "https://fx.example.test/latest?from=USD&to=INR", request.getValue().uri().toString());
    assertEquals(Duration.ofSeconds(3), request.getValue().timeout().orElseThrow());
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
