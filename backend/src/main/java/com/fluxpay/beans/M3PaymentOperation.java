package com.fluxpay.beans;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "m3_payment_operations",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_m3_operation_key",
            columnNames = {"user_id", "operation_type", "client_key"}))
public class M3PaymentOperation {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "operation_type", nullable = false)
  private String operationType;

  @Column(name = "client_key", nullable = false)
  private String clientKey;

  @Lob
  @Column(name = "normalized_request", nullable = false)
  private String normalizedRequest;

  @Column(name = "outcome_status", nullable = false)
  private int outcomeStatus;

  @Lob
  @Column(name = "response_data", nullable = false)
  private String responseData;

  @Column(name = "payment_id")
  private UUID paymentId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected M3PaymentOperation() {}

  public M3PaymentOperation(
      UUID id,
      UUID userId,
      String operationType,
      String clientKey,
      String normalizedRequest,
      int outcomeStatus,
      String responseData,
      UUID paymentId,
      Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.operationType = operationType;
    this.clientKey = clientKey;
    this.normalizedRequest = normalizedRequest;
    this.outcomeStatus = outcomeStatus;
    this.responseData = responseData;
    this.paymentId = paymentId;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public UUID userId() {
    return userId;
  }

  public String operationType() {
    return operationType;
  }

  public String clientKey() {
    return clientKey;
  }

  public String normalizedRequest() {
    return normalizedRequest;
  }

  public int outcomeStatus() {
    return outcomeStatus;
  }

  public String responseData() {
    return responseData;
  }

  public UUID paymentId() {
    return paymentId;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
