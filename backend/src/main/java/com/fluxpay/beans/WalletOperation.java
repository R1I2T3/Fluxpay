package com.fluxpay.beans;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Receive/convert request identity and committed response; orchestration owns the transaction. */
@Entity
@Table(name = "wallet_operations")
public class WalletOperation {
  @Id
  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "user_id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "operation_type", length = 20, nullable = false, updatable = false)
  private String operationType;

  @Column(name = "client_key", length = 255, nullable = false, updatable = false)
  private String clientKey;

  @Lob
  @Column(name = "normalized_request", nullable = false, updatable = false)
  private String normalizedRequest;

  @Column(name = "journal_reference", length = 64, nullable = false, updatable = false)
  private String journalReference;

  @Column(name = "status", length = 20, nullable = false)
  private String status;

  @Lob
  @Column(name = "response_snapshot")
  private String responseSnapshot;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected WalletOperation() {}

  public WalletOperation(
      UUID userId,
      String operationType,
      String clientKey,
      String normalizedRequest,
      String journalReference) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.operationType = operationType;
    this.clientKey = clientKey;
    this.normalizedRequest = normalizedRequest;
    this.journalReference = journalReference;
    this.status = "IN_PROGRESS";
    this.createdAt = Instant.now();
  }

  public void complete(String responseSnapshot) {
    if (responseSnapshot == null || responseSnapshot.isBlank()) {
      throw new IllegalArgumentException("A completed operation requires a response snapshot");
    }
    if (!"IN_PROGRESS".equals(status)) {
      throw new IllegalStateException("A completed operation cannot be overwritten");
    }
    this.responseSnapshot = responseSnapshot;
    this.status = "COMPLETED";
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getOperationType() {
    return operationType;
  }

  public String getClientKey() {
    return clientKey;
  }

  public String getNormalizedRequest() {
    return normalizedRequest;
  }

  public String getJournalReference() {
    return journalReference;
  }

  public String getStatus() {
    return status;
  }

  public String getResponseSnapshot() {
    return responseSnapshot;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
