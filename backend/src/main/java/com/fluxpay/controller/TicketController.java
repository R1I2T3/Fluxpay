package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.CreateTicketRequest;
import com.fluxpay.dto.TicketPageResponse;
import com.fluxpay.dto.TicketResponse;
import com.fluxpay.service.PaymentOperationService;
import com.fluxpay.service.TicketService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
  private final TicketService tickets;

  public TicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<TicketResponse>> create(
      @AuthenticationPrincipal CurrentUser user,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @Valid @RequestBody CreateTicketRequest request) {
    PaymentOperationService.requireKey(idempotencyKey);
    TicketResponse ticket = tickets.create(user.userId(), request, idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED).body(envelope(ticket));
  }

  @GetMapping
  public ApiResponse<TicketPageResponse> list(
      @AuthenticationPrincipal CurrentUser user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return envelope(tickets.list(user.userId(), page, size));
  }

  @GetMapping("/{id}")
  public ApiResponse<TicketResponse> get(
      @AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
    return envelope(tickets.getOwned(user.userId(), id));
  }

  private <T> ApiResponse<T> envelope(T data) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(correlationId == null ? "none" : correlationId, data);
  }
}
