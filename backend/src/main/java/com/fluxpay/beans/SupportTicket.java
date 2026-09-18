package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "support_tickets")
public class SupportTicket {
  private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "RAW(16)")
  private UUID id;

  @Column(name = "user_id", nullable = false, columnDefinition = "RAW(16)")
  private UUID userId;

  @Column(name = "payment_id", columnDefinition = "RAW(16)")
  private UUID paymentId;

  @Column(name = "subject", nullable = false, length = 120)
  private String subject;

  @Column(name = "body", nullable = false, length = 4000)
  private String body;

  @Column(name = "status", nullable = false, length = 20)
  private String status = "OPEN";

  @Column(name = "assignee_admin_id", columnDefinition = "RAW(16)")
  private UUID assigneeAdminId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SupportTicket() {}

  public SupportTicket(UUID userId, UUID paymentId, String subject, String body) {
    this.userId = userId;
    this.paymentId = paymentId;
    this.subject = subject;
    this.body = body;
  }

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
    if (status == null) {
      status = "OPEN";
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public String getSubject() {
    return subject;
  }

  public String getBody() {
    return body;
  }

  public String getStatus() {
    return status;
  }

  public UUID getAssigneeAdminId() {
    return assigneeAdminId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void moveTo(String nextStatus, Instant now) {
    if (!STATUSES.contains(nextStatus)) {
      throw new IllegalArgumentException("INVALID_STATUS_TRANSITION");
    }
    if ("CLOSED".equals(status) && !"OPEN".equals(nextStatus)) {
      throw new IllegalStateException("INVALID_STATUS_TRANSITION");
    }
    status = nextStatus;
    updatedAt = now;
  }

  public void assignTo(UUID adminId, Instant now) {
    assigneeAdminId = adminId;
    updatedAt = now;
  }
}
