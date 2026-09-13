package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.dto.*;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PayoutController {
  private final PaymentReader reader;
  private final RouteAdminAuthorizer authorizer;
  private final PayoutExecutionService execution;
  private final RecoveryService recovery;
  private final PaymentOperationService operations;

  public PayoutController(
      PaymentReader reader,
      RouteAdminAuthorizer authorizer,
      PayoutExecutionService execution,
      RecoveryService recovery,
      PaymentOperationService operations) {
    this.reader = reader;
    this.authorizer = authorizer;
    this.execution = execution;
    this.recovery = recovery;
    this.operations = operations;
  }

  @PostMapping("/api/payments/{paymentId}/submit-payout")
  public ApiResponse<PayoutApi.OutcomeResponse> submit(
      @PathVariable String paymentId,
      @RequestBody PayoutApi.SubmitRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request) {
    PaymentOperationService.requireKey(key);
    requireRoute(body == null ? null : body.routeCode());
    return payout(paymentId, key, "SUBMIT", body.routeCode(), null, request);
  }

  @PostMapping("/api/payments/{paymentId}/retry-payout")
  public ApiResponse<PayoutApi.OutcomeResponse> retry(
      @PathVariable String paymentId,
      @RequestBody(required = false) PayoutApi.RetryRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request) {
    PaymentOperationService.requireKey(key);
    return payout(paymentId, key, "RETRY", null, body == null ? null : body.quoteId(), request);
  }

  @PostMapping("/api/payments/{paymentId}/switch-route")
  public ApiResponse<PayoutApi.OutcomeResponse> switchRoute(
      @PathVariable String paymentId,
      @RequestBody(required = false) PayoutApi.SwitchRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request) {
    PaymentOperationService.requireKey(key);
    if (body == null || body.routeCode() == null || body.routeCode().isBlank())
      throw new com.fluxpay.exception.BusinessException(
          org.springframework.http.HttpStatus.CONFLICT,
          "REQUOTE_REQUIRED",
          "Select a current replacement quote and route.");
    return payout(paymentId, key, "SWITCH", body.routeCode(), body.quoteId(), request);
  }

  @PostMapping("/api/payments/{paymentId}/refund")
  public ApiResponse<RecoveryResult> refund(
      @PathVariable String paymentId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest request) {
    PaymentOperationService.requireKey(key);
    var payment = owned(paymentId);
    var id = UUID.fromString(paymentId);
    var cid = ControllerSupport.correlationId(request);
    var result =
        operations.execute(
            payment.senderUserId(),
            key,
            "REFUND",
            id,
            Map.of(),
            RecoveryResult.class,
            () ->
                new PaymentOperationService.Result<>(
                    200, recovery.refundFunded(payment.senderUserId(), id, cid), id));
    return new ApiResponse<>(cid, result.response());
  }

  private ApiResponse<PayoutApi.OutcomeResponse> payout(
      String paymentId,
      String key,
      String action,
      String route,
      UUID quote,
      HttpServletRequest request) {
    var payment = owned(paymentId);
    var cid = ControllerSupport.correlationId(request);
    return new ApiResponse<>(
        cid,
        execution.perform(
            payment.senderUserId(), key, action, UUID.fromString(paymentId), route, quote, cid));
  }

  private PaymentSnapshot owned(String paymentId) {
    var payment = reader.get(paymentId);
    if (!authorizer.isOwner(ControllerSupport.currentUser(), payment))
      throw new ForbiddenException("user is not the owner of payment " + paymentId);
    return payment;
  }

  private static void requireRoute(String route) {
    if (route == null || route.isBlank())
      throw new IllegalArgumentException("routeCode must not be blank");
  }
}
