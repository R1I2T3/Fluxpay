package com.fluxpay.repository;

import com.fluxpay.config.M5ApiException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only M5 adapter for the M1/M2/M3 database contracts. */
public final class M5PaymentObservationRepository {
  /*
   * Oracle provides statement-level read consistency for this single SELECT, including
   * the joined KYC/profile values and correlated history. A chain of repository reads
   * under READ_COMMITTED would not provide the same guarantee. History status is the
   * status visible to this statement; created_at is bounded by the supplied cutoff.
   */
  private static final String OBSERVATION_SQL = """
      SELECT p.id AS payment_id, p.sender_id, p.sender_wallet_id, p.recipient_id,
             p.amount, p.currency, p.payout_currency, p.purpose, p.recipient_snapshot,
             p.recipient_version, p.m3_flow_version, p.created_at,
             w.user_id AS wallet_owner, w.currency AS wallet_currency,
             r.user_id AS recipient_owner, r.version AS current_recipient_version,
             k.id AS kyc_id, k.status AS kyc_status,
             (SELECT COUNT(*) FROM payments h
                JOIN wallets hw ON hw.id = h.sender_wallet_id
               WHERE hw.user_id = p.sender_id
                 AND (h.sender_id IS NULL OR h.sender_id = p.sender_id)
                 AND h.recipient_id = p.recipient_id AND h.id <> p.id
                 AND h.created_at <= ? AND h.status = 'COMPLETED') AS prior_completed_count,
             (SELECT COUNT(*) FROM payments h
                JOIN wallets hw ON hw.id = h.sender_wallet_id
               WHERE hw.user_id = p.sender_id
                 AND (h.sender_id IS NULL OR h.sender_id = p.sender_id)
                 AND h.recipient_id = p.recipient_id AND h.id <> p.id
                 AND h.created_at >= ? AND h.created_at <= ?
                 AND (? = 'ALL_ATTEMPTS' OR h.status = 'COMPLETED')) AS recipient_today_count
        FROM payments p
        LEFT JOIN wallets w ON w.id = p.sender_wallet_id
        LEFT JOIN recipients r ON r.id = p.recipient_id
        LEFT JOIN kyc_cases k ON k.user_id = p.sender_id
       WHERE p.id = ?
      """;

  private final JdbcTemplate jdbc;

  public M5PaymentObservationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Observation> observe(UUID paymentId, Instant dayStart, Instant cutoff, String mode) {
    try {
      List<Observation> rows = jdbc.query(OBSERVATION_SQL, ps -> {
        ps.setTimestamp(1, Timestamp.from(cutoff), utc());
        ps.setTimestamp(2, Timestamp.from(dayStart), utc());
        ps.setTimestamp(3, Timestamp.from(cutoff), utc());
        ps.setString(4, mode);
        ps.setBytes(5, raw(paymentId));
      }, (rs, index) -> new Observation(
          uuid(rs, "payment_id"), uuid(rs, "sender_id"), uuid(rs, "sender_wallet_id"),
          uuid(rs, "recipient_id"), rs.getBigDecimal("amount"), rs.getString("currency"),
          rs.getString("payout_currency"), rs.getString("purpose"), rs.getString("recipient_snapshot"),
          number(rs, "recipient_version"), number(rs, "m3_flow_version"),
          timestamp(rs, "created_at"), uuid(rs, "wallet_owner"), rs.getString("wallet_currency"),
          uuid(rs, "recipient_owner"), number(rs, "current_recipient_version"),
          uuid(rs, "kyc_id"), rs.getString("kyc_status"),
          rs.getLong("prior_completed_count"), rs.getLong("recipient_today_count")));
      return one(rows);
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  /** Owner lookup deliberately does not depend on recipient, KYC, purpose or history. */
  public Optional<Owner> owner(UUID paymentId) {
    try {
      return one(jdbc.query("""
          SELECT p.sender_id, w.user_id AS wallet_owner
            FROM payments p LEFT JOIN wallets w ON w.id = p.sender_wallet_id
           WHERE p.id = ?
          """, (rs, index) -> new Owner(uuid(rs, "sender_id"), uuid(rs, "wallet_owner")), raw(paymentId)));
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  private static <T> Optional<T> one(List<T> rows) {
    if (rows.size() > 1) throw unavailable();
    return rows.stream().findFirst();
  }

  private static Long number(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Instant timestamp(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column, utc());
    return value == null ? null : value.toInstant();
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    byte[] value = rs.getBytes(column);
    if (value == null) return null;
    if (value.length != 16) throw unavailable();
    ByteBuffer bytes = ByteBuffer.wrap(value);
    return new UUID(bytes.getLong(), bytes.getLong());
  }

  private static byte[] raw(UUID value) {
    return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits()).array();
  }

  private static Calendar utc() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  }

  private static M5ApiException unavailable() {
    return new M5ApiException(503, "PAYMENT_DATA_UNAVAILABLE", "Authoritative payment data is unavailable");
  }

  public record Observation(UUID paymentId, UUID senderId, UUID walletId, UUID recipientId,
      BigDecimal amount, String sourceCurrency, String payoutCurrency, String purpose,
      String recipientSnapshot, Long recipientVersion, Long flowVersion, Instant createdAt,
      UUID walletOwner, String walletCurrency, UUID recipientOwner, Long currentRecipientVersion,
      UUID kycId, String kycStatus, long priorCompletedCount, long recipientTodayCount) {}

  public record Owner(UUID declaredSender, UUID walletOwner) {}
}
