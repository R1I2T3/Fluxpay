package com.fluxpay.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.dto.M5AssessmentRequest;
import com.fluxpay.dto.M5AssessmentResponse;
import com.fluxpay.dto.M5CasePage;
import com.fluxpay.dto.M5CaseResponse;
import com.fluxpay.service.M5ComplianceService;
import com.fluxpay.service.M5Fingerprints;
import com.fluxpay.service.M5PaymentReader;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** M5-owned authoritative screening API. Payment disposition callbacks remain internal. */
@Profile("m5-risk")
@RestController
@RequestMapping("/api/compliance")
public class M5ComplianceController {
  private final M5ComplianceService compliance;
  private final M5PaymentReader payments;

  public M5ComplianceController(M5ComplianceService compliance, M5PaymentReader payments) {
    this.compliance = compliance;
    this.payments = payments;
  }

  @PostMapping("/assess/{paymentId}")
  public ResponseEntity<ApiResponse<M5AssessmentResponse>> assess(
      @PathVariable UUID paymentId,
      @RequestBody AssessmentBody request,
      @AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);
    var outcome = compliance.assessOutcome(new M5AssessmentRequest(request.assessmentId(),
        request.assessmentSequence(), paymentId, request.expectedPaymentFingerprint()));
    return ResponseEntity.status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(wrap(outcome.response()));
  }

  /** Read-only starting point; the returned sequence is a suggestion, not a reservation. */
  @GetMapping("/payments/{paymentId}/assessment-context")
  public ApiResponse<AssessmentContext> assessmentContext(
      @PathVariable UUID paymentId, @AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);
    var snapshot = payments.readForAssessment(paymentId);
    if (snapshot == null || !paymentId.equals(snapshot.paymentId()) || snapshot.observedAt() == null) {
      throw new M5ApiException(503, "PAYMENT_DATA_UNAVAILABLE",
          "A complete authoritative observation of this payment is required");
    }
    long sequence = compliance.latestSequence(paymentId);
    if (sequence == Long.MAX_VALUE) {
      throw new M5ApiException(409, "ASSESSMENT_CONFLICT", "Assessment sequence is exhausted");
    }
    return wrap(new AssessmentContext(paymentId, M5Fingerprints.payment(snapshot),
        sequence + 1, snapshot.observedAt()));
  }

  @GetMapping("/cases")
  public ApiResponse<M5CasePage> cases(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String risk,
      @RequestParam(required = false) Boolean reviewable,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal CurrentUser actor) {
    return wrap(compliance.list(status, risk, reviewable, page, size, actor));
  }

  @GetMapping("/cases/{id}")
  public ApiResponse<M5CaseResponse> get(
      @PathVariable UUID id, @AuthenticationPrincipal CurrentUser actor) {
    return wrap(compliance.get(id, actor));
  }

  @GetMapping("/payments/{paymentId}/passport")
  public ApiResponse<M5CaseResponse> passport(
      @PathVariable UUID paymentId, @AuthenticationPrincipal CurrentUser actor) {
    return wrap(compliance.passport(paymentId, actor));
  }

  @PutMapping("/cases/{id}/approve")
  public ApiResponse<M5CaseResponse> approve(
      @PathVariable UUID id,
      @RequestBody(required = false) DecisionBody request,
      @AuthenticationPrincipal CurrentUser actor) {
    return wrap(compliance.decide(id, "APPROVE", request == null ? null : request.reason(), actor));
  }

  @PutMapping("/cases/{id}/reject")
  public ApiResponse<M5CaseResponse> reject(
      @PathVariable UUID id,
      @RequestBody(required = false) DecisionBody request,
      @AuthenticationPrincipal CurrentUser actor) {
    return wrap(compliance.decide(id, "REJECT", request == null ? null : request.reason(), actor));
  }

  private static void requireAdmin(CurrentUser actor) {
    if (actor == null || actor.userId() == null) {
      throw new M5ApiException(401, "AUTH_REQUIRED", "Authentication required");
    }
    if (!"ADMIN".equals(actor.role())) {
      throw new M5ApiException(403, "FORBIDDEN", "ADMIN access required");
    }
  }

  private static <T> ApiResponse<T> wrap(T value) {
    String correlation = MDC.get("correlationId");
    return new ApiResponse<>(correlation == null ? UUID.randomUUID().toString() : correlation, value);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AssessmentBody(UUID assessmentId, long assessmentSequence,
      String expectedPaymentFingerprint) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DecisionBody(String reason) {}

  public record AssessmentContext(UUID paymentId, String expectedPaymentFingerprint,
      long nextAssessmentSequence, Instant observedAt) {}
}
