package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.service.DemoFundingService;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {
  private final DemoFundingService funding;

  public WalletController(DemoFundingService funding) {
    this.funding = funding;
  }

  @PostMapping("/receive-demo")
  public ApiResponse<WalletResponse> receiveDemo(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody WalletReceiveRequest request) {
    if (currentUser == null) {
      throw new SecurityException("Authentication is required");
    }
    WalletResponse response = funding.receiveDemo(currentUser.userId(), request, idempotencyKey);
    return new ApiResponse<>(correlationId(), response);
  }

  private static String correlationId() {
    String value = MDC.get("correlationId");
    return value == null ? "none" : value;
  }
}
