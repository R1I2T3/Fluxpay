package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.*;
import com.fluxpay.service.RecipientService;
import jakarta.validation.Valid;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recipients")
public class RecipientController {
  private final RecipientService service;

  public RecipientController(RecipientService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<RecipientResponse>> create(
      @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody RecipientRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ok(service.create(user.userId(), request)));
  }

  @GetMapping
  public ApiResponse<List<RecipientResponse>> list(@AuthenticationPrincipal CurrentUser user) {
    return ok(service.list(user.userId()));
  }

  @PutMapping("/{id}")
  public ApiResponse<RecipientResponse> update(
      @AuthenticationPrincipal CurrentUser user,
      @PathVariable UUID id,
      @Valid @RequestBody RecipientRequest request) {
    return ok(service.update(user.userId(), id, request));
  }

  private <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(MDC.get("correlationId"), data);
  }
}
