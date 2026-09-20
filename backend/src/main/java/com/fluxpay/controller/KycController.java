package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.dto.KycSubmitRequest;
import com.fluxpay.service.KycService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kyc")
public class KycController {
  private final KycService kycService;

  public KycController(KycService kycService) {
    this.kycService = kycService;
  }

  @PostMapping(value = "/applications", consumes = "application/json")
  @io.swagger.v3.oas.annotations.Hidden
  public ResponseEntity<ApiResponse<KycStatusResponse>> submit(
      @AuthenticationPrincipal CurrentUser currentUser,
      @Valid @RequestBody KycSubmitRequest request) {
    throw new com.fluxpay.exception.KycException(
        com.fluxpay.exception.KycException.VALIDATION,
        "Upload the actual document files using multipart/form-data. Metadata-only submissions are no longer accepted.");
  }

  @GetMapping("/my-status")
  public ApiResponse<KycStatusResponse> getMyStatus(
      @AuthenticationPrincipal CurrentUser currentUser) {
    return envelope(kycService.getMyStatus(currentUser.userId()));
  }

  private ApiResponse<KycStatusResponse> envelope(KycStatusResponse response) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(correlationId == null ? "none" : correlationId, response);
  }
}
