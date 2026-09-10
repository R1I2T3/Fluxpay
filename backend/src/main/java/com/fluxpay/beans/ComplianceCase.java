package com.fluxpay.beans;

import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.common.enums.ComplianceRisk;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A Payment Passport compliance case for a single payment. payment_id is kept as a plain UUID
 * column (not a JPA relationship) since the `payments` table is owned by another member -- this
 * avoids taking a hard entity dependency on their model.
 */
@Entity
@Table(name = "compliance_cases")
public class ComplianceCase {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "RAW(16)")
  private UUID id;

  @Column(name = "payment_id", columnDefinition = "RAW(16)", nullable = false)
  private UUID paymentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "risk", length = 10, nullable = false)
  private ComplianceRisk risk;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 10, nullable = false)
  private ComplianceCaseStatus status = ComplianceCaseStatus.OPEN;

  /** JSON array string, e.g. ["KYC_UNVERIFIED","FIRST_TRANSFER_TO_RECIPIENT"]. */
  @Lob
  @Column(name = "risk_reasons", nullable = false)
  private String riskReasons;

  @Column(name = "suggested_action", length = 400, nullable = false)
  private String suggestedAction;

  @Column(name = "decided_by", length = 255)
  private String decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decision_reason", length = 500)
  private String decisionReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (status == null) {
      status = ComplianceCaseStatus.OPEN;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public void setPaymentId(UUID paymentId) {
    this.paymentId = paymentId;
  }

  public ComplianceRisk getRisk() {
    return risk;
  }

  public void setRisk(ComplianceRisk risk) {
    this.risk = risk;
  }

  public ComplianceCaseStatus getStatus() {
    return status;
  }

  public void setStatus(ComplianceCaseStatus status) {
    this.status = status;
  }

  public String getRiskReasons() {
    return riskReasons;
  }

  public void setRiskReasons(String riskReasons) {
    this.riskReasons = riskReasons;
  }

  public String getSuggestedAction() {
    return suggestedAction;
  }

  public void setSuggestedAction(String suggestedAction) {
    this.suggestedAction = suggestedAction;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public void setDecidedBy(String decidedBy) {
    this.decidedBy = decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(Instant decidedAt) {
    this.decidedAt = decidedAt;
  }

  public String getDecisionReason() {
    return decisionReason;
  }

  public void setDecisionReason(String decisionReason) {
    this.decisionReason = decisionReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
