package com.fluxpay.beans;

import com.fluxpay.common.enums.KycStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

/** Persistent KYC application backed by the existing {@code kyc_cases} table. */
@Entity
@Table(name = "kyc_cases")
public class KycCase {
  @Id
  @JdbcTypeCode(Types.BINARY)
  @Column(name = "id", nullable = false, columnDefinition = "RAW(16)")
  private UUID id;

  @JdbcTypeCode(Types.BINARY)
  @Column(name = "user_id", nullable = false, columnDefinition = "RAW(16)")
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private KycStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "doc_type", nullable = false, length = 30)
  private KycDocumentType docType;

  @Column(name = "doc_number", nullable = false, length = 64)
  private String docNumber;

  @JdbcTypeCode(Types.TIMESTAMP)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @JdbcTypeCode(Types.TIMESTAMP)
  @Column(name = "submitted_at", nullable = false)
  private Instant submittedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by")
  private User decidedBy;

  @JdbcTypeCode(Types.TIMESTAMP)
  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "reject_reason", length = 500)
  private String rejectReason;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected KycCase() {}

  public KycCase(
      UUID id,
      UUID userId,
      KycStatus status,
      KycDocumentType docType,
      String docNumber,
      Instant createdAt,
      Instant submittedAt) {
    this.id = id;
    this.userId = userId;
    this.status = status;
    this.docType = docType;
    this.docNumber = docNumber;
    this.createdAt = createdAt;
    this.submittedAt = submittedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public KycStatus getStatus() {
    return status;
  }

  public KycDocumentType getDocType() {
    return docType;
  }

  public String getDocNumber() {
    return docNumber;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public User getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public String getRejectReason() {
    return rejectReason;
  }

  public long getVersion() {
    return version;
  }

  public void resubmit(KycDocumentType docType, String docNumber, Instant submittedAt) {
    this.status = KycStatus.PENDING;
    this.docType = docType;
    this.docNumber = docNumber;
    this.submittedAt = submittedAt;
    this.decidedBy = null;
    this.decidedAt = null;
    this.rejectReason = null;
  }

  public void approve(User reviewer, Instant decidedAt) {
    this.status = KycStatus.VERIFIED;
    this.decidedBy = reviewer;
    this.decidedAt = decidedAt;
    this.rejectReason = null;
  }

  public void reject(User reviewer, Instant decidedAt, String rejectReason) {
    this.status = KycStatus.REJECTED;
    this.decidedBy = reviewer;
    this.decidedAt = decidedAt;
    this.rejectReason = rejectReason;
  }
}
