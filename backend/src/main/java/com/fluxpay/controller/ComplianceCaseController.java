package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.dto.ComplianceCaseRequest;
import com.fluxpay.dto.ComplianceCaseResponse;
import com.fluxpay.dto.ComplianceDecisionRequest;
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

@RestController
@RequestMapping("/api/compliance/cases")
public class ComplianceCaseController {

  private final ComplianceCaseService service;

  public ComplianceCaseController(ComplianceCaseService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<ComplianceCaseResponse>> create(
      @Valid @RequestBody ComplianceCaseRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(wrap(service.create(request)));
  }

  @GetMapping
  public ApiResponse<List<ComplianceCaseResponse>> list(
      @RequestParam(required = false) ComplianceCaseStatus status) {
    return wrap(service.list(status));
  }

  @GetMapping("/{id}")
  public ApiResponse<ComplianceCaseResponse> getById(@PathVariable UUID id) {
    return wrap(service.getById(id));
  }

  @PutMapping("/{id}/approve")
  public ApiResponse<ComplianceCaseResponse> approve(
      @PathVariable UUID id, @Valid @RequestBody ComplianceDecisionRequest request) {
    return wrap(service.approve(id, request));
  }

  @PutMapping("/{id}/reject")
  public ApiResponse<ComplianceCaseResponse> reject(
      @PathVariable UUID id, @Valid @RequestBody ComplianceDecisionRequest request) {
    return wrap(service.reject(id, request));
  }

  private <T> ApiResponse<T> wrap(T data) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, data);
  }
}
