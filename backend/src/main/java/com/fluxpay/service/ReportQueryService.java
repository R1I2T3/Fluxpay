package com.fluxpay.service;

import com.fluxpay.dto.ProviderRow;
import com.fluxpay.exception.BusinessException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportQueryService {
  private static final String PROVIDER_SUMMARY_SQL =
      """
      SELECT v.provider_name,
             COUNT(a.id) AS total_attempts,
             SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_attempts,
             SUM(CASE WHEN a.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_attempts
      FROM transfer_providers v
      LEFT JOIN transfer_routes r ON r.provider_id = v.id
      LEFT JOIN payout_attempts a
        ON a.transfer_route_id = r.id
       AND a.initiated_at >= ?
       AND a.initiated_at < ?
      GROUP BY v.provider_name
      ORDER BY v.provider_name
      """;

  private final JdbcTemplate jdbc;

  public ReportQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public List<ProviderRow> providerSummary(Instant from, Instant to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REPORT_RANGE",
          "Report start time must be before the end time.");
    }
    return jdbc.query(
        PROVIDER_SUMMARY_SQL,
        statement -> {
          statement.setObject(1, from.atOffset(ZoneOffset.UTC));
          statement.setObject(2, to.atOffset(ZoneOffset.UTC));
        },
        (resultSet, rowNumber) ->
            new ProviderRow(
                resultSet.getString("provider_name"),
                resultSet.getLong("total_attempts"),
                resultSet.getLong("completed_attempts"),
                resultSet.getLong("failed_attempts")));
  }
}
