package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.config.M5ComplianceSettings;
import com.fluxpay.repository.M5PaymentObservationRepository;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the production observation SQL against actual Oracle-shaped tables. */
class M5DatabasePaymentReaderTest {
  private static final UUID PAYMENT = UUID.fromString("50000000-0000-0000-0000-000000000101");
  private static final UUID SENDER = UUID.fromString("50000000-0000-0000-0000-000000000001");
  private static final UUID WALLET = UUID.fromString("50000000-0000-0000-0000-000000000011");
  private static final UUID RECIPIENT = UUID.fromString("50000000-0000-0000-0000-000000000021");
  private static final UUID OTHER = UUID.fromString("50000000-0000-0000-0000-000000000002");
  private static final Instant NOW = Instant.parse("2026-09-13T12:30:00Z");
  private static final Instant START = Instant.parse("2026-09-12T18:30:00Z");
  private static final String SNAPSHOT =
      "{\"name\":\"Recipient\",\"account\":\"123\",\"bankName\":\"Bank\",\"country\":\"IN\",\"currency\":\"INR\"}";
  private JdbcTemplate jdbc;

  @BeforeEach
  void database() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        "jdbc:h2:mem:m5reader-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("CREATE TABLE wallets(id RAW(16) PRIMARY KEY, user_id RAW(16), currency VARCHAR2(3))");
    jdbc.execute("CREATE TABLE recipients(id RAW(16) PRIMARY KEY, user_id RAW(16), country VARCHAR2(2), version NUMBER(10))");
    jdbc.execute("CREATE TABLE kyc_cases(id RAW(16) PRIMARY KEY, user_id RAW(16), status VARCHAR2(20))");
    jdbc.execute("CREATE TABLE payments(id RAW(16) PRIMARY KEY, sender_id RAW(16), sender_wallet_id RAW(16), recipient_id RAW(16), amount NUMBER(19,4), currency VARCHAR2(3), payout_currency VARCHAR2(3), purpose VARCHAR2(30), recipient_snapshot CLOB, recipient_version NUMBER(10), m3_flow_version NUMBER(1), status VARCHAR2(20), created_at TIMESTAMP)");
    jdbc.update("INSERT INTO wallets VALUES (?, ?, 'USD')", raw(WALLET), raw(SENDER));
    jdbc.update("INSERT INTO recipients VALUES (?, ?, 'IN', 0)", raw(RECIPIENT), raw(SENDER));
    jdbc.update("INSERT INTO kyc_cases VALUES (?, ?, 'VERIFIED')", raw(UUID.randomUUID()), raw(SENDER));
    payment(PAYMENT, SENDER, WALLET, RECIPIENT, "DRAFT", NOW.minusSeconds(60));
  }

  @Test
  void mapsAuthoritativeFieldsAndPreservesTheActualPurposeCategory() {
    var s = reader("ALL_ATTEMPTS").readForAssessment(PAYMENT);
    assertEquals(PAYMENT, s.paymentId());
    assertEquals(SENDER, s.senderId());
    assertEquals(WALLET, s.walletId());
    assertEquals(RECIPIENT, s.recipientId());
    assertEquals(new BigDecimal("75.0000"), s.sourceAmount());
    assertEquals("USD", s.sourceCurrency());
    assertEquals("INR", s.payoutCurrency());
    assertEquals("EDUCATION", s.purpose());
    assertTrue(s.purposeAvailable());
    assertEquals(SNAPSHOT, s.recipientSnapshot());
    assertEquals("IN", s.recipientCountry());
    assertEquals(0, s.recipientVersion());
    assertTrue(s.kycVerified());
    assertFalse(s.priorCompletedPayment());
    assertEquals(0L, s.recipientTodayCount());
    assertEquals(NOW, s.observedAt());
    assertEquals(NOW, s.historyCutoff());
    assertEquals(START, s.recipientDayStart());
    assertEquals("Asia/Kolkata", s.dayZone());
  }

  @Test
  void countsInclusiveDayBoundariesButExcludesCurrentFutureAndOtherOwnerOrRecipient() {
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "FAILED", START);
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "CANCELLED", NOW);
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "COMPLETED", START.minusSeconds(1));
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "COMPLETED", NOW.plusSeconds(1));
    payment(UUID.randomUUID(), SENDER, WALLET, UUID.randomUUID(), "COMPLETED", NOW);
    UUID otherWallet = UUID.randomUUID();
    jdbc.update("INSERT INTO wallets VALUES (?, ?, 'USD')", raw(otherWallet), raw(OTHER));
    payment(UUID.randomUUID(), OTHER, otherWallet, RECIPIENT, "COMPLETED", NOW);
    var s = reader("ALL_ATTEMPTS").readForAssessment(PAYMENT);
    assertEquals(2L, s.recipientTodayCount());
    assertTrue(s.priorCompletedPayment());
    assertEquals("ALL_ATTEMPTS", s.recipientTodayMode());
  }

  @Test
  void completedOnlyModeUsesCompletedPaymentsForTodayAndPriorRelationship() {
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "FAILED", START);
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "COMPLETED", NOW);
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "PROCESSING", NOW);
    var s = reader("COMPLETED_ONLY").readForAssessment(PAYMENT);
    assertEquals(1L, s.recipientTodayCount());
    assertTrue(s.priorCompletedPayment());
    assertEquals("COMPLETED_ONLY", s.recipientTodayMode());
  }

  @Test
  void currentCompletedPaymentAndFutureCompletionDoNotEstablishPriorRelationship() {
    jdbc.update("UPDATE payments SET status='COMPLETED' WHERE id=?", raw(PAYMENT));
    payment(UUID.randomUUID(), SENDER, WALLET, RECIPIENT, "COMPLETED", NOW.plusSeconds(1));
    var s = reader("COMPLETED_ONLY").readForAssessment(PAYMENT);
    assertFalse(s.priorCompletedPayment());
    assertEquals(0L, s.recipientTodayCount());
  }

  @Test
  void frozenCountryAndVersionRemainAuthoritativeAfterRecipientProfileChanges() {
    jdbc.update("UPDATE recipients SET country='RU', version=2 WHERE id=?", raw(RECIPIENT));
    var s = reader("ALL_ATTEMPTS").readForAssessment(PAYMENT);
    assertEquals("IN", s.recipientCountry());
    assertEquals(0, s.recipientVersion());
  }

  @Test
  void knownNullPurposeIsAvailableAndTriggersTheDocumentedRule() {
    jdbc.update("UPDATE payments SET purpose=NULL WHERE id=?", raw(PAYMENT));
    var s = reader("ALL_ATTEMPTS").readForAssessment(PAYMENT);
    assertNull(s.purpose());
    assertTrue(s.purposeAvailable());
    assertTrue(new M5ComplianceRulesEngine(settings("ALL_ATTEMPTS")).evaluate(s).reasons()
        .stream().anyMatch(r -> r.code().equals("SHORT_PURPOSE")));
  }

  @Test
  void rejectedAndNotSubmittedKycAreUnverifiedFollowingM1StatusSemantics() {
    jdbc.update("UPDATE kyc_cases SET status='REJECTED'");
    assertFalse(reader("ALL_ATTEMPTS").readForAssessment(PAYMENT).kycVerified());
    jdbc.update("DELETE FROM kyc_cases");
    assertFalse(reader("ALL_ATTEMPTS").readForAssessment(PAYMENT).kycVerified());
    assertEquals(SENDER, reader("ALL_ATTEMPTS").ownerOf(PAYMENT));
  }

  @Test
  void unrecognizedKycStatusIsUnavailable() {
    jdbc.update("UPDATE kyc_cases SET status='UNKNOWN'");
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
  }

  @Test
  void mismatchedWalletOrRecipientOwnershipCannotProduceAnObservation() {
    jdbc.update("UPDATE wallets SET user_id=? WHERE id=?", raw(OTHER), raw(WALLET));
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
    unavailable(() -> reader("ALL_ATTEMPTS").ownerOf(PAYMENT));
    jdbc.update("UPDATE wallets SET user_id=? WHERE id=?", raw(SENDER), raw(WALLET));
    jdbc.update("UPDATE recipients SET user_id=? WHERE id=?", raw(OTHER), raw(RECIPIENT));
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
  }

  @Test
  void missingOrIncompatibleSnapshotAndLegacyPaymentFailClosed() {
    for (String snapshot : new String[] {"{}", "{\"country\":null}", "[]", "not-json",
        "{\"country\":\"IN\",\"currency\":\"EUR\"}"}) {
      jdbc.update("UPDATE payments SET recipient_snapshot=? WHERE id=?", snapshot, raw(PAYMENT));
      unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
    }
    jdbc.update("UPDATE payments SET recipient_snapshot=?, recipient_version=3 WHERE id=?", SNAPSHOT, raw(PAYMENT));
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
    jdbc.update("UPDATE payments SET recipient_version=NULL WHERE id=?", raw(PAYMENT));
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
    jdbc.update("UPDATE payments SET recipient_version=0, m3_flow_version=0 WHERE id=?", raw(PAYMENT));
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
  }

  @Test
  void missingPaymentIsNotFoundAndMissingSchemaIsUnavailableWithoutSqlDetails() {
    var failure = assertThrows(M5ApiException.class,
        () -> reader("ALL_ATTEMPTS").readForAssessment(UUID.randomUUID()));
    assertEquals(404, failure.status());
    assertEquals("PAYMENT_NOT_FOUND", failure.code());
    jdbc.execute("ALTER TABLE payments DROP COLUMN purpose");
    unavailable(() -> reader("ALL_ATTEMPTS").readForAssessment(PAYMENT));
    assertEquals(SENDER, reader("ALL_ATTEMPTS").ownerOf(PAYMENT));
  }

  private M5PaymentReader reader(String mode) {
    return new DatabaseM5PaymentReader(new M5PaymentObservationRepository(jdbc),
        new ObjectMapper(), settings(mode), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private M5ComplianceSettings settings(String mode) {
    return new M5ComplianceSettings(Map.of("USD", new BigDecimal("1000")),
        ZoneId.of("Asia/Kolkata"), mode, Set.of("RU"));
  }

  private void unavailable(org.junit.jupiter.api.function.Executable operation) {
    var error = assertThrows(M5ApiException.class, operation);
    assertEquals(503, error.status());
    assertEquals("PAYMENT_DATA_UNAVAILABLE", error.code());
    assertFalse(error.getMessage().contains("SELECT"));
  }

  private void payment(UUID id, UUID sender, UUID wallet, UUID recipient, String status, Instant created) {
    jdbc.update("INSERT INTO payments VALUES (?, ?, ?, ?, 75.0000, 'USD', 'INR', 'EDUCATION', ?, 0, 1, ?, ?)",
        ps -> {
          ps.setBytes(1, raw(id)); ps.setBytes(2, raw(sender)); ps.setBytes(3, raw(wallet));
          ps.setBytes(4, raw(recipient)); ps.setString(5, SNAPSHOT); ps.setString(6, status);
          ps.setTimestamp(7, Timestamp.from(created), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        });
  }

  private static byte[] raw(UUID id) {
    return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
  }
}
