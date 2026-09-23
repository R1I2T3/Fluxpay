package com.fluxpay.service;

import com.fluxpay.beans.SupportTicket;
import com.fluxpay.dto.CreateTicketRequest;
import com.fluxpay.dto.TicketPageResponse;
import com.fluxpay.dto.TicketResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.SupportTicketRepository;
import com.fluxpay.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {
  private final SupportTicketRepository tickets;
  private final PaymentRepository payments;
  private final PaymentOperationService operations;
  private final UserRepository users;
  private final Clock clock;

  public TicketService(
      SupportTicketRepository tickets,
      PaymentRepository payments,
      PaymentOperationService operations,
      UserRepository users,
      Clock clock) {
    this.tickets = tickets;
    this.payments = payments;
    this.operations = operations;
    this.users = users;
    this.clock = clock;
  }

  public TicketResponse create(UUID userId, CreateTicketRequest request, String idempotencyKey) {
    validateRequest(request);
    PaymentOperationService.requireKey(idempotencyKey);
    if (request.paymentId() != null
        && payments.findByIdAndSenderId(request.paymentId(), userId).isEmpty()) {
      throw notFound("PAYMENT_NOT_FOUND", "Payment not found.");
    }

    return operations
        .execute(
            userId,
            idempotencyKey,
            "CREATE_TICKET",
            request.paymentId(),
            request,
            TicketResponse.class,
            () -> {
              SupportTicket ticket =
                  tickets.saveAndFlush(
                      new SupportTicket(
                          userId,
                          request.paymentId(),
                          request.subject().trim(),
                          request.body().trim(),
                          Instant.now(clock)));
              return new PaymentOperationService.Result<>(
                  201, response(ticket), request.paymentId());
            })
        .response();
  }

  @Transactional(readOnly = true)
  public TicketResponse getOwned(UUID userId, UUID ticketId) {
    return tickets
        .findByIdAndUserId(ticketId, userId)
        .map(this::response)
        .orElseThrow(this::ticketNotFound);
  }

  @Transactional(readOnly = true)
  public TicketPageResponse list(UUID userId, int page, int size) {
    int safePage = safePage(page);
    int safeSize = safeSize(size);
    Page<SupportTicket> results =
        tickets.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(safePage, safeSize));
    return pageResponse(results, safePage);
  }

  @Transactional(readOnly = true)
  public TicketPageResponse listForAdmin(String status, int page, int size) {
    int safePage = safePage(page);
    int safeSize = safeSize(size);
    PageRequest pageable = PageRequest.of(safePage, safeSize);
    Page<SupportTicket> results;
    if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
      results = tickets.findAllByOrderByCreatedAtDesc(pageable);
    } else {
      results = tickets.findByStatusOrderByCreatedAtDesc(normalizeStatus(status), pageable);
    }
    return pageResponse(results, safePage);
  }

  @Transactional
  public TicketResponse changeStatus(UUID ticketId, String nextStatus, UUID assigneeAdminId) {
    String normalizedStatus = normalizeStatus(nextStatus);
    SupportTicket ticket = tickets.findById(ticketId).orElseThrow(this::ticketNotFound);
    if ("CLOSED".equals(ticket.getStatus()) && !"OPEN".equals(normalizedStatus)) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "INVALID_STATUS_TRANSITION",
          "A closed ticket must be reopened before it can move to another status.");
    }
    if (assigneeAdminId != null) {
      validateAssignee(assigneeAdminId);
    }
    Instant now = Instant.now(clock);
    ticket.moveTo(normalizedStatus, now);
    // A null value means the caller is changing status only; it does not clear an assignment.
    if (assigneeAdminId != null) {
      ticket.assignTo(assigneeAdminId, now);
    }
    return response(ticket);
  }

  private void validateRequest(CreateTicketRequest request) {
    if (request == null) {
      throw invalid("INVALID_TICKET", "Ticket request is required.");
    }
    if (request.subject() == null
        || request.subject().trim().isEmpty()
        || request.subject().trim().length() > 120) {
      throw invalid("INVALID_TICKET_SUBJECT", "Ticket subject must contain 1 to 120 characters.");
    }
    if (request.body() == null
        || request.body().trim().isEmpty()
        || request.body().trim().length() > 4000) {
      throw invalid("INVALID_TICKET_BODY", "Ticket body must contain 1 to 4000 characters.");
    }
  }

  private String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      throw invalid("INVALID_STATUS_TRANSITION", "Ticket status is required.");
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!SupportTicket.isSupportedStatus(normalized)) {
      throw invalid(
          "INVALID_TICKET_STATUS", "Ticket status must be OPEN, IN_PROGRESS, RESOLVED, or CLOSED.");
    }
    return normalized;
  }

  private void validateAssignee(UUID assigneeAdminId) {
    boolean administrator =
        users
            .findById(assigneeAdminId)
            .map(user -> "ADMIN".equalsIgnoreCase(user.getRole()))
            .orElse(false);
    if (!administrator) {
      throw invalid(
          "INVALID_TICKET_ASSIGNEE", "Ticket assignee must be an existing administrator.");
    }
  }

  private int safePage(int page) {
    return Math.max(page, 0);
  }

  private int safeSize(int size) {
    return Math.min(Math.max(size, 1), 100);
  }

  private TicketPageResponse pageResponse(Page<SupportTicket> results, int page) {
    return new TicketPageResponse(
        results.getContent().stream().map(this::response).toList(),
        page,
        results.getSize(),
        results.getTotalElements());
  }

  private TicketResponse response(SupportTicket ticket) {
    return new TicketResponse(
        ticket.getId(),
        ticket.getUserId(),
        ticket.getPaymentId(),
        ticket.getSubject(),
        ticket.getBody(),
        ticket.getStatus(),
        ticket.getAssigneeAdminId(),
        ticket.getCreatedAt(),
        ticket.getUpdatedAt());
  }

  private BusinessException ticketNotFound() {
    return notFound("TICKET_NOT_FOUND", "Ticket not found.");
  }

  private BusinessException invalid(String code, String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
  }

  private BusinessException notFound(String code, String message) {
    return new BusinessException(HttpStatus.NOT_FOUND, code, message);
  }
}
