package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.LedgerPageResponse;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.dto.WalletSummaryResponse;
import com.fluxpay.service.DemoFundingService;
import com.fluxpay.service.WalletConversionService;
import com.fluxpay.service.WalletQueryService;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {
  private final DemoFundingService funding;
  private final WalletConversionService conversion;
  private final WalletQueryService queries;
  private final com.fluxpay.service.WalletTransferRoutingService transfers;
  private final com.fluxpay.service.BankAccountService banks;

  public WalletController(
      DemoFundingService funding,
      WalletConversionService conversion,
      WalletQueryService queries,
      com.fluxpay.service.WalletTransferRoutingService transfers,
      com.fluxpay.service.BankAccountService banks) {
    this.funding = funding;
    this.conversion = conversion;
    this.queries = queries;
    this.transfers = transfers;
    this.banks = banks;
  }

  @PostMapping("/transfer")
  public ApiResponse<com.fluxpay.dto.WalletTransferResponse> transfer(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody com.fluxpay.dto.WalletTransferRequest request) {
    requireUser(currentUser);
    return new ApiResponse<>(
        correlationId(), transfers.transfer(currentUser.userId(), request, key));
  }

  @PostMapping("/withdraw")
  public ApiResponse<WalletResponse> withdraw(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody com.fluxpay.dto.WalletWithdrawRequest request) {
    requireUser(currentUser);
    return new ApiResponse<>(correlationId(), banks.withdraw(currentUser.userId(), request, key));
  }

  @GetMapping
  public ApiResponse<List<WalletSummaryResponse>> wallets(
      @AuthenticationPrincipal CurrentUser currentUser) {
    requireUser(currentUser);
    return new ApiResponse<>(correlationId(), queries.wallets(currentUser.userId()));
  }

  @GetMapping("/{walletId}/ledger")
  public ApiResponse<LedgerPageResponse> ledger(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable UUID walletId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    requireUser(currentUser);
    return new ApiResponse<>(
        correlationId(), queries.ledger(currentUser.userId(), walletId, page, size));
  }

  @PostMapping("/convert")
  public ApiResponse<WalletConvertResponse> convert(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody WalletConvertRequest request) {
    requireUser(currentUser);
    WalletConvertResponse response =
        conversion.convert(currentUser.userId(), request, idempotencyKey);
    return new ApiResponse<>(correlationId(), response);
  }

  @PostMapping("/receive-demo")
  public ApiResponse<WalletResponse> receiveDemo(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody WalletReceiveRequest request) {
    requireUser(currentUser);
    WalletResponse response = funding.receiveDemo(currentUser.userId(), request, idempotencyKey);
    return new ApiResponse<>(correlationId(), response);
  }

  private static String correlationId() {
    String value = MDC.get("correlationId");
    return value == null ? "none" : value;
  }

  private static void requireUser(CurrentUser currentUser) {
    if (currentUser == null) {
      throw new SecurityException("Authentication is required");
    }
  }
}
