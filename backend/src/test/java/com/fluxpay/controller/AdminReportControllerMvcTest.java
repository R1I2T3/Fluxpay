package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.dto.AdminStatisticsOptionsResponse;
import com.fluxpay.dto.AdminStatisticsPaymentPageResponse;
import com.fluxpay.dto.AdminStatisticsResponse;
import com.fluxpay.dto.ProviderRow;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.AdminStatisticsService;
import com.fluxpay.service.ReportQueryService;
import com.fluxpay.web.advice.TicketApiExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
  @MockBean AdminStatisticsService statistics;
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

  @Test
  void adminCanReadStatisticsOptions() throws Exception {
    when(statistics.options())
        .thenReturn(
            new AdminStatisticsOptionsResponse(
                List.of(new AdminStatisticsOptionsResponse.Currency("INR", 2)),
                "INR",
                "Asia/Kolkata",
                LocalDate.parse("2026-09-25"),
                366));

    mvc.perform(
            get("/api/admin/reports/statistics/options")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .header("X-Correlation-ID", "statistics-options"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.defaultCurrency").value("INR"))
        .andExpect(jsonPath("$.correlationId").value("statistics-options"));
  }

  @Test
  void adminCanReadStatisticsSummary() throws Exception {
    when(statistics.summary("2026-09-24", "2026-09-25", "INR")).thenReturn(sampleStatistics());

    mvc.perform(
            get("/api/admin/reports/statistics")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.paymentSummary.paymentCount").value(6));
  }

  @Test
  void adminCanReadPaginatedStatisticsPayments() throws Exception {
    var page =
        new AdminStatisticsPaymentPageResponse(
            sampleStatistics().meta(), null, 0, 20, 0, 0, List.of());
    when(statistics.payments("2026-09-24", "2026-09-25", "INR", null, null, null)).thenReturn(page);

    mvc.perform(
            get("/api/admin/reports/statistics/payments")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.items").isArray());
  }

  @Test
  void allStatisticsRoutesDenyUsersAndAnonymousRequests() throws Exception {
    for (String path :
        List.of(
            "/api/admin/reports/statistics/options",
            "/api/admin/reports/statistics?from=2026-09-24&to=2026-09-25&currency=INR",
            "/api/admin/reports/statistics/payments?from=2026-09-24&to=2026-09-25&currency=INR")) {
      mvc.perform(get(path).header("Authorization", "Bearer " + USER_TOKEN))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
  }

  @Test
  void statisticsBusinessErrorsUseExistingApiErrorEnvelope() throws Exception {
    when(statistics.summary("bad", "2026-09-25", "INR"))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_REPORT_RANGE", "Use YYYY-MM-DD dates."));

    mvc.perform(
            get("/api/admin/reports/statistics")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "bad")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"))
        .andExpect(jsonPath("$.message").value("Use YYYY-MM-DD dates."));
  }

  @Test
  void missingFromUsesTheReportRangeErrorEnvelope() throws Exception {
    when(statistics.summary(null, "2026-09-25", "INR"))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_RANGE",
                "Use valid YYYY-MM-DD report dates."));

    mvc.perform(
            get("/api/admin/reports/statistics")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .header("X-Correlation-ID", "missing-report-from")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"))
        .andExpect(jsonPath("$.correlationId").value("missing-report-from"))
        .andExpect(header().string("X-Correlation-ID", "missing-report-from"));
  }

  @Test
  void missingToUsesTheReportRangeErrorEnvelope() throws Exception {
    when(statistics.summary("2026-09-24", null, "INR"))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_RANGE",
                "Use valid YYYY-MM-DD report dates."));

    mvc.perform(
            get("/api/admin/reports/statistics")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("currency", "INR"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"));
  }

  @Test
  void missingCurrencyUsesTheCurrencyErrorEnvelope() throws Exception {
    when(statistics.summary("2026-09-24", "2026-09-25", null))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_CURRENCY",
                "Select a supported report currency."));

    mvc.perform(
            get("/api/admin/reports/statistics")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("to", "2026-09-25"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_CURRENCY"));
  }

  @Test
  void unsupportedPaymentStatusUsesTheStatusErrorEnvelope() throws Exception {
    when(statistics.payments("2026-09-24", "2026-09-25", "INR", "WHAT", null, null))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_REPORT_STATUS", "Select a valid payment status."));

    mvc.perform(
            get("/api/admin/reports/statistics/payments")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR")
                .queryParam("status", "WHAT"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_STATUS"));
  }

  @Test
  void malformedPaymentPageUsesThePageErrorEnvelope() throws Exception {
    when(statistics.payments("2026-09-24", "2026-09-25", "INR", null, "abc", null))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_PAGE",
                "Page must be non-negative and size must be from 1 to 100."));

    mvc.perform(
            get("/api/admin/reports/statistics/payments")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR")
                .queryParam("page", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_PAGE"));
  }

  @Test
  void invalidPaymentPageSizeUsesThePageErrorEnvelope() throws Exception {
    when(statistics.payments("2026-09-24", "2026-09-25", "INR", null, null, "0"))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_PAGE",
                "Page must be non-negative and size must be from 1 to 100."));

    mvc.perform(
            get("/api/admin/reports/statistics/payments")
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .queryParam("from", "2026-09-24")
                .queryParam("to", "2026-09-25")
                .queryParam("currency", "INR")
                .queryParam("size", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REPORT_PAGE"));
  }

  private static AdminStatisticsResponse sampleStatistics() {
    var meta =
        new AdminStatisticsResponse.Metadata(
            LocalDate.parse("2026-09-24"),
            LocalDate.parse("2026-09-25"),
            "INR",
            2,
            "Asia/Kolkata",
            Instant.parse("2026-09-23T18:30:00Z"),
            Instant.parse("2026-09-25T12:00:00Z"),
            Instant.parse("2026-09-25T12:00:00Z"),
            "PAYMENT_CREATED_AT");
    return new AdminStatisticsResponse(
        meta,
        new AdminStatisticsResponse.PaymentSummary(6, 2, "125.00", new BigDecimal("50.00"), 1, 1),
        List.of(),
        List.of(),
        List.of(),
        new AdminStatisticsResponse.CustomerSummary(0, 0),
        List.of(),
        new AdminStatisticsResponse.Workload(0, 0, 0, 0, 0, 0, 0));
  }
}
