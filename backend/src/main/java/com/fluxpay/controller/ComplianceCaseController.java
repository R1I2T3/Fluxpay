package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.common.security.CurrentUser;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
  @PreAuthorize("hasRole('ADMIN')")
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
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ComplianceCaseResponse> approve(
      @PathVariable UUID id,
      @Valid @RequestBody ComplianceDecisionRequest request,
      @AuthenticationPrincipal CurrentUser reviewer) {
    return wrap(service.approve(id, request, reviewer.email()));
  }

  @PutMapping("/{id}/reject")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ComplianceCaseResponse> reject(
      @PathVariable UUID id,
      @Valid @RequestBody ComplianceDecisionRequest request,
      @AuthenticationPrincipal CurrentUser reviewer) {
    return wrap(service.reject(id, request, reviewer.email()));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.deleteManualCase(id);
    return ResponseEntity.noContent().build();
  }

  private <T> ApiResponse<T> wrap(T data) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, data);
  }
}
