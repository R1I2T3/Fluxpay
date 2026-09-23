package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.dto.ProviderRow;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.ReportQueryService;
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
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminReportController.class)
@Import({
  SecurityConfig.class,
  MethodSecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  TicketApiExceptionHandler.class
})
class AdminReportControllerMvcTest {
  private static final String FROM = "2026-09-18T00:00:00Z";
  private static final String TO = "2026-09-19T00:00:00Z";
  private static final String ADMIN_TOKEN = TestAuthHelper.mockJwt(UUID.randomUUID(), "ADMIN");
  private static final String USER_TOKEN = TestAuthHelper.mockJwt(UUID.randomUUID(), "USER");

  @Autowired MockMvc mvc;
  @MockBean ReportQueryService reports;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticateTokens() {
    when(jwt.parse(ADMIN_TOKEN)).thenReturn(TestAuthHelper.withUser("admin@fluxpay.test", "ADMIN"));
    when(jwt.parse(USER_TOKEN)).thenReturn(TestAuthHelper.withUser("user@fluxpay.test", "USER"));
  }

  @Test
  void adminCanReadProviderSummary() throws Exception {
    Instant from = Instant.parse(FROM);
    Instant to = Instant.parse(TO);
    when(reports.providerSummary(eq(from), eq(to)))
        .thenReturn(List.of(new ProviderRow("Bank A", 3, 2, 1)));

    mvc.perform(
            get("/api/admin/reports/provider-summary")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", FROM)
                .queryParam("to", TO))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].providerName").value("Bank A"))
        .andExpect(jsonPath("$.data[0].completedAttempts").value(2));
  }

  @Test
  void rejectsInvalidReportDate() throws Exception {
    mvc.perform(
            get("/api/admin/reports/provider-summary")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "not-a-date")
                .queryParam("to", TO))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"));
  }

  @Test
  void rejectsMissingReportDate() throws Exception {
    mvc.perform(
            get("/api/admin/reports/provider-summary")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("to", TO))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"));
  }

  @Test
  void rejectsReversedReportRange() throws Exception {
    when(reports.providerSummary(eq(Instant.parse(TO)), eq(Instant.parse(FROM))))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_RANGE",
                "Report start time must be before the end time."));

    mvc.perform(
            get("/api/admin/reports/provider-summary")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", TO)
                .queryParam("to", FROM))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"));
  }

  @Test
  void userIsForbiddenFromReports() throws Exception {
    mvc.perform(
            get("/api/admin/reports/provider-summary")
                .header("Authorization", "Bearer " + USER_TOKEN)
                .queryParam("from", FROM)
                .queryParam("to", TO))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }
}
