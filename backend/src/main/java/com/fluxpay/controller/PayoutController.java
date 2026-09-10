package com.fluxpay.controller;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.dto.RecoveryAction;
import com.fluxpay.dto.RecoveryResult;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.service.ForbiddenException;
import com.fluxpay.service.PaymentEligibilityGate;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.PayoutExecutionService;
import com.fluxpay.service.RecoveryService;
import com.fluxpay.service.RouteAdminAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PayoutController {

  private final PaymentReader reader;
  private final RouteAdminAuthorizer authorizer;
  private final PaymentEligibilityGate gate;
  private final PayoutExecutionService execution;
  private final RecoveryService recovery;
  private final PayoutAttemptRepository attempts;
  private final PayoutRouteRepository routes;

  public PayoutController(
      PaymentReader reader,
      RouteAdminAuthorizer authorizer,
      PaymentEligibilityGate gate,
      PayoutExecutionService execution,
      RecoveryService recovery,
      PayoutAttemptRepository attempts,
      PayoutRouteRepository routes) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    this.execution = Objects.requireNonNull(execution, "execution must not be null");
    this.recovery = Objects.requireNonNull(recovery, "recovery must not be null");
    this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
  }

  @PostMapping("/api/payments/{paymentId}/submit-payout")
  public ApiResponse<PayoutApi.OutcomeResponse> submit(
      @PathVariable String paymentId,
      @RequestBody PayoutApi.SubmitRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    PaymentSnapshot payment = owned(paymentId);
    if (body == null || body.routeCode() == null || body.routeCode().isBlank()) {
      throw new IllegalArgumentException("routeCode must not be blank");
    }
    gate.assertActiveQuote(payment, body.routeCode());
    String key = keyOrRandom(idempotencyKey);
    PaymentEligibilityGate.ConfirmOutcome confirm = gate.confirmIdempotent(payment, key);
    if (confirm.alreadyConfirmed()) {
      return new ApiResponse<>(
          cid, build(paymentId, body.routeCode(), null, true, confirm.originalEventId()));
    }
    try {
      PayoutOutcome outcome = execution.submit(paymentId, body.routeCode(), cid);
      // First execution has no replay source; return null instead of the gate's reservation UUID.
      return new ApiResponse<>(cid, build(paymentId, body.routeCode(), outcome, false, null));
    } catch (RuntimeException e) {
      gate.release(payment, key);
      throw e;
    }
  }

  @PostMapping("/api/payments/{paymentId}/retry-payout")
  public ApiResponse<PayoutApi.OutcomeResponse> retry(
      @PathVariable String paymentId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    PaymentSnapshot payment = owned(paymentId);
    String key = keyOrRandom(idempotencyKey);
    PaymentEligibilityGate.ConfirmOutcome confirm = gate.confirmIdempotent(payment, key);
    if (confirm.alreadyConfirmed()) {
      return new ApiResponse<>(cid, build(paymentId, null, null, true, confirm.originalEventId()));
    }
    try {
      PayoutOutcome outcome = recovery.retry(paymentId, cid);
      return new ApiResponse<>(cid, build(paymentId, null, outcome, false, null));
    } catch (RuntimeException e) {
      gate.release(payment, key);
      throw e;
    }
  }

  @PostMapping("/api/payments/{paymentId}/switch-route")
  public ApiResponse<PayoutApi.OutcomeResponse> switchRoute(
      @PathVariable String paymentId,
      @RequestBody PayoutApi.SwitchRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    PaymentSnapshot payment = owned(paymentId);
    if (body == null || body.routeCode() == null || body.routeCode().isBlank()) {
      throw new IllegalArgumentException("routeCode must not be blank");
    }
    String key = keyOrRandom(idempotencyKey);
    PaymentEligibilityGate.ConfirmOutcome confirm = gate.confirmIdempotent(payment, key);
    if (confirm.alreadyConfirmed()) {
      return new ApiResponse<>(
          cid, build(paymentId, body.routeCode(), null, true, confirm.originalEventId()));
    }
    try {
      PayoutOutcome outcome = recovery.switchRoute(paymentId, body.routeCode(), cid);
      return new ApiResponse<>(cid, build(paymentId, body.routeCode(), outcome, false, null));
    } catch (RuntimeException e) {
      gate.release(payment, key);
      throw e;
    }
  }

  @PostMapping("/api/payments/{paymentId}/refund")
  public ApiResponse<RecoveryResult> refund(
      @PathVariable String paymentId, HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    owned(paymentId);
    return new ApiResponse<>(cid, recovery.refund(paymentId, cid));
  }

  private PaymentSnapshot owned(String paymentId) {
    PaymentSnapshot payment = reader.get(paymentId);
    if (!authorizer.isOwner(ControllerSupport.currentUser(), payment)) {
      throw new ForbiddenException("user is not the owner of payment " + paymentId);
    }
    return payment;
  }

  private static String keyOrRandom(String idempotencyKey) {
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      return idempotencyKey;
    }
    return UUID.randomUUID().toString();
  }

  private PayoutApi.OutcomeResponse build(
      String paymentId,
      String routeCode,
      PayoutOutcome outcome,
      boolean alreadyConfirmed,
      String originalEventId) {
    Integer attemptNumber = null;
    String providerRef = null;
    String error = null;
    String status = null;
    List<RecoveryAction> allowed = List.of();
    Optional<PayoutAttempt> latest =
        attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId);
    if (latest.isPresent()) {
      PayoutAttempt attempt = latest.get();
      attemptNumber = attempt.attemptNumber();
      providerRef = attempt.providerReference();
      error = attempt.errorCode();
      status = attempt.status().name();
      if (attempt.status() == PayoutAttemptStatus.FAILED) {
        allowed = List.of(RecoveryAction.RETRY, RecoveryAction.SWITCH, RecoveryAction.REFUND);
      }
      if (routeCode == null) {
        routeCode =
            routes.findById(attempt.routeId()).map(route -> route.getRouteCode()).orElse(null);
      }
    }
    if (outcome != null) {
      status = outcome.status().name();
      allowed = outcome.allowed();
    }
    return new PayoutApi.OutcomeResponse(
        attemptNumber,
        routeCode,
        status,
        providerRef,
        error,
        allowed,
        alreadyConfirmed,
        originalEventId);
  }
}
