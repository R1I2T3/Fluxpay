package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.PolicyIndexResult;
import com.fluxpay.service.PolicyIndexingService;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Indexes an existing policy into a new active Qwen vector generation. */
@RestController
@RequestMapping("/api/policies")
@PreAuthorize("hasRole('ADMIN')")
public class PolicyIndexController {
  private final PolicyIndexingService indexingService;

  public PolicyIndexController(PolicyIndexingService indexingService) {
    this.indexingService = indexingService;
  }

  @PostMapping("/{id}/index")
  public ResponseEntity<ApiResponse<PolicyIndexResult>> index(@PathVariable UUID id) {
    String correlationId = MDC.get("correlationId");
    return ResponseEntity.ok(
        new ApiResponse<>(
            correlationId == null ? "none" : correlationId, indexingService.index(id)));
  }
}
