package com.fluxpay.beans;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
  @Id private UUID id;

  @Column(nullable = false)
  private String topic;

  @Lob
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected OutboxEvent() {}

  public OutboxEvent(UUID id, String topic, String payload, Instant createdAt) {
    this.id = id;
    this.topic = topic;
    this.payload = payload;
    this.createdAt = createdAt;
  }

  public UUID id() {
    return id;
  }

  public String topic() {
    return topic;
  }

  public String payload() {
    return payload;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
