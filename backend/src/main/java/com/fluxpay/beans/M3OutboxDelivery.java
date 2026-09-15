package com.fluxpay.beans;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "m3_outbox_delivery",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_m3_delivery_sequence",
            columnNames = {"payment_id", "aggregate_sequence"}))
public class M3OutboxDelivery {
  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "payment_id", nullable = false)
  private UUID paymentId;

  @Column(name = "aggregate_sequence", nullable = false)
  private int aggregateSequence;

  @Column(nullable = false)
  private String state;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "claim_token")
  private String claimToken;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "sent_at")
  private Instant sentAt;

  protected M3OutboxDelivery() {}

  public M3OutboxDelivery(UUID eventId, UUID paymentId, int aggregateSequence, Instant now) {
    this.eventId = eventId;
    this.paymentId = paymentId;
    this.aggregateSequence = aggregateSequence;
    this.state = "PENDING";
    this.attemptCount = 0;
    this.nextAttemptAt = now;
  }

  public UUID eventId() {
    return eventId;
  }

  public UUID paymentId() {
    return paymentId;
  }

  public int aggregateSequence() {
    return aggregateSequence;
  }

  public String state() {
    return state;
  }

  public int attemptCount() {
    return attemptCount;
  }

  public Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  public Instant leaseExpiresAt() {
    return leaseExpiresAt;
  }

  public String claimToken() {
    return claimToken;
  }

  public void claim(String token, Instant leaseExpiry) {
    this.state = "SENDING";
    this.claimToken = token;
    this.leaseExpiresAt = leaseExpiry;
  }

  public void markSent(Instant now) {
    this.state = "SENT";
    this.sentAt = now;
  }

  public void scheduleRetry(Instant nextAttempt, String error) {
    this.state = "PENDING";
    this.attemptCount = this.attemptCount + 1;
    this.nextAttemptAt = nextAttempt;
    this.claimToken = null;
    this.leaseExpiresAt = null;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
  }
}
