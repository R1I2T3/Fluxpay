package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.ProviderRow;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.ReportQueryService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportController {
  private final ReportQueryService reports;

  public AdminReportController(ReportQueryService reports) {
    this.reports = reports;
  }

  @GetMapping("/provider-summary")
  public ApiResponse<List<ProviderRow>> providerSummary(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return envelope(reports.providerSummary(parseInstant(from, "from"), parseInstant(to, "to")));
  }

  private Instant parseInstant(String value, String parameter) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REPORT_RANGE",
          "Report " + parameter + " must be an ISO-8601 instant.");
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REPORT_RANGE",
          "Report " + parameter + " must be an ISO-8601 instant.");
    }
  }

  private <T> ApiResponse<T> envelope(T data) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(correlationId == null ? "none" : correlationId, data);
  }
}
