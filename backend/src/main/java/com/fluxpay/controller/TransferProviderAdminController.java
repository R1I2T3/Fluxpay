package com.fluxpay.controller;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.DeletionResult;
import com.fluxpay.dto.TransferProviderApi;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.service.TransferProviderService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
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
 * Administrator CRUD for transfer providers. Administrators configure provider identity and rail
 * selection; they never configure endpoints or secrets.
 */
@RestController
@RequestMapping("/api/admin/providers")
public class TransferProviderAdminController {

  private final TransferProviderService service;
  private final RouteAdminAuthorizer authorizer;

  public TransferProviderAdminController(
      TransferProviderService service, RouteAdminAuthorizer authorizer) {
    this.service = Objects.requireNonNull(service, "service must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
  }

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferProviderApi.ProviderListResponse> list(HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("list providers");
    return new ApiResponse<>(
        cid,
        new TransferProviderApi.ProviderListResponse(
            service.list().stream().map(TransferProviderApi::toEntry).toList()));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferProviderApi.ProviderEntry> get(
      @PathVariable String id, HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("read provider " + id);
    return new ApiResponse<>(cid, TransferProviderApi.toEntry(service.get(parseId(id))));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferProviderApi.ProviderEntry> create(
      @RequestBody(required = false) TransferProviderApi.CreateProviderRequest body,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("create provider");
    if (body == null) {
      throw invalid("request body must not be null");
    }
    TransferProvider provider =
        service.create(
            new TransferProviderService.CreateProvider(
                providerCode(body.providerCode()),
                requireText(body.providerName(), "providerName"),
                parseRailType(body.railType()),
                requireActive(body.active())));
    return new ApiResponse<>(cid, TransferProviderApi.toEntry(provider));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<TransferProviderApi.ProviderEntry> update(
      @PathVariable String id,
      @RequestBody(required = false) TransferProviderApi.UpdateProviderRequest body,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("update provider " + id);
    if (body == null) {
      throw invalid("request body must not be null");
    }
    TransferProvider provider =
        service.update(
            parseId(id),
            new TransferProviderService.UpdateProvider(
                requireText(body.providerName(), "providerName"),
                parseRailType(body.railType()),
                requireActive(body.active()),
                requireVersion(body.version())));
    return new ApiResponse<>(cid, TransferProviderApi.toEntry(provider));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<DeletionResult> delete(
      @PathVariable String id,
      @RequestParam(value = "version", required = false) Long version,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    requireAdmin("delete provider " + id);
    return new ApiResponse<>(cid, service.delete(parseId(id), requireVersion(version)));
  }

  private void requireAdmin(String action) {
    if (!authorizer.isAdmin(ControllerSupport.currentUser())) {
      throw new ForbiddenException("admin role required to " + action);
    }
  }

  private static UUID parseId(String id) {
    if (id == null || id.isBlank()) {
      throw invalid("provider id must not be blank");
    }
    try {
      return UUID.fromString(id.trim());
    } catch (IllegalArgumentException e) {
      throw invalid("provider id must be a UUID");
    }
  }

  private static String providerCode(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("providerCode must not be blank");
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

  private static RailType parseRailType(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("railType must not be null");
    }
    try {
      return RailType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw invalid("unknown railType " + value.trim());
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
