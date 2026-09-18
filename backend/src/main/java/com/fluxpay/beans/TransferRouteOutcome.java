package com.fluxpay.beans;

import com.fluxpay.domain.RouteOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfer_route_outcomes")
public class TransferRouteOutcome {

  @Id
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @Column(
      name = "transfer_route_id",
      columnDefinition = "RAW(16)",
      nullable = false,
      updatable = false)
  private UUID routeId;

  @Column(
      name = "execution_reference",
      nullable = false,
      unique = true,
      length = 100,
      updatable = false)
  private String executionReference;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 20, updatable = false)
  private RouteOutcome outcome;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  protected TransferRouteOutcome() {}

  public static TransferRouteOutcome record(
      UUID id, UUID routeId, String executionReference, RouteOutcome outcome, Instant occurredAt) {
    TransferRouteOutcome projection = new TransferRouteOutcome();
    projection.id = Objects.requireNonNull(id, "id must not be null");
    projection.routeId = Objects.requireNonNull(routeId, "routeId must not be null");
    if (executionReference == null || executionReference.isBlank()) {
      throw new IllegalArgumentException("executionReference must not be blank");
    }
    projection.executionReference = executionReference;
    projection.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
    projection.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    return projection;
  }

  public UUID id() {
    return id;
  }

  public UUID routeId() {
    return routeId;
  }

  public String executionReference() {
    return executionReference;
  }

  public RouteOutcome outcome() {
    return outcome;
  }

  public Instant occurredAt() {
    return occurredAt;
  }
}
