package com.fluxpay.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
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

@WebMvcTest(AdminTicketController.class)
@Import({
  SecurityConfig.class,
  MethodSecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  TicketApiExceptionHandler.class
})
class AdminTicketControllerMvcTest {
  private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final UUID TICKET_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final String ADMIN_TOKEN = TestAuthHelper.mockJwt(ADMIN_ID, "ADMIN");
  private static final String USER_TOKEN = TestAuthHelper.mockJwt(USER_ID, "USER");

  @Autowired MockMvc mvc;
  @MockBean TicketService tickets;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticateTokens() {
    CurrentUser admin = TestAuthHelper.withUser(ADMIN_ID, "admin@fluxpay.test", "ADMIN");
    CurrentUser user = TestAuthHelper.withUser(USER_ID, "user@fluxpay.test", "USER");
    when(jwt.parse(ADMIN_TOKEN)).thenReturn(admin);
    when(jwt.parse(USER_TOKEN)).thenReturn(user);
  }

  @Test
  void adminCanListTickets() throws Exception {
    when(tickets.listForAdmin(null, 0, 20))
        .thenReturn(new TicketPageResponse(List.of(response()), 0, 20, 1));

    mvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + ADMIN_TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(TICKET_ID.toString()));
  }

  @Test
  void adminCanUpdateTicketStatusAndAssignee() throws Exception {
    when(tickets.changeStatus(TICKET_ID, "IN_PROGRESS", ADMIN_ID))
        .thenReturn(response("IN_PROGRESS", ADMIN_ID));

    mvc.perform(
            put("/api/admin/tickets/{id}", TICKET_ID)
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"assigneeAdminId\":\"" + ADMIN_ID + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.data.assigneeAdminId").value(ADMIN_ID.toString()));
  }

  @Test
  void userIsForbiddenFromAdminTickets() throws Exception {
    mvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + USER_TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void rejectsUnsupportedTicketStatus() throws Exception {
    when(tickets.changeStatus(TICKET_ID, "WAITING_FOR_VENDOR", null))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_TICKET_STATUS",
                "Ticket status must be OPEN, IN_PROGRESS, RESOLVED, or CLOSED."));

    mvc.perform(
            put("/api/admin/tickets/{id}", TICKET_ID)
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"WAITING_FOR_VENDOR\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TICKET_STATUS"));
  }

  private TicketResponse response() {
    return response("OPEN", null);
  }

  private TicketResponse response(String status, UUID assigneeAdminId) {
    Instant now = Instant.parse("2026-09-18T00:00:00Z");
    return new TicketResponse(
        TICKET_ID,
        USER_ID,
        null,
        "Payout stuck",
        "Payment failed",
        status,
        assigneeAdminId,
        now,
        now);
  }
}
