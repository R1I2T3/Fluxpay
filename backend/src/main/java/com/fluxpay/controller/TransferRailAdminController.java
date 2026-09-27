package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.service.RailRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only administrator metadata for installed transfer rails. Rails are code-shipped and
 * trusted; administrators select rail types on providers and routes but never install rail code.
 */
@RestController
@RequestMapping("/api/admin/rail-types")
public class TransferRailAdminController {

  public record RailEntry(
      RailType railType, String displayLabel, Set<DestinationType> supportedDestinations) {
    public RailEntry {
      supportedDestinations =
          supportedDestinations == null || supportedDestinations.isEmpty()
              ? Set.of()
              : EnumSet.copyOf(supportedDestinations);
    }
  }

  public record RailListResponse(List<RailEntry> railTypes) {
    public RailListResponse {
      railTypes = railTypes == null ? List.of() : List.copyOf(railTypes);
    }
  }

  private final RailRegistry rails;
  private final RouteAdminAuthorizer authorizer;

  public TransferRailAdminController(RailRegistry rails, RouteAdminAuthorizer authorizer) {
    this.rails = Objects.requireNonNull(rails, "rails must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<RailListResponse> list(HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("list rail types");
    return new ApiResponse<>(
        cid,
        new RailListResponse(
            rails.all().stream()
                .sorted(Comparator.comparing(rail -> rail.type().name()))
                .map(TransferRailAdminController::toEntry)
                .toList()));
  }

  @GetMapping("/{railType}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<RailEntry> get(@PathVariable String railType, HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("read rail type " + railType);
    return new ApiResponse<>(cid, toEntry(rails.require(parseRailType(railType))));
  }

  private void requireAdmin(String action) {
    if (!authorizer.isAdmin(ControllerSupport.currentUser())) {
      throw new ForbiddenException("admin role required to " + action);
    }
  }

  private static RailEntry toEntry(TransferRail rail) {
    return new RailEntry(rail.type(), rail.type().displayLabel(), rail.supportedDestinations());
  }

  private static RailType parseRailType(String value) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_TRANSFER_ROUTE", "railType must not be blank");
    }
    try {
      return RailType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_TRANSFER_ROUTE", "unknown railType " + value.trim());
    }
  }
}
