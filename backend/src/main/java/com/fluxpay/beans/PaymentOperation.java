package com.fluxpay.beans;

import com.fluxpay.common.json.OperationJson;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "payment_operations",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_payment_operation_key",
            columnNames = {"user_id", "client_key"}))
public class PaymentOperation {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "operation_type", nullable = false)
  private String operationType;

  @Column(name = "client_key", nullable = false, length = 255)
  private String clientKey;

  @Lob
  @Column(name = "normalized_request", nullable = false)
  private String normalizedRequest;

  @Column(name = "outcome_status")
  private Integer outcomeStatus;

  @Column(name = "status", nullable = false, length = 20)
  private String status;

  @Lob
  @Column(name = "response_data")
  private String responseData;

  @Lob
  @Column(name = "payout_reservation")
  private String payoutReservation;

  public String payoutReservation() {
    return payoutReservation;
  }

  public void capturePayoutReservation(String snapshot) {
    if (!"IN_PROGRESS".equals(status) || payoutReservation != null)
      throw new IllegalStateException("Payout reservation is immutable");
    OperationJson.requireObject(snapshot);
    payoutReservation = snapshot;
  }

  @Column(name = "payment_id")
  private UUID paymentId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected PaymentOperation() {}

  public PaymentOperation(
      UUID id,
      UUID userId,
      String operationType,
      String clientKey,
      String normalizedRequest,
      Integer outcomeStatus,
      String responseData,
      UUID paymentId,
      Instant createdAt) {
    OperationJson.requireObject(normalizedRequest);
    if ((outcomeStatus == null) != (responseData == null))
      throw new IllegalArgumentException(
          "Completion status and response must both be present or absent");
    if (outcomeStatus != null) validateCompletion(outcomeStatus, responseData);
    this.id = id;
    this.userId = userId;
    this.operationType = operationType;
    this.clientKey = clientKey;
    this.normalizedRequest = normalizedRequest;
    this.outcomeStatus = outcomeStatus;
    this.responseData = responseData;
    this.status = responseData == null ? "IN_PROGRESS" : "COMPLETED";
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

  public Integer outcomeStatus() {
    return outcomeStatus;
  }

  public String status() {
    return status;
  }

  public void complete(int outcomeStatus, String responseData) {
    complete(outcomeStatus, responseData, paymentId);
  }

  public void complete(int outcomeStatus, String responseData, UUID paymentId) {
    if (!"IN_PROGRESS".equals(status))
      throw new IllegalStateException("Operation already completed");
    validateCompletion(outcomeStatus, responseData);
    this.outcomeStatus = outcomeStatus;
    this.responseData = responseData;
    this.status = "COMPLETED";
    this.paymentId = paymentId;
  }

  private static void validateCompletion(int outcomeStatus, String responseData) {
    if (outcomeStatus < 200 || outcomeStatus > 599)
      throw new IllegalArgumentException("Completed operations require a final HTTP status");
    OperationJson.requireObject(responseData);
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
