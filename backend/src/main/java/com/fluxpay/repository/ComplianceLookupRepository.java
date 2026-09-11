package com.fluxpay.repository;

import com.fluxpay.common.util.UuidRawCodec;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only lookups into tables owned by other Member modules ({@code payments}, {@code wallets},
 * {@code recipients}, {@code kyc_cases}).
 *
 * <p>Deliberately plain JDBC rather than a shadow JPA {@code @Entity} for those tables: Members
 * 1-3 will eventually add their own entities for them, and a second, independent {@code @Entity}
 * mapped to the same table is an easy source of confusion (which one is the source of truth?).
 * Reading through {@link org.springframework.jdbc.core.JdbcTemplate} needs no entity at all and
 * can't collide with anyone else's mapping.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Repository
public class ComplianceLookupRepository {

  private final JdbcTemplate jdbcTemplate;

  public ComplianceLookupRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<PaymentFacts> findPaymentFacts(UUID paymentId) {
    String sql =
        "SELECT p.id, p.amount, p.currency, p.created_at, p.recipient_id, w.user_id "
            + "FROM payments p JOIN wallets w ON w.id = p.sender_wallet_id "
            + "WHERE p.id = ?";
    List<PaymentFacts> rows =
        jdbcTemplate.query(sql, this::mapPaymentFacts, UuidRawCodec.toBytes(paymentId));
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private PaymentFacts mapPaymentFacts(ResultSet rs, int rowNum) throws SQLException {
    return new PaymentFacts(
        UuidRawCodec.fromBytes(rs.getBytes("id")),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getTimestamp("created_at").toInstant(),
        UuidRawCodec.fromBytes(rs.getBytes("recipient_id")),
        UuidRawCodec.fromBytes(rs.getBytes("user_id")));
  }

  /** True only if the payer has a kyc_cases row with status VERIFIED. */
  public boolean isKycVerified(UUID userId) {
    String sql = "SELECT status FROM kyc_cases WHERE user_id = ?";
    List<String> statuses =
        jdbcTemplate.query(
            sql, (rs, rowNum) -> rs.getString("status"), UuidRawCodec.toBytes(userId));
    return !statuses.isEmpty() && "VERIFIED".equals(statuses.get(0));
  }

  public Instant recipientCreatedAt(UUID recipientId) {
    String sql = "SELECT created_at FROM recipients WHERE id = ?";
    List<Instant> rows =
        jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getTimestamp("created_at").toInstant(),
            UuidRawCodec.toBytes(recipientId));
    return rows.isEmpty() ? null : rows.get(0);
  }

  public long countPriorPaymentsToRecipient(UUID recipientId, UUID excludingPaymentId) {
    String sql = "SELECT COUNT(*) FROM payments WHERE recipient_id = ? AND id <> ?";
    Long count =
        jdbcTemplate.queryForObject(
            sql,
            Long.class,
            UuidRawCodec.toBytes(recipientId),
            UuidRawCodec.toBytes(excludingPaymentId));
    return count == null ? 0 : count;
  }

  public record PaymentFacts(
      UUID paymentId,
      BigDecimal amount,
      String currency,
      Instant createdAt,
      UUID recipientId,
      UUID payerUserId) {}
}
