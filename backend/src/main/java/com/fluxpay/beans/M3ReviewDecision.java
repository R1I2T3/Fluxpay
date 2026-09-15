package com.fluxpay.beans;
import com.fluxpay.dto.M3ReviewCommand;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="m3_review_decisions",uniqueConstraints=@UniqueConstraint(name="uq_m3_review_reference",columnNames="review_reference"))
public class M3ReviewDecision {
  @Id private UUID id;
  @Column(name="payment_id",nullable=false) private UUID paymentId;
  @Column(name="review_reference",nullable=false) private String reviewReference;
  @Column(nullable=false) private String decision;
  @Column(name="receipt_expires_at") private Instant receiptExpiresAt;
  @Column(name="accepted_at",nullable=false) private Instant acceptedAt;
  @Lob @Column(name="result_snapshot",nullable=false) private String resultSnapshot;
  @Lob @Column(name="normalized_command") private String normalizedCommand;
  @Column(name="assessment_id") private UUID assessmentId;
  @Column(name="case_id") private UUID caseId;
  @Column(name="payment_fingerprint") private String paymentFingerprint;
  @Column(name="receipt_consumed_at") private Instant receiptConsumedAt;
  protected M3ReviewDecision() {}
  public M3ReviewDecision(M3ReviewCommand c,String normalized,String result,Instant accepted) {
    id=c.decisionId(); paymentId=c.paymentId(); reviewReference=c.reviewReference().toString();
    decision=c.decision(); assessmentId=c.assessmentId(); caseId=c.caseId();
    paymentFingerprint=c.paymentFingerprint(); normalizedCommand=normalized; resultSnapshot=result;
    acceptedAt=accepted; receiptExpiresAt="APPROVE".equals(decision)?accepted.plusSeconds(900):null;
  }
  public UUID id() { return id; }
  public String normalizedCommand() { return normalizedCommand; }
  public String resultSnapshot() { return resultSnapshot; }
  public Instant receiptExpiresAt() { return receiptExpiresAt; }
  public Instant receiptConsumedAt() { return receiptConsumedAt; }
  public boolean authorizes(Payment p,String fingerprint,Instant now) {
    return "APPROVE".equals(decision) && receiptConsumedAt==null && receiptExpiresAt!=null && now.isBefore(receiptExpiresAt)
        && id.equals(p.approvalDecisionId()) && paymentId.equals(p.id())
        && reviewReference.equals(p.reviewReference()) && Objects.equals(assessmentId,p.reviewAssessmentId())
        && Objects.equals(caseId,p.reviewCaseId()) && Objects.equals(paymentFingerprint,p.reviewPaymentFingerprint())
        && Objects.equals(paymentFingerprint,fingerprint) && p.hasApproval(fingerprint,now);
  }
  public void consume(Instant now) {
    if(receiptConsumedAt!=null || receiptExpiresAt==null || !now.isBefore(receiptExpiresAt)) throw new IllegalStateException("Receipt is unavailable");
    receiptConsumedAt=now;
  }
}
