package com.fluxpay.dto;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.DestinationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** REST shapes for administrator-managed transfer routes. Entities are never serialized. */
public final class TransferRouteApi {
  private TransferRouteApi() {}

  public record RouteListResponse(List<RouteEntry> routes) {
    public RouteListResponse {
      routes = routes == null ? List.of() : List.copyOf(routes);
    }
  }

  public record RouteEntry(
      String id,
      String providerId,
      String routeCode,
      String name,
      DestinationType destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal effectiveSuccessRate,
      long completedCount,
      long failedCount,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      boolean active,
      boolean systemProtected,
      Instant archivedAt,
      long version) {}

  public record CreateRouteRequest(
      String providerId,
      String routeCode,
      String name,
      String destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      Integer estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      Boolean active) {}

  public record UpdateRouteRequest(
      String providerId,
      String name,
      String destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      Integer estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      Boolean active,
      Long version) {}

  public static RouteEntry toEntry(
      TransferRoute route, BigDecimal effectiveSuccessRate, long completedCount, long failedCount) {
    return new RouteEntry(
        route.getId().toString(),
        route.getProvider().getId().toString(),
        route.getRouteCode(),
        route.getName(),
        route.getDestinationType(),
        route.getDestinationCountry(),
        route.getPayoutCurrency(),
        route.getBaseFee(),
        route.getFxSpreadPercentage(),
        route.getEstimatedMinutes(),
        route.configuredSuccessRate(),
        effectiveSuccessRate,
        completedCount,
        failedCount,
        route.minimumRecipientAmount(),
        route.maximumRecipientAmount(),
        route.isActive(),
        route.systemProtected(),
        route.getArchivedAt(),
        route.getVersion() == null ? 0L : route.getVersion());
  }
}
