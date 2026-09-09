package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Single canonical payment event mapped to {@code payment_events} per V402 DDL.
 *
 * <p>The {@code id} column is {@code RAW(16)}; like {@link PayoutRoute} the id is modelled as a
 * {@code String} UUID handle on the {@code id} column. The {@code event_payload} column is a JSON
 * {@code CLOB} held here as its serialized JSON string form.
 */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

  @Id
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private String eventId;

  @Column(name = "payment_id", nullable = false)
  private String paymentId;

  @Column(name = "event_type", nullable = false, length = 100)
  private String eventType;

  @Lob
  @Column(name = "event_payload", nullable = false)
  private String payload;

  @Column(name = "kafka_topic", nullable = false, length = 150)
  private String kafkaTopic;

  @Column(name = "correlation_id", nullable = false, length = 100)
  private String correlationId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected PaymentEvent() {}

  private PaymentEvent(
      String eventId,
      String paymentId,
      String eventType,
      String kafkaTopic,
      String correlationId,
      String payload,
      Instant occurredAt) {
    this.eventId = eventId;
    this.paymentId = paymentId;
    this.eventType = eventType;
    this.kafkaTopic = kafkaTopic;
    this.correlationId = correlationId;
    this.payload = payload;
    this.occurredAt = occurredAt;
  }

  /** Factory used by ingestion to persist a decoded envelope. */
  public static PaymentEvent create(
      String eventId,
      String paymentId,
      String eventType,
      String kafkaTopic,
      String correlationId,
      String payload,
      Instant occurredAt) {
    return new PaymentEvent(
        eventId, paymentId, eventType, kafkaTopic, correlationId, payload, occurredAt);
  }

  /** Record-style accessors used by ingestion, timeline mapping and tests. */
  public String eventId() {
    return eventId;
  }

  public String paymentId() {
    return paymentId;
  }

  public String eventType() {
    return eventType;
  }

  public String kafkaTopic() {
    return kafkaTopic;
  }

  public String correlationId() {
    return correlationId;
  }

  public String payload() {
    return payload;
  }

  public Instant occurredAt() {
    return occurredAt;
  }
}
