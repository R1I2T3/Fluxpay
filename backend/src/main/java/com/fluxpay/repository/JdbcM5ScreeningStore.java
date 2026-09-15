package com.fluxpay.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.M5ReviewDecision;
import com.fluxpay.beans.M5ScreeningCase;
import com.fluxpay.beans.M5ScreeningHead;
import com.fluxpay.common.util.UuidRawCodec;
import com.fluxpay.dto.M5AssessmentResponse;
import com.fluxpay.dto.M5PaymentSnapshot;
import com.fluxpay.dto.M5ReviewCommand;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V705 persistence. The caller owns transactions and acquires the payment head before case locks. */
public final class JdbcM5ScreeningStore implements M5ScreeningStore {
  private static final String REVIEWABLE = """
      EXISTS (SELECT 1 FROM m5_screening_heads h
        WHERE h.payment_id=c.payment_id AND h.latest_case_id=c.id
          AND h.latest_sequence=c.assessment_sequence AND c.status='UNDER_REVIEW'
          AND c.payment_disposition='REVIEW_REQUIRED' AND c.review_reference IS NOT NULL)
      """;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public JdbcM5ScreeningStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public Optional<M5ScreeningCase> byAssessment(UUID id) {
    return jdbc.query("SELECT * FROM screening_cases WHERE assessment_id=?", this::readCase,
        UuidRawCodec.toBytes(id)).stream().findFirst();
  }

  @Override
  public Optional<M5ScreeningCase> byCase(UUID id, boolean lock) {
    return jdbc.query("SELECT * FROM screening_cases WHERE id=?" + lockClause(lock),
        this::readCase, UuidRawCodec.toBytes(id)).stream().findFirst();
  }

  @Override
  public Optional<M5ScreeningHead> head(UUID paymentId, boolean lock) {
    return jdbc.query("SELECT * FROM m5_screening_heads WHERE payment_id=?" + lockClause(lock),
        (rs, row) -> new M5ScreeningHead(uuid(rs, "payment_id"), uuid(rs, "latest_case_id"),
            rs.getLong("latest_sequence"), rs.getLong("version")),
        UuidRawCodec.toBytes(paymentId)).stream().findFirst();
  }

  @Override
  public void createHead(UUID paymentId) {
    write("INSERT INTO m5_screening_heads(payment_id,latest_sequence,version) VALUES (?,0,0)",
        id(paymentId));
  }

  @Override
  public void insertCase(M5ScreeningCase value) {
    var a = value.assessment();
    write("""
        INSERT INTO screening_cases (
          id,payment_id,verdict,created_at,assessment_id,assessment_sequence,
          request_fingerprint,payment_fingerprint,risk,status,screening_verdict,
          risk_reasons,suggested_action,decided_by,decided_at,decision_reason,version,
          assessment_snapshot,assessment_response,rule_version,rule_config_hash,
          review_reference,payment_disposition)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, id(a.caseId()), id(a.paymentId()), value.verdict(), time(a.assessedAt()),
        id(a.assessmentId()), a.assessmentSequence(), value.requestFingerprint(),
        a.expectedPaymentFingerprint(), a.risk(), value.status(), a.screeningVerdict(),
        json(a.reasons()), value.suggestedAction(), id(value.decidedBy()), time(value.decidedAt()),
        value.decisionReason(), value.version(), json(value.snapshot()), json(a), a.ruleVersion(),
        a.ruleConfigHash(), id(value.reviewReference()), value.paymentDisposition());
  }

  /** value.version is the new version, exactly one greater than the observed version. */
  @Override
  public void updateCase(M5ScreeningCase value) {
    // Screening evidence, suggested action and the original replay payload are never overwritten.
    changed(write("""
        UPDATE screening_cases SET status=?,verdict=?,payment_disposition=?,review_reference=?,
          decided_by=?,decided_at=?,decision_reason=?,version=? WHERE id=? AND version=?
        """, value.status(), value.verdict(), value.paymentDisposition(), id(value.reviewReference()),
        id(value.decidedBy()), time(value.decidedAt()), value.decisionReason(), value.version(),
        id(value.assessment().caseId()), previousVersion(value.version())), "screening case");
  }

  @Override
  public void publishHead(M5ScreeningHead value) {
    changed(write("""
        UPDATE m5_screening_heads SET latest_case_id=?,latest_sequence=?,version=?
          WHERE payment_id=? AND version=?
        """, id(value.latestCaseId()), value.latestSequence(), value.version(), id(value.paymentId()),
        previousVersion(value.version())), "screening head");
  }

  @Override
  public Optional<M5ReviewDecision> decisionForCase(UUID caseId) {
    return jdbc.query("SELECT * FROM m5_review_decisions WHERE case_id=?", this::readDecision,
        UuidRawCodec.toBytes(caseId)).stream().findFirst();
  }

  @Override
  public Optional<M5ReviewDecision> decision(UUID decisionId, boolean lock) {
    return jdbc.query("SELECT * FROM m5_review_decisions WHERE id=?" + lockClause(lock),
        this::readDecision, UuidRawCodec.toBytes(decisionId)).stream().findFirst();
  }

  @Override
  public void insertDecision(M5ReviewDecision value) {
    var c = value.command();
    write("""
        INSERT INTO m5_review_decisions (
          id,case_id,review_reference,payment_id,assessment_id,payment_fingerprint,decision,
          reason,reviewer_id,decided_at,delivery_state,retry_count,next_attempt_at,last_error_code)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, id(c.decisionId()), id(c.caseId()), id(c.reviewReference()), id(c.paymentId()),
        id(c.assessmentId()), c.paymentFingerprint(), c.decision(), c.reason(), id(c.reviewerId()),
        time(c.decidedAt()), value.deliveryState(), value.retryCount(), time(value.nextAttemptAt()),
        value.lastErrorCode());
  }

  @Override
  public void updateDelivery(M5ReviewDecision value) {
    // A retry only updates delivery metadata; the original downstream command remains immutable.
    changed(write("""
        UPDATE m5_review_decisions SET delivery_state=?,retry_count=?,next_attempt_at=?,last_error_code=?
          WHERE id=?
        """, value.deliveryState(), value.retryCount(), time(value.nextAttemptAt()),
        value.lastErrorCode(), id(value.command().decisionId())), "review decision");
  }

  @Override
  public List<M5ReviewDecision> pending(Instant now, int limit) {
    if (limit <= 0) return List.of();
    return jdbc.query("""
        SELECT * FROM m5_review_decisions
          WHERE delivery_state='PENDING' AND retry_count<8 AND next_attempt_at<=?
          ORDER BY next_attempt_at,id FETCH FIRST ? ROWS ONLY
        """, ps -> {
          ps.setTimestamp(1, Timestamp.from(now), utc());
          ps.setInt(2, limit);
        }, this::readDecision);
  }

  @Override
  public List<M5ScreeningCase> list(String status, String risk, Boolean reviewable, int page, int size) {
    if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid M5 case page");
    var filter = filter(status, risk, reviewable);
    var args = new ArrayList<>(filter.arguments());
    args.add((long) page * size);
    args.add(size);
    return jdbc.query("SELECT c.* FROM screening_cases c" + filter.sql()
        + " ORDER BY c.created_at DESC,c.id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
        this::readCase, args.toArray());
  }

  @Override
  public long count(String status, String risk, Boolean reviewable) {
    var filter = filter(status, risk, reviewable);
    return jdbc.queryForObject("SELECT COUNT(*) FROM screening_cases c" + filter.sql(),
        Long.class, filter.arguments().toArray());
  }

  private M5ScreeningCase readCase(ResultSet rs, int row) throws SQLException {
    return new M5ScreeningCase(decode(rs.getString("assessment_response"), M5AssessmentResponse.class),
        decode(rs.getString("assessment_snapshot"), M5PaymentSnapshot.class),
        rs.getString("request_fingerprint"), rs.getString("status"), rs.getString("verdict"),
        rs.getString("suggested_action"), rs.getString("payment_disposition"),
        uuid(rs, "review_reference"), uuid(rs, "decided_by"), instant(rs, "decided_at"),
        rs.getString("decision_reason"), rs.getLong("version"));
  }

  private M5ReviewDecision readDecision(ResultSet rs, int row) throws SQLException {
    var command = new M5ReviewCommand(uuid(rs, "id"), uuid(rs, "case_id"),
        uuid(rs, "assessment_id"), uuid(rs, "payment_id"), uuid(rs, "review_reference"),
        rs.getString("payment_fingerprint"), rs.getString("decision"), uuid(rs, "reviewer_id"),
        instant(rs, "decided_at"), rs.getString("reason"));
    return new M5ReviewDecision(command, rs.getString("delivery_state"), rs.getInt("retry_count"),
        instant(rs, "next_attempt_at"), rs.getString("last_error_code"));
  }

  private Filter filter(String status, String risk, Boolean reviewable) {
    var sql = new StringBuilder(" WHERE 1=1");
    var args = new ArrayList<Object>();
    if (status != null) { sql.append(" AND c.status=?"); args.add(status); }
    if (risk != null) { sql.append(" AND c.risk=?"); args.add(risk); }
    if (reviewable != null) sql.append(reviewable ? " AND " : " AND NOT ").append(REVIEWABLE);
    return new Filter(sql.toString(), args);
  }

  private int write(String sql, Object... values) {
    return jdbc.update(sql, ps -> {
      for (int i = 0; i < values.length; i++) {
        int index = i + 1;
        Object value = values[i];
        if (value instanceof RawId raw) {
          if (raw.value() == null) ps.setNull(index, Types.VARBINARY);
          else ps.setBytes(index, UuidRawCodec.toBytes(raw.value()));
        } else if (value instanceof UtcTime timestamp) {
          if (timestamp.value() == null) ps.setNull(index, Types.TIMESTAMP);
          else ps.setTimestamp(index, Timestamp.from(timestamp.value()), utc());
        } else if (value instanceof Json document) {
          ps.setClob(index, new StringReader(document.value()));
        } else if (value instanceof Number number) {
          ps.setLong(index, number.longValue());
        } else {
          ps.setString(index, (String) value);
        }
      }
    });
  }

  private Json json(Object value) {
    try { return new Json(mapper.writeValueAsString(value)); }
    catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot encode M5 evidence", e); }
  }

  private <T> T decode(String json, Class<T> type) {
    try { return mapper.readValue(json, type); }
    catch (JsonProcessingException e) { throw new DataRetrievalFailureException("Invalid stored M5 evidence", e); }
  }

  private static UUID uuid(ResultSet rs, String name) throws SQLException {
    byte[] bytes = rs.getBytes(name);
    return bytes == null ? null : UuidRawCodec.fromBytes(bytes);
  }

  private static Instant instant(ResultSet rs, String name) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(name, utc());
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static long previousVersion(long version) {
    if (version <= 0) throw new OptimisticLockingFailureException("M5 update requires a new version");
    return version - 1;
  }

  private static void changed(int rows, String entity) {
    if (rows != 1) throw new OptimisticLockingFailureException("Concurrent or missing M5 " + entity);
  }

  private static String lockClause(boolean lock) { return lock ? " FOR UPDATE" : ""; }
  private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
  private static RawId id(UUID value) { return new RawId(value); }
  private static UtcTime time(Instant value) { return new UtcTime(value); }
  private record RawId(UUID value) {}
  private record UtcTime(Instant value) {}
  private record Json(String value) {}
  private record Filter(String sql, List<Object> arguments) {}
}
