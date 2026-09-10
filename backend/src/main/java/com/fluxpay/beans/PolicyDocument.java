package com.fluxpay.beans;

import com.fluxpay.common.enums.PolicyCategory;
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

/** A source policy document indexed for the Compliance Copilot. */
@Entity
@Table(name = "policy_documents")
public class PolicyDocument {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "RAW(16)")
  private UUID id;

  @Column(name = "title", length = 200, nullable = false)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", length = 20, nullable = false)
  private PolicyCategory category;

  @Lob
  @Column(name = "content", nullable = false)
  private String content;

  @Column(name = "document_hash", length = 64, nullable = false, unique = true)
  private String documentHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public PolicyCategory getCategory() {
    return category;
  }

  public void setCategory(PolicyCategory category) {
    this.category = category;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getDocumentHash() {
    return documentHash;
  }

  public void setDocumentHash(String documentHash) {
    this.documentHash = documentHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
