package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.dto.ProviderRow;
import com.fluxpay.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ReportQueryServiceTest {
  private final JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
  private final ReportQueryService reports = new ReportQueryService(jdbc);

  @Test
  void rejectsMissingOrReversedReportRange() {
    assertThatThrownBy(
            () ->
                reports.providerSummary(
                    Instant.parse("2026-09-18T01:00:00Z"), Instant.parse("2026-09-18T01:00:00Z")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(400);
              assertThat(error.code()).isEqualTo("INVALID_REPORT_RANGE");
            });
  }

  @Test
  @SuppressWarnings("unchecked")
  void queriesProviderSummaryReadOnlyAggregate() {
    ProviderRow expected = new ProviderRow("Bank A", 3, 2, 1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenReturn(List.of(expected));

    List<ProviderRow> result =
        reports.providerSummary(
            Instant.parse("2026-09-18T00:00:00Z"), Instant.parse("2026-09-19T00:00:00Z"));

    assertThat(result).containsExactly(expected);
    verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
  }
}
