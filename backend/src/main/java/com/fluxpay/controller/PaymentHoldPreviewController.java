package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.HoldPreviewRequest;
import com.fluxpay.dto.HoldPreviewResponse;
import com.fluxpay.service.PaymentHoldPreviewService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentHoldPreviewController {
  private final PaymentHoldPreviewService preview;

  public PaymentHoldPreviewController(PaymentHoldPreviewService preview) {
    this.preview = preview;
  }

  @PostMapping("/hold-preview")
  public ApiResponse<HoldPreviewResponse> preview(
      @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody HoldPreviewRequest request) {
    return new ApiResponse<>(MDC.get("correlationId"), preview.preview(user.userId(), request));
  }
}
