package com.fluxpay.m5.api;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.m5.application.M5PolicyIndexingService;
import com.fluxpay.m5.application.PolicyIndexResult;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Indexes an existing policy into a new active M5 Qwen vector generation. */
@RestController
@RequestMapping("/api/policies")
public class M5PolicyIndexController {
  private final M5PolicyIndexingService indexingService;

  public M5PolicyIndexController(M5PolicyIndexingService indexingService) {
    this.indexingService = indexingService;
  }

  @PostMapping("/{id}/index")
  public ResponseEntity<ApiResponse<PolicyIndexResult>> index(@PathVariable UUID id) {
    String correlationId = MDC.get("correlationId");
    return ResponseEntity.ok(
        new ApiResponse<>(correlationId == null ? "none" : correlationId, indexingService.index(id)));
  }
}
