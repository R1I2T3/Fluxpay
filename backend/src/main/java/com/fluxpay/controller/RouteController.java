package com.fluxpay.controller;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.RouteCatalogService;
import com.fluxpay.service.RouteReliabilityService;
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

  private RouteApi.RouteEntry toEntry(TransferRoute route) {
    RouteReliabilityService.RouteReliability metric = catalog.metricFor(route);
    return toEntry(route, metric);
  }

  static RouteApi.RouteEntry toEntry(
      TransferRoute route, RouteReliabilityService.RouteReliability metric) {
    return new RouteApi.RouteEntry(
        route.getId().toString(),
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
        metric == null ? 0L : metric.completedCount(),
        metric == null ? 0L : metric.completedCount() + metric.failedCount());
  }

  private static RouteApi.RecommendResponse toResponse(
      String paymentId, RoutePreference preference, RouteRecommendation recommendation) {
    String recommendedId = recommendation.recommended().quote().route().getId().toString();
    List<RouteApi.Quote> quotes =
        recommendation.quotes().stream()
            .map(
                ranked -> {
                  var quote = ranked.quote();
                  TransferRoute route = quote.route();
                  return new RouteApi.Quote(
                      route.getId().toString(),
                      route.getRouteCode(),
                      route.getName(),
                      route.getProvider().getId().toString(),
                      route.getProviderName(),
                      quote.marketRate(),
                      quote.offeredRate(),
                      quote.feeAmount(),
                      quote.recipientAmount(),
                      route.getEstimatedMinutes(),
                      quote.effectiveReliability(),
                      ranked.score(),
                      ranked.position(),
                      route.getId().toString().equals(recommendedId));
                })
            .toList();
    return new RouteApi.RecommendResponse(
        paymentId, recommendedId, recommendation.reason(), quotes);
  }
}
