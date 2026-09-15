package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payout attempt mapped to {@code payout_attempts} per the V503 forward migration.
 *
 * <p>UUID strategy: {@code id} and {@code payout_route_id} are {@code RAW(16)} UUID storage mapped
 * as {@code UUID}. {@code payment_id} is the business key (e.g. {@code P-001}/{@code P-002}) stored
 * as {@code VARCHAR2(50)} with no FK to {@code payments(id)}. The {@code failure_reason} column
 * carries the terminal error code; the human-readable error message is kept transient for event
 * payloads.
 */
@Entity
@Table(
    name = "payout_attempts",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_payout_attempt",
            columnNames = {"payment_id", "attempt_number"}))
public class PayoutAttempt {

  @Id
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "payment_id", columnDefinition = "VARCHAR2(50)", nullable = false)
  private String paymentId;

  @Column(name = "payout_route_id", columnDefinition = "RAW(16)", nullable = false)
  private UUID routeId;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private PayoutAttemptStatus status;

  @Column(name = "failure_reason", length = 1000)
  private String errorCode;

  @Transient private String errorMessage;

  @Column(name = "provider_reference", length = 100, unique = true)
  private String providerReference;

  @Column(name = "initiated_at", nullable = false)
  private Instant initiatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected PayoutAttempt() {}

  private PayoutAttempt(
      UUID id, String paymentId, int attemptNumber, UUID routeId, Instant initiatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be at least 1");
    }
    this.attemptNumber = attemptNumber;
    this.routeId = Objects.requireNonNull(routeId, "routeId must not be null");
    this.initiatedAt = Objects.requireNonNull(initiatedAt, "initiatedAt must not be null");
    this.status = PayoutAttemptStatus.INITIATED;
  }

  public static PayoutAttempt initiated(
      UUID id, String paymentId, int attemptNumber, UUID routeId, Instant initiatedAt) {
    return new PayoutAttempt(id, paymentId, attemptNumber, routeId, initiatedAt);
  }

  public void markProcessing() {
    if (status != PayoutAttemptStatus.INITIATED) {
      throw new IllegalStateException(transitionMessage("mark PROCESSING"));
    }
    this.status = PayoutAttemptStatus.PROCESSING;
  }

  public void markCompleted(String providerReference) {
    if (status != PayoutAttemptStatus.PROCESSING) {
      throw new IllegalStateException(transitionMessage("mark COMPLETED"));
    }
    this.providerReference =
        Objects.requireNonNull(providerReference, "providerReference must not be null");
    this.errorCode = null;
    this.errorMessage = null;
    this.completedAt = Instant.now();
    this.status = PayoutAttemptStatus.COMPLETED;
  }

  public void markFailed(String errorCode, String errorMessage) {
    if (status != PayoutAttemptStatus.PROCESSING) {
      throw new IllegalStateException(transitionMessage("mark FAILED"));
    }
    if (errorCode == null || errorCode.isBlank()) {
      throw new IllegalArgumentException("errorCode must not be blank");
    }
    if (errorMessage == null || errorMessage.isBlank()) {
      throw new IllegalArgumentException("errorMessage must not be blank");
    }
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.providerReference = null;
    this.completedAt = Instant.now();
    this.status = PayoutAttemptStatus.FAILED;
  }

  private String transitionMessage(String action) {
    if (status == PayoutAttemptStatus.COMPLETED || status == PayoutAttemptStatus.FAILED) {
      return "attempt is terminal";
    }
    return "attempt must be " + requiredPrior(action) + " to " + action;
  }

  private static String requiredPrior(String action) {
    if (action.endsWith("PROCESSING")) {
      return "INITIATED";
    }
    return "PROCESSING";
  }

  public UUID getId() {
    return id;
  }

  public String getPaymentId() {
    return paymentId;
  }

  public UUID getRouteId() {
    return routeId;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public PayoutAttemptStatus getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public String getProviderReference() {
    return providerReference;
  }

  public Instant getInitiatedAt() {
    return initiatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  /** Record-style aliases used by domain logic and tests. */
  public UUID id() {
    return id;
  }

  public String paymentId() {
    return paymentId;
  }

  public UUID routeId() {
    return routeId;
  }

  public int attemptNumber() {
    return attemptNumber;
  }

  public PayoutAttemptStatus status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }

  public String errorMessage() {
    return errorMessage;
  }

  public String providerReference() {
    return providerReference;
  }

  public Instant initiatedAt() {
    return initiatedAt;
  }

  public Instant completedAt() {
    return completedAt;
  }
}
