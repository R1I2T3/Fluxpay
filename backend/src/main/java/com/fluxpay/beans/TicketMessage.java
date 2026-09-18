package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_ticket_messages")
public class TicketMessage {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", columnDefinition = "RAW(16)")
  private UUID id;

  @Column(name = "ticket_id", nullable = false, columnDefinition = "RAW(16)")
  private UUID ticketId;

  @Column(name = "author_user_id", nullable = false, columnDefinition = "RAW(16)")
  private UUID authorUserId;

  @Column(name = "body", nullable = false, length = 4000)
  private String body;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TicketMessage() {}

  public TicketMessage(UUID ticketId, UUID authorUserId, String body) {
    this.ticketId = ticketId;
    this.authorUserId = authorUserId;
    this.body = body;
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getAuthorUserId() {
    return authorUserId;
  }

  public String getBody() {
    return body;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
