package com.fluxpay.dto;

import java.util.List;

public record RouteRecommendation(
    RankedRouteQuote recommended, List<RankedRouteQuote> quotes, String reason) {
  public RouteRecommendation {
    quotes = List.copyOf(quotes);
    if (quotes.isEmpty() || quotes.size() > 3 || !quotes.get(0).equals(recommended)) {
      throw new IllegalArgumentException("recommendation requires one to three ranked quotes");
    }
  }
}
