package com.fluxpay.controller;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.RouteApi;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.service.RouteAdminAuthorizer;
import com.fluxpay.service.RouteCatalogService;
import com.fluxpay.service.RouteMetrics;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/routes")
public class RouteAdminController {

  private final RouteCatalogService catalog;
  private final RouteAdminAuthorizer authorizer;

  public RouteAdminController(RouteCatalogService catalog, RouteAdminAuthorizer authorizer) {
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
  }

  @PutMapping("/{routeId}")
  public ApiResponse<RouteApi.RouteEntry> update(
      @PathVariable String routeId,
      @RequestBody RouteApi.Update update,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    if (!authorizer.isAdmin(ControllerSupport.currentUser())) {
      throw new ForbiddenException("admin role required to update route " + routeId);
    }
    PayoutRoute route = catalog.updateRoute(routeId, update);
    RouteMetrics.RouteMetric metric = catalog.metricFor(route.getId());
    return new ApiResponse<>(cid, RouteController.toEntry(route, metric));
  }
}
