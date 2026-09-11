package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.service.DemoFundingService;
import com.fluxpay.service.WalletConversionService;
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
  private final WalletConversionService conversion;

  public WalletController(DemoFundingService funding, WalletConversionService conversion) {
    this.funding = funding;
    this.conversion = conversion;
  }

  @PostMapping("/convert")
  public ApiResponse<WalletConvertResponse> convert(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody WalletConvertRequest request) {
    if (currentUser == null) {
      throw new SecurityException("Authentication is required");
    }
    WalletConvertResponse response =
        conversion.convert(currentUser.userId(), request, idempotencyKey);
    return new ApiResponse<>(correlationId(), response);
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
