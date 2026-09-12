package com.fluxpay.dto;

import java.math.BigDecimal;
import java.util.List;

/** REST shapes for the route catalog. Domain entities are never serialized directly. */
public final class RouteApi {
  private RouteApi() {}

  public record RouteListResponse(List<RouteEntry> routes) {
    public RouteListResponse {
      routes = routes == null ? List.of() : List.copyOf(routes);
    }
  }

  public record RouteEntry(
      String routeId,
      String routeCode,
      String routeName,
      String providerName,
      String routeType,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal successRate,
      boolean active,
      long version,
      long successCount,
      long totalAttempts) {}

  public record RecommendRequest(RoutePreference preference) {}

  public record RecommendResponse(
      String paymentId,
      String recommendedRouteId,
      String recommendationReason,
      List<Quote> quotes) {
    public RecommendResponse {
      quotes = quotes == null ? List.of() : List.copyOf(quotes);
    }
  }

  public record Quote(
      String routeId,
      String routeName,
      BigDecimal marketRate,
      BigDecimal offeredRate,
      BigDecimal feeAmount,
      BigDecimal recipientAmount,
      int estimatedMinutes,
      boolean recommended) {}

  public record Update(
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal successRate,
      boolean active,
      long version) {}
}
