package com.fluxpay.controller;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.dto.RoutePreference;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.service.ForbiddenException;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.RouteAdminAuthorizer;
import com.fluxpay.service.RouteCatalogService;
import com.fluxpay.service.RouteMetrics;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RouteController {

  private final PaymentReader reader;
  private final RouteCatalogService catalog;
  private final RouteAdminAuthorizer authorizer;

  public RouteController(
      PaymentReader reader, RouteCatalogService catalog, RouteAdminAuthorizer authorizer) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
  }

  @GetMapping("/routes")
  public ApiResponse<RouteApi.RouteListResponse> list(HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    List<RouteApi.RouteEntry> entries = catalog.listRoutes().stream().map(this::toEntry).toList();
    return new ApiResponse<>(cid, new RouteApi.RouteListResponse(entries));
  }

  @PostMapping("/payments/{paymentId}/recommend-route")
  public ApiResponse<RouteApi.RecommendResponse> recommend(
      @PathVariable String paymentId,
      @RequestBody(required = false) RouteApi.RecommendRequest body,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    PaymentSnapshot payment = reader.get(paymentId);
    if (!authorizer.isOwner(ControllerSupport.currentUser(), payment)) {
      throw new ForbiddenException("user is not the owner of payment " + paymentId);
    }
    RoutePreference preference =
        body != null && body.preference() != null ? body.preference() : RoutePreference.BALANCED;
    RouteRecommendation recommendation = catalog.recommend(paymentId, preference, cid);
    return new ApiResponse<>(cid, toResponse(paymentId, preference, recommendation));
  }

  private RouteApi.RouteEntry toEntry(PayoutRoute route) {
    RouteMetrics.RouteMetric metric = catalog.metricFor(route.getId());
    return toEntry(route, metric);
  }

  static RouteApi.RouteEntry toEntry(PayoutRoute route, RouteMetrics.RouteMetric metric) {
    return new RouteApi.RouteEntry(
        route.getId(),
        route.getRouteCode(),
        route.getName(),
        route.getProviderName(),
        route.getRouteType(),
        route.getBaseFee(),
        route.getFxSpreadPercentage(),
        route.getEstimatedMinutes(),
        route.getSuccessRate(),
        route.isActive(),
        route.getVersion() == null ? 0L : route.getVersion(),
        metric == null ? 0L : metric.successCount(),
        metric == null ? 0L : metric.totalCount());
  }

  private static RouteApi.RecommendResponse toResponse(
      String paymentId, RoutePreference preference, RouteRecommendation recommendation) {
    String recommendedId = recommendation.recommended().getId();
    List<RouteApi.Quote> quotes =
        recommendation.quotes().stream()
            .map(
                quote ->
                    new RouteApi.Quote(
                        quote.route().getId(),
                        quote.route().getName(),
                        quote.marketRate(),
                        quote.offeredRate(),
                        quote.route().getBaseFee(),
                        quote.recipientAmount(),
                        quote.route().getEstimatedMinutes(),
                        quote.route().getId().equals(recommendedId)))
            .toList();
    String reason = "preference " + preference + " over " + quotes.size() + " active routes";
    return new RouteApi.RecommendResponse(paymentId, recommendedId, reason, quotes);
  }
}
