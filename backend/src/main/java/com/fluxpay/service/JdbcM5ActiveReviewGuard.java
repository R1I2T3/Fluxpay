package com.fluxpay.service;
import java.util.UUID;
import java.nio.ByteBuffer;
import org.springframework.jdbc.core.JdbcTemplate;
/** A non-locking read avoids reversing the operation/payment/M5 lock order. */
public final class JdbcM5ActiveReviewGuard implements M5ActiveReviewGuard {
  private final JdbcTemplate jdbc;
  public JdbcM5ActiveReviewGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }
  public boolean isActive(UUID paymentId,UUID caseId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE id=? AND m3_flow_version=1 AND status='UNDER_REVIEW' AND review_case_id=?",Integer.class,raw(paymentId),raw(caseId))>0;
  }
  private static byte[] raw(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
