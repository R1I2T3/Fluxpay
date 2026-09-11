package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.KycAdminRow;
import com.fluxpay.dto.KycReviewRequest;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.service.KycService;
import com.fluxpay.service.M1KycException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/kyc")
public class AdminKycController {
  private final KycService kycService;

  public AdminKycController(KycService kycService) {
    this.kycService = kycService;
  }

  @GetMapping("/applications")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<List<KycAdminRow>> listApplications(
      @RequestParam(defaultValue = "PENDING") String status) {
    return envelope(kycService.listForAdmin(parseStatus(status)));
  }

  @PutMapping("/applications/{id}/approve")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<KycStatusResponse> approve(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable UUID id,
      @Valid @RequestBody KycReviewRequest request) {
    return envelope(kycService.approve(currentUser.userId(), id, request));
  }

  @PutMapping("/applications/{id}/reject")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<KycStatusResponse> reject(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable UUID id,
      @Valid @RequestBody KycReviewRequest request) {
    return envelope(kycService.reject(currentUser.userId(), id, request));
  }

  private KycStatus parseStatus(String value) {
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if ("ALL".equals(normalized)) {
      return null;
    }
    try {
      return KycStatus.valueOf(normalized);
    } catch (IllegalArgumentException exception) {
      throw new M1KycException(M1KycException.VALIDATION, "unsupported KYC status filter");
    }
  }

  private <T> ApiResponse<T> envelope(T response) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(correlationId == null ? "none" : correlationId, response);
  }
}
