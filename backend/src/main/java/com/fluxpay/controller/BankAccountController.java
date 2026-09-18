package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.*;
import com.fluxpay.service.BankAccountService;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bank-accounts")
public class BankAccountController {
  private final BankAccountService banks;

  public BankAccountController(BankAccountService banks) {
    this.banks = banks;
  }

  @PostMapping("/link")
  public ApiResponse<BankAccountResponse> link(
      @AuthenticationPrincipal CurrentUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody BankLinkRequest request) {
    requireUser(user);
    return new ApiResponse<>(correlationId(), banks.link(user.userId(), request, key));
  }

  @PostMapping("/{id}/topup")
  public ApiResponse<WalletResponse> topup(
      @AuthenticationPrincipal CurrentUser user,
      @PathVariable UUID id,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody BankTopupRequest request) {
    requireUser(user);
    return new ApiResponse<>(correlationId(), banks.topup(user.userId(), id, request, key));
  }

  private static void requireUser(CurrentUser user) {
    if (user == null) throw new SecurityException("Authentication is required");
  }

  private static String correlationId() {
    String value = MDC.get("correlationId");
    return value == null ? "none" : value;
  }
}
