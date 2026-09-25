package com.fluxpay.repository;

import com.fluxpay.domain.PaymentStatus;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Isolated rows for the admin statistics Oracle integration tests. */
class AdminStatisticsFixture {
  private final JdbcTemplate jdbc;

  @SuppressWarnings("unused")
  private final EntityManager entityManager;

  AdminStatisticsFixture(JdbcTemplate jdbc, EntityManager entityManager) {
    this.jdbc = jdbc;
    this.entityManager = entityManager;
  }

  UUID customer(String role, Instant createdAt) {
    UUID userId = UUID.randomUUID();
    String suffix = userId.toString();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,role,full_name,created_at,updated_at) "
            + "VALUES (?,?,'!ORACLE_TEST_NO_LOGIN!',?,'Statistics fixture',?,?)",
        raw(userId),
        suffix + "@stats-test.invalid",
        role,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    UUID walletId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO wallets(id,user_id,currency,account_role) VALUES (?,?,'INR','CUSTOMER')",
        raw(walletId),
        raw(userId));
    jdbc.update(
        "INSERT INTO recipients(id,user_id,name,account_ref,country,currency,status,profile_complete) "
            + "VALUES (?,?,'Statistics recipient',?,'IN','INR','ACTIVE',1)",
        raw(UUID.randomUUID()),
        raw(userId),
        "acct-" + suffix);
    return userId;
  }

  UUID payment(String currency, String amount, PaymentStatus status, Instant createdAt) {
    UUID userId = customer("USER", createdAt);
    UUID walletId =
        jdbc.queryForObject(
            "SELECT id FROM wallets WHERE user_id=? AND account_role='CUSTOMER'",
            (rs, row) -> uuid(rs.getBytes(1)),
            raw(userId));
    UUID recipientId =
        jdbc.queryForObject(
            "SELECT id FROM recipients WHERE user_id=?",
            (rs, row) -> uuid(rs.getBytes(1)),
            raw(userId));
    UUID paymentId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payments(id,sender_wallet_id,recipient_id,amount,currency,status,sender_id,created_at) "
            + "VALUES (?,?,?,?,?,?,?,?)",
        raw(paymentId),
        raw(walletId),
        raw(recipientId),
        new java.math.BigDecimal(amount),
        currency,
        status.name(),
        raw(userId),
        Timestamp.from(createdAt));
    return paymentId;
  }

  UUID provider(String code, boolean archived) {
    String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-01T00:00:00Z");
    jdbc.update(
        "INSERT INTO transfer_providers(id,provider_code,provider_name,rail_type,active,system_protected,version,created_at,updated_at,archived_at) "
            + "VALUES (?,?,?,'BANK_NETWORK',?,0,0,?,?,?)",
        raw(id),
        code + "_" + suffix,
        "Statistics provider",
        archived ? 0 : 1,
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
        archived ? OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC) : null);
    return id;
  }

  UUID route(UUID providerId) {
    String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-01T00:00:00Z");
    jdbc.update(
        "INSERT INTO transfer_routes(id,provider_id,route_code,route_name,destination_type,destination_country,payout_currency,base_fee,fx_spread_percentage,estimated_minutes,configured_success_rate,active,system_protected,version,created_at,updated_at) "
            + "VALUES (?,?,?,'Statistics route','EXTERNAL_ACCOUNT','IN','INR',0,0,1,99,1,0,0,?,?)",
        raw(id),
        raw(providerId),
        "ROUTE_" + suffix,
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    return id;
  }

  void attempt(UUID paymentId, UUID routeId, int number, String status, Instant initiatedAt) {
    jdbc.update(
        "INSERT INTO payout_attempts(payment_id,transfer_route_id,attempt_number,status,initiated_at) "
            + "VALUES (?,?,?, ?,?)",
        paymentId.toString(),
        raw(routeId),
        number,
        status,
        OffsetDateTime.ofInstant(initiatedAt, java.time.ZoneOffset.UTC));
  }

  void standaloneAttempt(String paymentId, UUID routeId, Instant initiatedAt) {
    jdbc.update(
        "INSERT INTO payout_attempts(payment_id,transfer_route_id,attempt_number,status,initiated_at) "
            + "VALUES (?,?,1,'INITIATED',?)",
        paymentId,
        raw(routeId),
        OffsetDateTime.ofInstant(initiatedAt, java.time.ZoneOffset.UTC));
  }

  void kyc(Instant submittedAt, String status) {
    UUID userId = customer("USER", submittedAt.minusSeconds(30L * 86400));
    UUID reviewer = "PENDING".equals(status) ? null : customer("ADMIN", submittedAt);
    Object[] common = {
      raw(UUID.randomUUID()),
      raw(userId),
      status,
      Timestamp.from(submittedAt.minusSeconds(30L * 86400)),
      Timestamp.from(submittedAt)
    };
    if ("PENDING".equals(status)) {
      jdbc.update(
          "INSERT INTO kyc_cases(id,user_id,status,doc_type,doc_number,created_at,submitted_at) "
              + "VALUES (?,?,?,'AADHAAR',?,?,?)",
          common[0],
          common[1],
          common[2],
          "DOC-" + UUID.randomUUID(),
          common[3],
          common[4]);
    } else {
      jdbc.update(
          "INSERT INTO kyc_cases(id,user_id,status,doc_type,doc_number,created_at,submitted_at,decided_by,decided_at,reject_reason) "
              + "VALUES (?,?,?,'AADHAAR',?,?,?,?,?,?)",
          common[0],
          common[1],
          common[2],
          "DOC-" + UUID.randomUUID(),
          common[3],
          common[4],
          raw(reviewer),
          Timestamp.from(submittedAt),
          "REJECTED".equals(status) ? "fixture rejection" : null);
    }
  }

  void compliance(UUID paymentId, String risk, String status, Instant createdAt) {
    jdbc.update(
        "INSERT INTO compliance_cases(payment_id,risk,status,risk_reasons,suggested_action,created_at) "
            + "VALUES (?,?,?,'{}','Review statistics fixture',?)",
        raw(paymentId),
        risk,
        status,
        Timestamp.from(createdAt));
  }

  void ticket(String status, Instant createdAt) {
    UUID userId = customer("USER", createdAt);
    Instant time = createdAt;
    jdbc.update(
        "INSERT INTO support_tickets(id,user_id,subject,body,status,created_at,updated_at) "
            + "VALUES (?,?,'Statistics fixture','Fixture body',?,?,?)",
        raw(UUID.randomUUID()),
        raw(userId),
        status,
        OffsetDateTime.ofInstant(time, java.time.ZoneOffset.UTC),
        OffsetDateTime.ofInstant(time, java.time.ZoneOffset.UTC));
  }

  private static byte[] raw(UUID id) {
    return ByteBuffer.allocate(16)
        .putLong(id.getMostSignificantBits())
        .putLong(id.getLeastSignificantBits())
        .array();
  }

  private static UUID uuid(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }
}
