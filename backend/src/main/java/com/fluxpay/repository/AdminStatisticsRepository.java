package com.fluxpay.repository;

import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsOptionsResponse;
import com.fluxpay.dto.AdminStatisticsQuery;
import com.fluxpay.dto.AdminStatisticsResponse;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only, bounded Oracle aggregates for the admin statistics report. */
@Repository
public class AdminStatisticsRepository {
  private static final String REPORTING_ZONE = "Asia/Kolkata";
  private final JdbcTemplate jdbc;
  private final ZoneId storageZone;
  private final TimeZone storageTimeZone;

  public AdminStatisticsRepository(JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory) {
    this.jdbc = jdbc;
    TimeZone configured =
        entityManagerFactory
            .unwrap(SessionFactoryImplementor.class)
            .getSessionFactoryOptions()
            .getJdbcTimeZone();
    this.storageTimeZone =
        configured == null ? TimeZone.getDefault() : (TimeZone) configured.clone();
    this.storageZone = storageTimeZone.toZoneId();
  }

  public List<AdminStatisticsOptionsResponse.Currency> currencies() {
    return jdbc.query(
        "SELECT TRIM(code) AS code, scale FROM currencies ORDER BY code",
        (rs, row) ->
            new AdminStatisticsOptionsResponse.Currency(rs.getString("code"), rs.getInt("scale")));
  }

  public List<PaymentBucket> paymentBuckets(AdminStatisticsQuery query) {
    String sql =
        "SELECT created_day, status, COUNT(*) AS payment_count, "
            + "SUM(CASE WHEN status = 'COMPLETED' THEN amount ELSE 0 END) AS completed_amount "
            + "FROM (SELECT TO_CHAR(FROM_TZ(p.created_at, ?) AT TIME ZONE '"
            + REPORTING_ZONE
            + "', "
            + "'YYYY-MM-DD') AS created_day, p.status, p.amount FROM payments p "
            + "WHERE p.created_at >= ? AND p.created_at < ? AND p.currency = ?) "
            + "GROUP BY created_day, status ORDER BY created_day, status";
    return jdbc.query(
        sql,
        statement -> {
          statement.setString(1, storageZone.getId());
          bindTimestamp(statement, 2, query.fromInclusive());
          bindTimestamp(statement, 3, query.toExclusive());
          statement.setString(4, query.currency());
        },
        (rs, row) ->
            new PaymentBucket(
                LocalDate.parse(rs.getString("created_day")),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getLong("payment_count"),
                rs.getBigDecimal("completed_amount")));
  }

  public long totalCustomers(Instant cutoff) {
    Long count =
        jdbc.query(
            "SELECT COUNT(*) FROM users WHERE role = 'USER' AND created_at < ?",
            statement -> bindTimestamp(statement, 1, cutoff),
            rs -> rs.next() ? rs.getLong(1) : 0L);
    return count == null ? 0L : count;
  }

  public List<CustomerBucket> registrations(AdminStatisticsQuery query) {
    String sql =
        "SELECT created_day, COUNT(*) AS registrations FROM ("
            + "SELECT TO_CHAR(FROM_TZ(u.created_at, ?) AT TIME ZONE '"
            + REPORTING_ZONE
            + "', "
            + "'YYYY-MM-DD') AS created_day FROM users u "
            + "WHERE u.role = 'USER' AND u.created_at >= ? AND u.created_at < ?) "
            + "GROUP BY created_day ORDER BY created_day";
    return jdbc.query(
        sql,
        statement -> {
          statement.setString(1, storageZone.getId());
          bindTimestamp(statement, 2, query.fromInclusive());
          bindTimestamp(statement, 3, query.toExclusive());
        },
        (rs, row) ->
            new CustomerBucket(
                LocalDate.parse(rs.getString("created_day")), rs.getLong("registrations")));
  }

  public List<ProviderAggregate> providers(AdminStatisticsQuery query) {
    String sql =
        "SELECT v.id AS provider_id, v.provider_code, v.provider_name, "
            + "COUNT(*) AS total_attempts, "
            + "SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_attempts, "
            + "SUM(CASE WHEN a.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_attempts, "
            + "SUM(CASE WHEN a.status IN ('INITIATED', 'PROCESSING') THEN 1 ELSE 0 END) "
            + "AS in_progress_attempts "
            + "FROM payments p "
            + "JOIN payout_attempts a ON REPLACE(UPPER(TRIM(a.payment_id)), '-', '') = RAWTOHEX(p.id) "
            + "JOIN transfer_routes r ON r.id = a.transfer_route_id "
            + "JOIN transfer_providers v ON v.id = r.provider_id "
            + "WHERE p.created_at >= ? AND p.created_at < ? AND p.currency = ? "
            + "AND a.initiated_at < ? "
            + "GROUP BY v.id, v.provider_code, v.provider_name "
            + "ORDER BY total_attempts DESC, v.provider_code";
    return jdbc.query(
        sql,
        statement -> {
          bindTimestamp(statement, 1, query.fromInclusive());
          bindTimestamp(statement, 2, query.toExclusive());
          statement.setString(3, query.currency());
          statement.setObject(4, query.generatedAt().atOffset(ZoneOffset.UTC));
        },
        (rs, row) ->
            new ProviderAggregate(
                uuid(rs.getBytes("provider_id")),
                rs.getString("provider_code"),
                rs.getString("provider_name"),
                rs.getLong("total_attempts"),
                rs.getLong("completed_attempts"),
                rs.getLong("failed_attempts"),
                rs.getLong("in_progress_attempts")));
  }

  public AdminStatisticsResponse.Workload workload(Instant cutoff) {
    Instant agedCutoff = cutoff.minusSeconds(24 * 60 * 60L);
    long[] kyc =
        aggregate(
            "SELECT COUNT(*) AS pending, "
                + "COALESCE(SUM(CASE WHEN submitted_at < ? THEN 1 ELSE 0 END), 0) AS aged "
                + "FROM kyc_cases WHERE status = 'PENDING' AND submitted_at < ?",
            agedCutoff,
            cutoff);
    long[] compliance =
        aggregate(
            "SELECT COUNT(*) AS open_count, "
                + "COALESCE(SUM(CASE WHEN risk = 'HIGH' THEN 1 ELSE 0 END), 0) AS high_risk, "
                + "COALESCE(SUM(CASE WHEN created_at < ? THEN 1 ELSE 0 END), 0) AS aged "
                + "FROM compliance_cases WHERE status = 'OPEN' AND created_at < ?",
            agedCutoff,
            cutoff);
    long[] tickets =
        aggregateWithTimestampsWithTimeZone(
            "SELECT COUNT(*) AS open_count, "
                + "COALESCE(SUM(CASE WHEN created_at < ? THEN 1 ELSE 0 END), 0) AS aged "
                + "FROM support_tickets WHERE status IN ('OPEN', 'IN_PROGRESS') AND created_at < ?",
            agedCutoff,
            cutoff);
    return new AdminStatisticsResponse.Workload(
        kyc[0], kyc[1], compliance[0], compliance[1], compliance[2], tickets[0], tickets[1]);
  }

  private long[] aggregate(String sql, Instant agedCutoff, Instant cutoff) {
    return jdbc.query(
        sql,
        statement -> {
          bindTimestamp(statement, 1, agedCutoff);
          bindTimestamp(statement, 2, cutoff);
        },
        rs -> {
          if (!rs.next()) {
            return new long[0];
          }
          long[] values = new long[rs.getMetaData().getColumnCount()];
          for (int index = 0; index < values.length; index++) {
            values[index] = rs.getLong(index + 1);
          }
          return values;
        });
  }

  private long[] aggregateWithTimestampsWithTimeZone(
      String sql, Instant agedCutoff, Instant cutoff) {
    return jdbc.query(
        sql,
        statement -> {
          statement.setObject(1, agedCutoff.atOffset(ZoneOffset.UTC));
          statement.setObject(2, cutoff.atOffset(ZoneOffset.UTC));
        },
        rs -> {
          if (!rs.next()) {
            return new long[0];
          }
          long[] values = new long[rs.getMetaData().getColumnCount()];
          for (int index = 0; index < values.length; index++) {
            values[index] = rs.getLong(index + 1);
          }
          return values;
        });
  }

  private void bindTimestamp(PreparedStatement statement, int index, Instant instant)
      throws SQLException {
    statement.setTimestamp(
        index, Timestamp.from(instant), Calendar.getInstance((TimeZone) storageTimeZone.clone()));
  }

  public record PaymentBucket(
      LocalDate date, PaymentStatus status, long count, BigDecimal completedAmount) {}

  public record CustomerBucket(LocalDate date, long count) {}

  public record ProviderAggregate(
      java.util.UUID providerId,
      String providerCode,
      String providerName,
      long totalAttempts,
      long completedAttempts,
      long failedAttempts,
      long inProgressAttempts) {}

  private static java.util.UUID uuid(byte[] bytes) {
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
    return new java.util.UUID(buffer.getLong(), buffer.getLong());
  }
}
