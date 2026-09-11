package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.dto.ComplianceAssessRequest;
import com.fluxpay.dto.ComplianceCaseRequest;
import com.fluxpay.dto.ComplianceCaseResponse;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.service.ComplianceAssessmentService;
import com.fluxpay.service.ComplianceCaseService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("m5-legacy")
@RestController
@RequestMapping("/api/compliance")
public class ComplianceCaseController {

  private final ComplianceCaseService service;
  private final ComplianceAssessmentService assessmentService;

  public ComplianceCaseController(
      ComplianceCaseService service, ComplianceAssessmentService assessmentService) {
    this.service = service;
    this.assessmentService = assessmentService;
  }

  /**
   * Runs the deterministic Payment Passport rule engine against a real payment and creates (or
   * returns the existing) compliance case. Body is optional -- see {@link
   * ComplianceAssessRequest} for why.
   */
  @PostMapping("/assess/{paymentId}")
  public ResponseEntity<ApiResponse<ComplianceCaseResponse>> assess(
      @PathVariable UUID paymentId,
      @RequestBody(required = false) ComplianceAssessRequest hints) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(wrap(assessmentService.assess(paymentId, hints)));
  }

  /** Manual case creation (e.g. an admin opening a case directly), bypassing the rule engine. */
  @PostMapping("/cases")
  public ResponseEntity<ApiResponse<ComplianceCaseResponse>> create(
      @Valid @RequestBody ComplianceCaseRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(wrap(service.create(request)));
  }

  @GetMapping("/cases")
  public ApiResponse<List<ComplianceCaseResponse>> list(
      @RequestParam(required = false) ComplianceCaseStatus status) {
    return wrap(service.list(status));
  }

  @GetMapping("/cases/{id}")
  public ApiResponse<ComplianceCaseResponse> getById(@PathVariable UUID id) {
    return wrap(service.getById(id));
  }

  @PutMapping("/cases/{id}/approve")
  public ApiResponse<ComplianceCaseResponse> approve(
      @PathVariable UUID id, @Valid @RequestBody ComplianceDecisionRequest request) {
    return wrap(service.approve(id, request));
  }

  @PutMapping("/cases/{id}/reject")
  public ApiResponse<ComplianceCaseResponse> reject(
      @PathVariable UUID id, @Valid @RequestBody ComplianceDecisionRequest request) {
    return wrap(service.reject(id, request));
  }

  private <T> ApiResponse<T> wrap(T data) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, data);
  }
}
