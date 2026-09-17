package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * A semantic chunk of a {@link PolicyDocument}. The `embedding` VECTOR column is intentionally
 * NOT mapped here yet -- it's populated by a later indexing pass, and Hibernate 6.4 (Spring Boot
 * 3.2.5) doesn't need to know about it for basic CRUD since ddl-auto is `validate`, which only
 * checks columns that are actually mapped.
 */
@Entity
@Table(
    name = "policy_chunks",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"policy_document_id", "chunk_number"}))
public class PolicyChunk {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "RAW(16)")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "policy_document_id", nullable = false)
  private PolicyDocument policyDocument;

  @Column(name = "chunk_number", nullable = false)
  private Integer chunkNumber;

  @Lob
  @Column(name = "content", nullable = false)
  private String content;

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

  public PolicyDocument getPolicyDocument() {
    return policyDocument;
  }

  public void setPolicyDocument(PolicyDocument policyDocument) {
    this.policyDocument = policyDocument;
  }

  public Integer getChunkNumber() {
    return chunkNumber;
  }

  public void setChunkNumber(Integer chunkNumber) {
    this.chunkNumber = chunkNumber;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
