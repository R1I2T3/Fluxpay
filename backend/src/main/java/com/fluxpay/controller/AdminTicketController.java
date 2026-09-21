package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.TicketPageResponse;
import com.fluxpay.dto.TicketResponse;
import com.fluxpay.dto.UpdateTicketRequest;
import com.fluxpay.service.TicketService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tickets")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTicketController {
  private final TicketService tickets;

  public AdminTicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  @GetMapping
  public ApiResponse<TicketPageResponse> list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return envelope(tickets.listForAdmin(status, page, size));
  }

  @PutMapping("/{id}")
  public ApiResponse<TicketResponse> update(
      @PathVariable UUID id, @Valid @RequestBody UpdateTicketRequest request) {
    return envelope(tickets.changeStatus(id, request.status(), request.assigneeAdminId()));
  }

  private <T> ApiResponse<T> envelope(T data) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(correlationId == null ? "none" : correlationId, data);
  }
}
