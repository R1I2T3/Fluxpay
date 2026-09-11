package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.*;
import com.fluxpay.service.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
  private final PaymentService payments; private final QuoteService quotes; private final PaymentConfirmationService confirmations;
  public PaymentController(PaymentService payments, QuoteService quotes, PaymentConfirmationService confirmations) { this.payments = payments; this.quotes = quotes; this.confirmations = confirmations; }
  @PostMapping("/draft") public ResponseEntity<ApiResponse<PaymentResponse>> draft(@AuthenticationPrincipal CurrentUser user, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody DraftPaymentRequest request) { requiredKey(key); return ResponseEntity.status(HttpStatus.CREATED).body(ok(payments.draft(user.userId(), request))); }
  @PostMapping("/{id}/quotes") public ResponseEntity<ApiResponse<QuoteResponse>> quote(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) { return ResponseEntity.status(HttpStatus.CREATED).body(ok(quotes.createOrCurrent(user.userId(), id))); }
  @GetMapping("/{id}/quotes") public ApiResponse<QuoteResponse> getQuotes(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) { return ok(quotes.get(user.userId(), id)); }
  @PostMapping("/{id}/confirm") public ApiResponse<PaymentResponse> confirm(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ConfirmPaymentRequest request) { requiredKey(key); return ok(confirmations.confirm(user.userId(), id, request)); }
  @PostMapping("/{id}/cancel") public ApiResponse<PaymentResponse> cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) { return ok(payments.cancel(user.userId(), id)); }
  @GetMapping public ApiResponse<PaymentPageResponse> list(@AuthenticationPrincipal CurrentUser user, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) { return ok(payments.list(user.userId(), page, size)); }
  @GetMapping("/{id}") public ApiResponse<PaymentResponse> detail(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) { return ok(payments.detail(user.userId(), id)); }
  private void requiredKey(String key) { if (key == null || key.isBlank() || key.length() > 64) throw new M3BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1 to 64 characters."); }
  private <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(MDC.get("correlationId"), data); }
}
