package com.fluxpay.controller;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.dto.DeletionResult;
import com.fluxpay.dto.TransferRouteApi;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.service.RouteReliabilityService;
import com.fluxpay.service.TransferRouteService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator CRUD for transfer routes. Route codes are immutable; the provider/destination
 * binding is immutable once the route has been used.
 */
@RestController
@RequestMapping("/api/admin/routes")
public class TransferRouteAdminController {

  private final TransferRouteService service;
  private final RouteReliabilityService reliability;
  private final RouteAdminAuthorizer authorizer;

  public TransferRouteAdminController(
      TransferRouteService service,
      RouteReliabilityService reliability,
      RouteAdminAuthorizer authorizer) {
    this.service = Objects.requireNonNull(service, "service must not be null");
    this.reliability = Objects.requireNonNull(reliability, "reliability must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferRouteApi.RouteListResponse> list(HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("list routes");
    List<TransferRoute> routes = service.list();
    Map<UUID, RouteReliabilityService.RouteReliability> stats = reliability.effectiveFor(routes);
    return new ApiResponse<>(
        cid,
        new TransferRouteApi.RouteListResponse(
            routes.stream().map(route -> toEntry(route, stats)).toList()));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferRouteApi.RouteEntry> get(
      @PathVariable String id, HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("read route " + id);
    return new ApiResponse<>(cid, toEntry(service.get(parseId(id))));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferRouteApi.RouteEntry> create(
      @RequestBody(required = false) TransferRouteApi.CreateRouteRequest body,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("create route");
    if (body == null) {
      throw invalid("request body must not be null");
    }
    DestinationType destinationType = parseDestinationType(body.destinationType());
    BigDecimal minimum =
        optionalPositiveAmount(body.minimumRecipientAmount(), "minimumRecipientAmount");
    BigDecimal maximum =
        optionalPositiveAmount(body.maximumRecipientAmount(), "maximumRecipientAmount");
    requireMaxAtLeastMin(minimum, maximum);
    TransferRoute route =
        service.create(
            new TransferRouteService.CreateRoute(
                parseProviderId(body.providerId()),
                routeCode(body.routeCode()),
                requireText(body.name(), "name"),
                destinationType,
                normalizeCountry(body.destinationCountry(), destinationType),
                normalizeCurrency(body.payoutCurrency()),
                requireNonnegative(body.baseFee(), "baseFee"),
                requireNonnegative(body.fxSpreadPercentage(), "fxSpreadPercentage"),
                requirePositiveEta(body.estimatedMinutes()),
                requireReliability(body.configuredSuccessRate()),
                minimum,
                maximum,
                requireActive(body.active())));
    return new ApiResponse<>(cid, toEntry(route));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferRouteApi.RouteEntry> update(
      @PathVariable String id,
      @RequestBody(required = false) TransferRouteApi.UpdateRouteRequest body,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("update route " + id);
    if (body == null) {
      throw invalid("request body must not be null");
    }
    DestinationType destinationType = parseDestinationType(body.destinationType());
    BigDecimal minimum = optionalPositiveAmount(body.minimumRecipientAmount(), "minimum");
    BigDecimal maximum = optionalPositiveAmount(body.maximumRecipientAmount(), "maximum");
    requireMaxAtLeastMin(minimum, maximum);
    TransferRoute route =
        service.update(
            parseId(id),
            new TransferRouteService.UpdateRoute(
                parseProviderId(body.providerId()),
                requireText(body.name(), "name"),
                destinationType,
                normalizeCountry(body.destinationCountry(), destinationType),
                normalizeCurrency(body.payoutCurrency()),
                requireNonnegative(body.baseFee(), "baseFee"),
                requireNonnegative(body.fxSpreadPercentage(), "fxSpreadPercentage"),
                requirePositiveEta(body.estimatedMinutes()),
                requireReliability(body.configuredSuccessRate()),
                minimum,
                maximum,
                requireActive(body.active()),
                requireVersion(body.version())));
    return new ApiResponse<>(cid, toEntry(route));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<DeletionResult> delete(
      @PathVariable String id,
      @RequestParam(value = "version", required = false) Long version,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("delete route " + id);
    return new ApiResponse<>(cid, service.delete(parseId(id), requireVersion(version)));
  }

  private TransferRouteApi.RouteEntry toEntry(TransferRoute route) {
    RouteReliabilityService.RouteReliability stat =
        reliability.effectiveFor(List.of(route)).get(route.getId());
    return TransferRouteApi.toEntry(
        route, stat.effectiveReliability(), stat.completedCount(), stat.failedCount());
  }

  private static TransferRouteApi.RouteEntry toEntry(
      TransferRoute route, Map<UUID, RouteReliabilityService.RouteReliability> stats) {
    RouteReliabilityService.RouteReliability stat = stats.get(route.getId());
    return TransferRouteApi.toEntry(
        route, stat.effectiveReliability(), stat.completedCount(), stat.failedCount());
  }

  private void requireAdmin(String action) {
    if (!authorizer.isAdmin(ControllerSupport.currentUser())) {
      throw new ForbiddenException("admin role required to " + action);
    }
  }

  private static UUID parseId(String id) {
    if (id == null || id.isBlank()) {
      throw invalid("route id must not be blank");
    }
    try {
      return UUID.fromString(id.trim());
    } catch (IllegalArgumentException e) {
      throw invalid("route id must be a UUID");
    }
  }

  private static UUID parseProviderId(String providerId) {
    if (providerId == null || providerId.isBlank()) {
      throw invalid("providerId must not be null");
    }
    try {
      return UUID.fromString(providerId.trim());
    } catch (IllegalArgumentException e) {
      throw invalid("providerId must be a UUID");
    }
  }

  private static String routeCode(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("routeCode must not be blank");
    }
    try {
      return TransferProvider.normalizeCode(value);
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw invalid(name + " must not be blank");
    }
    return value.trim();
  }

  private static DestinationType parseDestinationType(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("destinationType must not be null");
    }
    try {
      return DestinationType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw invalid("unknown destinationType " + value.trim());
    }
  }

  private static String normalizeCountry(String country, DestinationType destinationType) {
    if (country == null || country.isBlank()) {
      if (destinationType == DestinationType.EXTERNAL_ACCOUNT) {
        throw invalid("destinationCountry is required for external routes");
      }
      return null;
    }
    String normalized = country.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{2}")) {
      throw invalid("destinationCountry must be ISO-3166 alpha-2");
    }
    return normalized;
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      throw invalid("payoutCurrency must not be blank");
    }
    String normalized = currency.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{3}")) {
      throw invalid("payoutCurrency must be ISO-4217");
    }
    return normalized;
  }

  private static BigDecimal requireNonnegative(BigDecimal value, String name) {
    if (value == null) {
      throw invalid(name + " must not be null");
    }
    if (value.signum() < 0) {
      throw invalid(name + " must be nonnegative");
    }
    return value;
  }

  private static int requirePositiveEta(Integer estimatedMinutes) {
    if (estimatedMinutes == null) {
      throw invalid("estimatedMinutes must not be null");
    }
    if (estimatedMinutes <= 0) {
      throw invalid("estimatedMinutes must be positive");
    }
    return estimatedMinutes;
  }

  private static BigDecimal requireReliability(BigDecimal configuredSuccessRate) {
    if (configuredSuccessRate == null) {
      throw invalid("configuredSuccessRate must not be null");
    }
    if (configuredSuccessRate.signum() < 0
        || configuredSuccessRate.compareTo(new BigDecimal("100")) > 0) {
      throw invalid("configuredSuccessRate must be between 0 and 100");
    }
    return configuredSuccessRate;
  }

  private static BigDecimal optionalPositiveAmount(BigDecimal value, String name) {
    if (value == null) {
      return null;
    }
    if (value.signum() <= 0) {
      throw invalid(name + " must be positive");
    }
    return value;
  }

  private static void requireMaxAtLeastMin(BigDecimal minimum, BigDecimal maximum) {
    if (minimum != null && maximum != null && maximum.compareTo(minimum) < 0) {
      throw invalid("maximumRecipientAmount must be >= minimumRecipientAmount");
    }
  }

  private static boolean requireActive(Boolean active) {
    if (active == null) {
      throw invalid("active must not be null");
    }
    return active;
  }

  private static Long requireVersion(Long version) {
    if (version == null) {
      throw invalid("version must not be null");
    }
    return version;
  }

  private static BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER_ROUTE", message);
  }
}
