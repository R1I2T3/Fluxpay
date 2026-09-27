package com.fluxpay.dto;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PaymentOperationsResponse(
    Payment payment,
    List<Attempt> attempts,
    List<OutboxEvent> outboxEvents,
    List<TimelineEvent> timelineEvents,
    List<Operation> operations,
    List<LedgerEntry> ledgerEntries,
    Recovery recovery) {

  public PaymentOperationsResponse {
    attempts = immutable(attempts);
    outboxEvents = immutable(outboxEvents);
    timelineEvents = immutable(timelineEvents);
    operations = immutable(operations);
    ledgerEntries = immutable(ledgerEntries);
  }

  public record Payment(
      UUID id,
      PaymentStatus status,
      UUID selectedQuoteId,
      int eventSequence,
      Instant createdAt,
      Instant updatedAt) {}

  public record Attempt(
      UUID id,
      int attemptNumber,
      PayoutAttemptStatus status,
      String routeCode,
      String providerCode,
      String providerReference,
      String errorCode,
      String errorMessage,
      Instant initiatedAt,
      Instant completedAt) {}

  public record OutboxEvent(
      UUID eventId,
      String eventType,
      int aggregateSequence,
      Instant createdAt,
      Map<String, Object> payload,
      Delivery delivery) {
    public OutboxEvent {
      payload = immutableMap(payload);
    }
  }

  public record Delivery(
      String state, int attemptCount, Instant nextAttemptAt, Instant sentAt, String lastError) {}

  public record TimelineEvent(
      UUID eventId,
      String eventType,
      String kafkaTopic,
      String correlationId,
      Map<String, Object> payload,
      Instant occurredAt) {
    public TimelineEvent {
      payload = immutableMap(payload);
    }
  }

  public record Operation(
      UUID id,
      PaymentOperation.Namespace namespace,
      String operationType,
      String clientKey,
      String status,
      Integer outcomeStatus,
      Map<String, Object> response,
      Instant createdAt) {
    public Operation {
      response = immutableMap(response);
    }
  }

  public record LedgerEntry(
      UUID id,
      String journalReference,
      String idempotencyKey,
      String entryType,
      String amount,
      String currency,
      String narration,
      Instant createdAt) {}

  public record Recovery(long automatedRetryCount, RecoveryDecision decision, Instant nextRun) {}

  public enum RecoveryDecision {
    NOT_REQUIRED,
    RETRY_SCHEDULED,
    REFUND_SCHEDULED,
    REFUNDED,
    RECONCILIATION_REQUIRED,
    STALE
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static Map<String, Object> immutableMap(Map<String, Object> values) {
    return values == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
  }
}
