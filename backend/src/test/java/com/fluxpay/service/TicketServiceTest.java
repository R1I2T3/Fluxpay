package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.SupportTicket;
import com.fluxpay.beans.User;
import com.fluxpay.dto.CreateTicketRequest;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.SupportTicketRepository;
import com.fluxpay.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TicketServiceTest {
  private final SupportTicketRepository tickets = mock(SupportTicketRepository.class);
  private final PaymentRepository payments = mock(PaymentRepository.class);
  private final PaymentOperationService operations = mock(PaymentOperationService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);
  private final TicketService service =
      new TicketService(tickets, payments, operations, users, clock);

  @Test
  void createsTrimmedOpenTicketUsingIdempotencyOperation() {
    UUID userId = UUID.randomUUID();
    when(tickets.saveAndFlush(any(SupportTicket.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(operations.execute(
            eq(userId),
            eq("ticket-create-1"),
            eq("CREATE_TICKET"),
            eq(null),
            any(),
            eq(com.fluxpay.dto.TicketResponse.class),
            any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              Supplier<PaymentOperationService.Result<com.fluxpay.dto.TicketResponse>> work =
                  invocation.getArgument(6);
              return work.get();
            });

    var result =
        service.create(
            userId,
            new CreateTicketRequest(null, "  Payout stuck  ", "  My payment failed twice  "),
            "ticket-create-1");

    ArgumentCaptor<SupportTicket> saved = ArgumentCaptor.forClass(SupportTicket.class);
    verify(tickets).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getSubject()).isEqualTo("Payout stuck");
    assertThat(saved.getValue().getBody()).isEqualTo("My payment failed twice");
    assertThat(saved.getValue().getStatus()).isEqualTo("OPEN");
    assertThat(result.status()).isEqualTo("OPEN");
  }

  @Test
  void hidesAnotherUsersTicketAsNotFound() {
    UUID userId = UUID.randomUUID();
    UUID ticketId = UUID.randomUUID();
    when(tickets.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getOwned(userId, ticketId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(404);
              assertThat(error.code()).isEqualTo("TICKET_NOT_FOUND");
            });
  }

  @Test
  void rejectsInvalidTicketStatusTransition() {
    SupportTicket ticket =
        new SupportTicket(UUID.randomUUID(), null, "Payout stuck", "My payment failed twice");
    ticket.moveTo("CLOSED", Instant.now(clock));
    UUID ticketId = UUID.randomUUID();
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));

    assertThatThrownBy(() -> service.changeStatus(ticketId, "RESOLVED", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(409);
              assertThat(error.code()).isEqualTo("INVALID_STATUS_TRANSITION");
            });
  }

  @Test
  void rejectsUnsupportedTicketStatus() {
    UUID ticketId = UUID.randomUUID();

    assertThatThrownBy(() -> service.changeStatus(ticketId, "WAITING_FOR_VENDOR", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("INVALID_TICKET_STATUS");
            });
  }

  @Test
  void rejectsNonAdminTicketAssignee() {
    UUID ticketId = UUID.randomUUID();
    UUID assigneeId = UUID.randomUUID();
    SupportTicket ticket =
        new SupportTicket(UUID.randomUUID(), null, "Payout stuck", "My payment failed twice");
    when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
    when(users.findById(assigneeId))
        .thenReturn(
            Optional.of(
                new User(
                    assigneeId,
                    "user@fluxpay.test",
                    "hash",
                    "USER",
                    "Regular User",
                    Instant.now(clock),
                    Instant.now(clock))));

    assertThatThrownBy(() -> service.changeStatus(ticketId, "IN_PROGRESS", assigneeId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("INVALID_TICKET_ASSIGNEE");
            });
  }

  @Test
  void rejectsUnsupportedAdminStatusFilter() {
    assertThatThrownBy(() -> service.listForAdmin("WAITING_FOR_VENDOR", 0, 20))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("INVALID_TICKET_STATUS");
            });
  }
}
