package com.fluxpay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.dto.FxSnapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

public class FrankfurterFxProvider implements FxSnapshotSource {
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient client;
  private final ObjectMapper objectMapper;
  private final String providerUrl;
  private final Clock clock;

  public FrankfurterFxProvider(
      HttpClient client, ObjectMapper objectMapper, String providerUrl, Clock clock) {
    this.client = client;
    this.objectMapper = objectMapper;
    this.providerUrl = providerUrl;
    this.clock = clock;
  }

  @Override
  public FxSnapshot fetch(String from, String to) {
    try {
      String separator = providerUrl.contains("?") ? "&" : "?";
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(providerUrl + separator + "from=" + from + "&to=" + to))
              .timeout(REQUEST_TIMEOUT)
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new FxUnavailableException("FX provider returned HTTP " + response.statusCode());
      }
      JsonNode body = objectMapper.readTree(response.body());
      if (!from.equals(body.path("base").asText())) {
        throw new FxUnavailableException("FX provider returned a different base currency");
      }
      JsonNode rateNode = body.path("rates").path(to);
      if (!rateNode.isNumber()) {
        throw new FxUnavailableException("FX provider response has no requested rate");
      }
      BigDecimal rate = rateNode.decimalValue();
      if (rate.signum() <= 0) {
        throw new FxUnavailableException("FX provider returned a nonpositive rate");
      }
      return new FxSnapshot(from, to, rate, clock.instant(), false, false);
    } catch (FxUnavailableException exception) {
      throw exception;
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new FxUnavailableException(exception);
    }
  }
}
