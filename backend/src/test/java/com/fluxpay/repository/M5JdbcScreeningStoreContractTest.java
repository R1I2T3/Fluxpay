package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fluxpay.beans.M5ReviewDecision;
import com.fluxpay.beans.M5ScreeningCase;
import com.fluxpay.beans.M5ScreeningHead;
import com.fluxpay.common.util.UuidRawCodec;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.config.M5ComplianceSettings;
import com.fluxpay.dto.M5AssessmentRequest;
import com.fluxpay.dto.M5AssessmentResponse;
import com.fluxpay.dto.M5PaymentSnapshot;
import com.fluxpay.dto.M5ReviewCommand;
import com.fluxpay.dto.M5RiskReason;
import com.fluxpay.service.M5ComplianceService;
import com.fluxpay.service.M5ComplianceRulesEngine;
import com.fluxpay.service.M5Fingerprints;
import com.fluxpay.service.M5PaymentReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** SQL contract tests using V705-shaped H2 tables; not a replacement for Oracle migration tests. */
class M5JdbcScreeningStoreContractTest {
  private static final Instant NOW = Instant.parse("2026-09-13T10:30:00.123456Z");
  private JdbcTemplate jdbc;
  private M5ScreeningStore store;
  private TransactionTemplate transaction;

  @BeforeEach
  void setUp() throws Exception {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:m5_store_" + UUID.randomUUID()
        + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=100");
    jdbc = new JdbcTemplate(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    jdbc.execute("""
        CREATE TABLE screening_cases (
          id RAW(16) PRIMARY KEY, payment_id RAW(16) NOT NULL,
          verdict VARCHAR2(20) NOT NULL, created_at TIMESTAMP NOT NULL,
          assessment_id RAW(16) NOT NULL UNIQUE, assessment_sequence NUMBER(19) NOT NULL,
          request_fingerprint VARCHAR2(64) NOT NULL, payment_fingerprint VARCHAR2(64) NOT NULL,
          risk VARCHAR2(10) NOT NULL, status VARCHAR2(20) NOT NULL,
          screening_verdict VARCHAR2(20) NOT NULL, risk_reasons CLOB NOT NULL,
          suggested_action VARCHAR2(400) NOT NULL, decided_by RAW(16), decided_at TIMESTAMP,
          decision_reason VARCHAR2(500), version NUMBER(10) DEFAULT 0 NOT NULL,
          assessment_snapshot CLOB NOT NULL, assessment_response CLOB NOT NULL,
          rule_version VARCHAR2(100) NOT NULL, rule_config_hash VARCHAR2(64) NOT NULL,
          review_reference RAW(16) UNIQUE, payment_disposition VARCHAR2(20),
          UNIQUE(payment_id, assessment_sequence), UNIQUE(id,payment_id,assessment_sequence),
          UNIQUE(id,assessment_id,payment_id,review_reference),
          CHECK (status IN ('UNDER_REVIEW','PROCESSING','APPROVED','REJECTED')),
          CHECK (risk IN ('LOW','MEDIUM','HIGH')), CHECK (version >= 0),
          CHECK ((status='UNDER_REVIEW' AND verdict='REVIEW')
            OR (status IN ('PROCESSING','APPROVED') AND verdict='APPROVE')
            OR (status='REJECTED' AND verdict='BLOCK'))
        )
        """);
    jdbc.execute("""
        CREATE TABLE m5_screening_heads (
          payment_id RAW(16) PRIMARY KEY, latest_case_id RAW(16),
          latest_sequence NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(10) DEFAULT 0 NOT NULL,
          FOREIGN KEY(latest_case_id,payment_id,latest_sequence)
            REFERENCES screening_cases(id,payment_id,assessment_sequence)
        )
        """);
    jdbc.execute("""
        CREATE TABLE m5_review_decisions (
          id RAW(16) PRIMARY KEY, case_id RAW(16) NOT NULL UNIQUE,
          review_reference RAW(16) NOT NULL UNIQUE, payment_id RAW(16) NOT NULL,
          assessment_id RAW(16) NOT NULL, payment_fingerprint VARCHAR2(64) NOT NULL,
          decision VARCHAR2(10) NOT NULL, reason VARCHAR2(500), reviewer_id RAW(16) NOT NULL,
          decided_at TIMESTAMP NOT NULL, delivery_state VARCHAR2(20) NOT NULL,
          retry_count NUMBER(10) NOT NULL, next_attempt_at TIMESTAMP NOT NULL,
          last_error_code VARCHAR2(100),
          FOREIGN KEY(case_id,assessment_id,payment_id,review_reference)
            REFERENCES screening_cases(id,assessment_id,payment_id,review_reference)
        )
        """);
    store = new JdbcM5ScreeningStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  @Test
  void caseRoundTripsStructuredEvidenceAndUsesRawUuidBytes() {
    var value = sample(UUID.randomUUID(), 1, "HIGH", NOW);
    store.insertCase(value);
    assertEquals(value, store.byAssessment(value.assessment().assessmentId()).orElseThrow());
    assertEquals(value, store.byCase(value.assessment().caseId(), false).orElseThrow());
    assertArrayEquals(UuidRawCodec.toBytes(value.assessment().caseId()),
        jdbc.queryForObject("SELECT id FROM screening_cases", byte[].class));
    var json = jdbc.queryForObject("SELECT risk_reasons FROM screening_cases", String.class);
    assertTrue(json.contains("KYC_UNVERIFIED"));
    assertTrue(json.contains("Verify sender identity"));
    assertTrue(store.byAssessment(UUID.randomUUID()).isEmpty());
    assertTrue(store.byCase(UUID.randomUUID(), true).isEmpty());
  }

  @Test
  void creatingAndPublishingHeadSupportsLockingAndRejectsLostUpdates() {
    UUID payment = UUID.randomUUID();
    var value = sample(payment, 4, "HIGH", NOW);
    assertTrue(store.head(payment, false).isEmpty());
    store.createHead(payment);
    assertEquals(new M5ScreeningHead(payment, null, 0, 0), store.head(payment, false).orElseThrow());
    assertThrows(DuplicateKeyException.class, () -> store.createHead(payment));
    transaction.executeWithoutResult(tx -> {
      assertEquals(0, store.head(payment, true).orElseThrow().latestSequence());
      store.insertCase(value);
      store.publishHead(new M5ScreeningHead(payment, value.assessment().caseId(), 4, 1));
      assertEquals(value, store.byCase(value.assessment().caseId(), true).orElseThrow());
    });
    assertEquals(4, store.head(payment, false).orElseThrow().latestSequence());
    assertThrows(OptimisticLockingFailureException.class,
        () -> store.publishHead(new M5ScreeningHead(payment, value.assessment().caseId(), 4, 1)));
  }

  @Test
  void headLockPreventsAConcurrentWriterUntilTransactionCompletes() {
    UUID payment = UUID.randomUUID();
    store.createHead(payment);
    transaction.executeWithoutResult(tx -> {
      store.head(payment, true).orElseThrow();
      var writer = CompletableFuture.runAsync(() -> jdbc.update(
          "UPDATE m5_screening_heads SET version=version+1 WHERE payment_id=?",
          UuidRawCodec.toBytes(payment)));
      var failure = assertThrows(CompletionException.class, writer::join);
      assertInstanceOf(QueryTimeoutException.class, failure.getCause());
    });
    assertEquals(0, store.head(payment, false).orElseThrow().version());
  }

  @Test
  void caseMutationNeverRewritesOriginalAssessmentAndRejectsStaleVersion() {
    var original = sample(UUID.randomUUID(), 1, "HIGH", NOW);
    store.insertCase(original);
    UUID reference = UUID.randomUUID();
    var active = activate(original, reference);
    store.updateCase(active);
    assertEquals(active, store.byCase(original.assessment().caseId(), false).orElseThrow());
    assertThrows(OptimisticLockingFailureException.class, () -> store.updateCase(active));
    var a = original.assessment();
    var forged = new M5AssessmentResponse(a.assessmentId(), a.assessmentSequence(), a.paymentId(),
        a.expectedPaymentFingerprint(), a.caseId(), "LOW", "APPROVE", List.of(), a.assessedAt(),
        "untrusted-replacement", a.ruleConfigHash());
    UUID reviewer = UUID.randomUUID();
    store.updateCase(new M5ScreeningCase(forged, null, "replacement", "APPROVED", "APPROVE",
        "replacement", "REVIEW_REQUIRED", reference, reviewer, NOW.plusSeconds(1), "checked", 2));
    var actual = store.byAssessment(a.assessmentId()).orElseThrow();
    assertEquals(original.assessment(), actual.assessment());
    assertEquals(original.snapshot(), actual.snapshot());
    assertEquals(original.requestFingerprint(), actual.requestFingerprint());
    assertEquals(original.suggestedAction(), actual.suggestedAction());
    assertEquals("APPROVED", actual.status());
    assertEquals(reviewer, actual.decidedBy());
    assertEquals("checked", actual.decisionReason());
    assertEquals(2, actual.version());
  }

  @Test
  void duplicateAssessmentOrPaymentSequenceCannotCreateAnAlternativeCase() {
    var first = sample(UUID.randomUUID(), 1, "HIGH", NOW);
    store.insertCase(first);
    assertThrows(DuplicateKeyException.class, () -> store.insertCase(first));
    assertThrows(DuplicateKeyException.class,
        () -> store.insertCase(sample(first.assessment().paymentId(), 1, "LOW", NOW)));
    assertEquals(1, store.count(null, null, null));
  }

  @Test
  void pagesFilterByCurrentActivatedReviewAndHaveStableOrder() {
    UUID payment = UUID.randomUUID();
    var superseded = activate(sample(payment, 1, "HIGH", NOW), UUID.randomUUID());
    var latest = activate(sample(payment, 2, "HIGH", NOW.plusSeconds(1)), UUID.randomUUID());
    var inactive = sample(UUID.randomUUID(), 1, "MEDIUM", NOW.plusSeconds(2));
    var processing = sample(UUID.randomUUID(), 1, "LOW", NOW.plusSeconds(3));
    for (var c : List.of(superseded, latest, inactive, processing)) store.insertCase(c);
    publish(latest);
    publish(inactive);
    publish(processing);
    assertEquals(List.of(latest), store.list(null, null, true, 0, 10));
    assertEquals(1, store.count("UNDER_REVIEW", "HIGH", true));
    assertEquals(3, store.count(null, null, false));
    assertEquals(4, store.count(null, null, null));
    assertEquals(List.of(processing, inactive), store.list(null, null, null, 0, 2));
    assertEquals(List.of(latest, superseded), store.list(null, null, null, 1, 2));
    assertEquals(List.of(inactive), store.list("UNDER_REVIEW", "MEDIUM", false, 0, 10));
    assertEquals(0, store.count("UNDER_REVIEW' OR 1=1 --", null, null));
  }

  @Test
  void reviewCommandAndRetryPayloadAreDurableWhileOnlyDeliveryMetadataChanges() {
    var c = activate(sample(UUID.randomUUID(), 1, "HIGH", NOW), UUID.randomUUID());
    store.insertCase(c);
    var decision = decision(c, "PENDING", 0, NOW);
    store.insertDecision(decision);
    assertEquals(decision, store.decisionForCase(c.assessment().caseId()).orElseThrow());
    transaction.executeWithoutResult(tx ->
        assertEquals(decision, store.decision(decision.command().decisionId(), true).orElseThrow()));
    var updated = new M5ReviewDecision(decision.command(), "ACKNOWLEDGED", 1,
        NOW.plusSeconds(10), "SINK_UNAVAILABLE");
    var command = decision.command();
    var replacement = new M5ReviewCommand(command.decisionId(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "replaced", "REJECT",
        UUID.randomUUID(), NOW.plusSeconds(100), "replaced");
    store.updateDelivery(new M5ReviewDecision(replacement, updated.deliveryState(),
        updated.retryCount(), updated.nextAttemptAt(), updated.lastErrorCode()));
    assertEquals(updated, store.decision(decision.command().decisionId(), false).orElseThrow());
    assertTrue(store.pending(NOW.plusSeconds(100), 10).isEmpty());
    assertTrue(store.decision(UUID.randomUUID(), true).isEmpty());
    assertTrue(store.decisionForCase(UUID.randomUUID()).isEmpty());
    assertThrows(DuplicateKeyException.class, () -> store.insertDecision(decision));
  }

  @Test
  void pendingDeliveriesExcludeFutureAcknowledgedConflictAndExhaustedRecords() {
    for (int i = 0; i < 6; i++) {
      var c = activate(sample(UUID.randomUUID(), 1, "HIGH", NOW), UUID.randomUUID());
      store.insertCase(c);
      store.insertDecision(decision(c, i == 2 ? "ACKNOWLEDGED" : i == 3 ? "CONFLICT" : "PENDING",
          i == 4 ? 8 : 0, NOW.plusSeconds(i == 5 ? 1 : -i)));
    }
    var due = store.pending(NOW, 10);
    assertEquals(2, due.size());
    assertEquals(NOW.minusSeconds(1), due.get(0).nextAttemptAt());
    assertEquals(NOW, due.get(1).nextAttemptAt());
    assertEquals(List.of(due.get(0)), store.pending(NOW, 1));
    assertTrue(store.pending(NOW, 0).isEmpty());
  }

  @Test
  void rollbackLeavesNeitherPartialCaseNorPublishedHead() {
    var value = sample(UUID.randomUUID(), 1, "HIGH", NOW);
    assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> {
      store.insertCase(value);
      publish(value);
      throw new IllegalStateException("synthetic failure before commit");
    }));
    assertTrue(store.head(value.assessment().paymentId(), false).isEmpty());
    assertTrue(store.byCase(value.assessment().caseId(), false).isEmpty());
  }

  @Test
  void concurrentLifecycleAssessmentPublishesExactlyOneReplayableCase() throws Exception {
    var snapshot = sample(UUID.randomUUID(), 1, "HIGH", NOW).snapshot();
    var readers = new CyclicBarrier(2);
    var service = service(new M5PaymentReader() {
      public M5PaymentSnapshot readForAssessment(UUID id) {
        try { readers.await(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return snapshot;
      }
      public UUID ownerOf(UUID id) { return snapshot.senderId(); }
    });
    var request = new M5AssessmentRequest(UUID.randomUUID(), 1, snapshot.paymentId(),
        M5Fingerprints.payment(snapshot));
    var first = CompletableFuture.supplyAsync(() -> service.assessOutcome(request));
    var second = CompletableFuture.supplyAsync(() -> service.assessOutcome(request));
    var a = first.get(10, TimeUnit.SECONDS);
    var b = second.get(10, TimeUnit.SECONDS);
    assertEquals(a.response(), b.response());
    assertNotEquals(a.replay(), b.replay());
    assertEquals(1, store.count(null, null, null));
    assertEquals(a.response().caseId(), store.head(snapshot.paymentId(), false).orElseThrow().latestCaseId());
    assertEquals("HIGH", a.response().risk());
    assertTrue(a.response().reasons().stream().anyMatch(reason -> reason.code().equals("KYC_UNVERIFIED")));
    // Replay must bypass the reader, whose two-party barrier would otherwise time out.
    assertEquals(a.response(), service.assess(request));
  }

  @Test
  void failedDecisionInsertRollsBackTheCaseStatusInRealLifecycleTransaction() {
    var snapshot = sample(UUID.randomUUID(), 1, "HIGH", NOW).snapshot();
    var service = service(new M5PaymentReader() {
      public M5PaymentSnapshot readForAssessment(UUID id) { return snapshot; }
      public UUID ownerOf(UUID id) { return snapshot.senderId(); }
    });
    var assessment = service.assess(new M5AssessmentRequest(UUID.randomUUID(), 1, snapshot.paymentId(),
        M5Fingerprints.payment(snapshot)));
    service.recordDisposition(assessment.assessmentId(), "REVIEW_REQUIRED", UUID.randomUUID());
    jdbc.execute("ALTER TABLE m5_review_decisions ADD CONSTRAINT synthetic_insert_failure CHECK(decision='REJECT')");
    var admin = new CurrentUser(UUID.randomUUID(), "reviewer@example.invalid", "ADMIN");
    assertEquals("CASE_CONFLICT", assertThrows(M5ApiException.class,
        () -> service.decide(assessment.caseId(), "APPROVE", "checked", admin)).code());
    var actual = service.get(assessment.caseId(), admin);
    assertEquals("UNDER_REVIEW", actual.status());
    assertNull(actual.decidedBy());
    assertTrue(actual.reviewable());
    assertTrue(store.decisionForCase(assessment.caseId()).isEmpty());
    assertEquals(assessment, service.assess(new M5AssessmentRequest(assessment.assessmentId(), 1,
        snapshot.paymentId(), M5Fingerprints.payment(snapshot))));
  }

  private M5ComplianceService service(M5PaymentReader reader) {
    var settings = new M5ComplianceSettings(Map.of("USD", new BigDecimal("5000")),
        ZoneId.of("Asia/Kolkata"), "ALL_ATTEMPTS", Set.of("KP"));
    return new M5ComplianceService(store, reader, new M5ComplianceRulesEngine(settings),
        new DataSourceTransactionManager(jdbc.getDataSource()), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void publish(M5ScreeningCase value) {
    var a = value.assessment();
    store.createHead(a.paymentId());
    store.publishHead(new M5ScreeningHead(a.paymentId(), a.caseId(), a.assessmentSequence(), 1));
  }

  private static M5ScreeningCase sample(UUID payment, long sequence, String risk, Instant at) {
    var snapshot = new M5PaymentSnapshot(payment, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), new BigDecimal("10000.25"), "USD", "INR", "Family support",
        true, "{\"name\":\"Test recipient\"}", 2, "IN", false, true, 3L,
        at, at, Instant.parse("2026-09-12T18:30:00Z"), "Asia/Kolkata", "ALL_ATTEMPTS");
    String verdict = "LOW".equals(risk) ? "APPROVE" : "REVIEW";
    var response = new M5AssessmentResponse(UUID.randomUUID(), sequence, payment, "a".repeat(64),
        UUID.randomUUID(), risk, verdict,
        List.of(new M5RiskReason("R1", "HIGH", "KYC_UNVERIFIED", "Verify sender identity")),
        at, "m5-v1", "b".repeat(64));
    return new M5ScreeningCase(response, snapshot, "c".repeat(64),
        "LOW".equals(risk) ? "PROCESSING" : "UNDER_REVIEW", verdict, "Review sender identity",
        null, null, null, null, null, 0);
  }

  private static M5ScreeningCase activate(M5ScreeningCase c, UUID reference) {
    return new M5ScreeningCase(c.assessment(), c.snapshot(), c.requestFingerprint(), c.status(),
        c.verdict(), c.suggestedAction(), "REVIEW_REQUIRED", reference, null, null, null,
        c.version() + 1);
  }

  private static M5ReviewDecision decision(M5ScreeningCase c, String state, int retries, Instant due) {
    var a = c.assessment();
    var command = new M5ReviewCommand(UUID.randomUUID(), a.caseId(), a.assessmentId(), a.paymentId(),
        c.reviewReference(), a.expectedPaymentFingerprint(), "APPROVE", UUID.randomUUID(), NOW,
        "Evidence checked");
    return new M5ReviewDecision(command, state, retries, due, null);
  }
}
