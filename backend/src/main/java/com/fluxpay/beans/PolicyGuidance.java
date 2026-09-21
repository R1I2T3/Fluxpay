package com.fluxpay.beans;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_guidance")
public class PolicyGuidance {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(columnDefinition = "RAW(16)")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "policy_document_id", nullable = false)
  private PolicyDocument policyDocument;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "compliance_case_id", nullable = false)
  private ComplianceCase complianceCase;

  @Lob
  @Column(nullable = false)
  private String content;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void created() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void updated() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public PolicyDocument getPolicyDocument() {
    return policyDocument;
  }

  public void setPolicyDocument(PolicyDocument value) {
    policyDocument = value;
  }

  public ComplianceCase getComplianceCase() {
    return complianceCase;
  }

  public void setComplianceCase(ComplianceCase value) {
    complianceCase = value;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String value) {
    content = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
