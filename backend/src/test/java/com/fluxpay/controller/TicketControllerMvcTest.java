package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.dto.CreateTicketRequest;
import com.fluxpay.dto.TicketPageResponse;
import com.fluxpay.dto.TicketResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.TicketService;
import com.fluxpay.web.advice.TicketApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TicketController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  TicketApiExceptionHandler.class
})
class TicketControllerMvcTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID TICKET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String TOKEN = TestAuthHelper.mockJwt(USER_ID, "USER");

  @Autowired MockMvc mvc;
  @MockBean TicketService tickets;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticateToken() {
    CurrentUser user = TestAuthHelper.withUser(USER_ID, "user@fluxpay.test", "USER");
    when(jwt.parse(TOKEN)).thenReturn(user);
  }

  @Test
  void createsTicketForAuthenticatedUser() throws Exception {
    when(tickets.create(
            eq(USER_ID),
            eq(new CreateTicketRequest(null, "Payout stuck", "Payment failed")),
            eq("key-1")))
        .thenReturn(response());

    mvc.perform(
            post("/api/tickets")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Payout stuck\",\"body\":\"Payment failed\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(TICKET_ID.toString()))
        .andExpect(jsonPath("$.data.status").value("OPEN"));
  }

  @Test
  void listsOnlyCurrentUsersTickets() throws Exception {
    when(tickets.list(USER_ID, 0, 20))
        .thenReturn(new TicketPageResponse(List.of(response()), 0, 20, 1));

    mvc.perform(get("/api/tickets").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(TICKET_ID.toString()));
  }

  @Test
  void getsOwnedTicket() throws Exception {
    when(tickets.getOwned(USER_ID, TICKET_ID)).thenReturn(response());

    mvc.perform(get("/api/tickets/{id}", TICKET_ID).header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userId").value(USER_ID.toString()));
  }

  @Test
  void crossUserTicketIsHiddenAsNotFound() throws Exception {
    when(tickets.getOwned(USER_ID, TICKET_ID))
        .thenThrow(
            new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Ticket not found."));

    mvc.perform(get("/api/tickets/{id}", TICKET_ID).header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
  }

  @Test
  void rejectsMissingIdempotencyKey() throws Exception {
    mvc.perform(
            post("/api/tickets")
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Payout stuck\",\"body\":\"Payment failed\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
  }

  @Test
  void rejectsInvalidSubjectAndBody() throws Exception {
    mvc.perform(
            post("/api/tickets")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "key-invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"\",\"body\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION"))
        .andExpect(jsonPath("$.fieldErrors.subject").exists())
        .andExpect(jsonPath("$.fieldErrors.body").exists());
  }

  private TicketResponse response() {
    Instant now = Instant.parse("2026-09-18T00:00:00Z");
    return new TicketResponse(
        TICKET_ID, USER_ID, null, "Payout stuck", "Payment failed", "OPEN", null, now, now);
  }
}
