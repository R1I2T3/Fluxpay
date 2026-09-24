package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PaymentOperationsResponse;
import com.fluxpay.dto.PaymentOperationsResponse.RecoveryDecision;
import com.fluxpay.messaging.EventEnvelopeCodec;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.LedgerJournalRepository;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentEventRepository;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Correlates persisted payment evidence for the read-only operations console. */
@Service
@Transactional(readOnly = true)
public class PaymentOperationsService {
  private final PaymentRepository payments;
  private final PayoutAttemptRepository attempts;
  private final OutboxDeliveryRepository deliveries;
  private final OutboxEventRepository outboxEvents;
  private final PaymentEventRepository timelineEvents;
  private final PaymentOperationRepository operations;
  private final LedgerJournalRepository journals;
  private final LedgerEntryRepository ledgerEntries;
  private final TransferRouteRepository routes;
  private final ObjectMapper objectMapper;
  private final EventEnvelopeCodec eventCodec;

  public PaymentOperationsService(
      PaymentRepository payments,
      PayoutAttemptRepository attempts,
      OutboxDeliveryRepository deliveries,
      OutboxEventRepository outboxEvents,
      PaymentEventRepository timelineEvents,
      PaymentOperationRepository operations,
      LedgerJournalRepository journals,
      LedgerEntryRepository ledgerEntries,
      TransferRouteRepository routes,
      ObjectMapper objectMapper,
      EventEnvelopeCodec eventCodec) {
    this.payments = payments;
    this.attempts = attempts;
    this.deliveries = deliveries;
    this.outboxEvents = outboxEvents;
    this.timelineEvents = timelineEvents;
    this.operations = operations;
    this.journals = journals;
    this.ledgerEntries = ledgerEntries;
    this.routes = routes;
    this.objectMapper = objectMapper;
    this.eventCodec = eventCodec;
  }

  @Transactional(readOnly = true)
  public PaymentOperationsResponse get(String paymentId) {
    var id = parsePaymentId(paymentId);
    var payment =
        payments.findById(id).orElseThrow(() -> new NoSuchElementException("payment not found"));

    var attemptEntities = attempts.findByPaymentIdOrderByAttemptNumberAsc(id.toString());
    var deliveryEntities = deliveries.findByPaymentIdOrderByAggregateSequenceAsc(id);
    var eventEntities =
        outboxEvents.findAllById(
            safe(deliveryEntities).stream()
                .filter(Objects::nonNull)
                .map(OutboxDelivery::eventId)
                .collect(Collectors.toSet()));
    var timelineEntities =
        timelineEvents.findByPaymentIdOrderByOccurredAtAscEventIdAsc(id.toString());
    var operationEntities = operations.findByPaymentIdOrderByCreatedAtAscIdAsc(id);
    var journalReferences = ledgerReferences(id);
    journals.findByJournalReferenceInOrderByJournalReferenceAsc(journalReferences);
    var ledgerEntities =
        ledgerEntries.findByJournalReferenceInOrderByCreatedAtAscIdAsc(journalReferences);
    var routeCatalogue = routes.findAllByOrderByRouteCodeAsc();

    return assemble(
        payment,
        attemptEntities,
        deliveryEntities,
        eventEntities,
        timelineEntities,
        operationEntities,
        ledgerEntities,
        routeCatalogue);
  }

  private UUID parsePaymentId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new NoSuchElementException("payment " + value + " not found");
    }
  }

  private Map<String, Object> decodeObject(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return objectMapper.readValue(
          json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
      throw new IllegalStateException("persisted operations JSON is invalid", exception);
    }
  }

  private Map<String, Object> decodeTimelineObject(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      return objectMapper.readerFor(new TypeReference<Map<String, Object>>() {}).readValue(json);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new IllegalStateException("persisted operations JSON is invalid", exception);
    }
  }

  private List<String> ledgerReferences(UUID id) {
    return List.of(
        "payment:" + id,
        "refund:" + id + ":clearing:debit",
        "refund:" + id + ":sender:credit",
        "refund:" + id + ":fee:debit");
  }

  private PaymentOperationsResponse assemble(
      Payment payment,
      List<PayoutAttempt> attemptEntities,
      List<OutboxDelivery> deliveryEntities,
      List<OutboxEvent> eventEntities,
      List<PaymentEvent> timelineEntities,
      List<PaymentOperation> operationEntities,
      List<LedgerEntry> ledgerEntities,
      List<TransferRoute> routeCatalogue) {
    var routeById = routeIndex(routeCatalogue);
    var orderedDeliveries = orderedDeliveries(deliveryEntities);
    var eventById = eventIndex(eventEntities);
    var outbox =
        orderedDeliveries.stream()
            .map(delivery -> toOutboxEvent(delivery, eventById.get(delivery.eventId())))
            .toList();
    var timeline = orderedTimeline(timelineEntities).stream().map(this::toTimelineEvent).toList();
    var orderedAttemptEntities = orderedAttempts(attemptEntities);
    var failureMessages = failureMessages(outbox);
    var attempts =
        orderedAttemptEntities.stream()
            .map(attempt -> toAttempt(attempt, routeById, failureMessages))
            .toList();
    var orderedOperationEntities = orderedOperations(operationEntities);
    var operationRows = toOperations(orderedOperationEntities);
    var orderedLedgerEntities = orderedLedgerEntries(ledgerEntities);
    var ledgerRows = toLedgerEntries(orderedLedgerEntities);
    var latestAttempt =
        orderedAttemptEntities.isEmpty()
            ? null
            : orderedAttemptEntities.get(orderedAttemptEntities.size() - 1);
    var recovery = recovery(payment, latestAttempt, orderedOperationEntities, outbox, timeline);

    return new PaymentOperationsResponse(
        toPayment(payment), attempts, outbox, timeline, operationRows, ledgerRows, recovery);
  }

  private Map<UUID, TransferRoute> routeIndex(List<TransferRoute> routeCatalogue) {
    Map<UUID, TransferRoute> result = new HashMap<>();
    safe(routeCatalogue).stream()
        .filter(Objects::nonNull)
        .filter(route -> route.id() != null)
        .forEach(route -> result.put(route.id(), route));
    return result;
  }

  private Map<UUID, OutboxEvent> eventIndex(List<OutboxEvent> eventEntities) {
    Map<UUID, OutboxEvent> result = new HashMap<>();
    safe(eventEntities).stream()
        .filter(Objects::nonNull)
        .filter(event -> event.id() != null)
        .forEach(event -> result.put(event.id(), event));
    return result;
  }

  private List<OutboxDelivery> orderedDeliveries(List<OutboxDelivery> values) {
    return safe(values).stream()
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparingInt(OutboxDelivery::aggregateSequence)
                .thenComparing(
                    OutboxDelivery::eventId, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private List<PayoutAttempt> orderedAttempts(List<PayoutAttempt> values) {
    return safe(values).stream()
        .filter(Objects::nonNull)
        .sorted(Comparator.comparingInt(PayoutAttempt::attemptNumber))
        .toList();
  }

  private List<PaymentEvent> orderedTimeline(List<PaymentEvent> values) {
    return safe(values).stream()
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparing(
                    PaymentEvent::occurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    PaymentEvent::eventId, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private List<PaymentOperation> orderedOperations(List<PaymentOperation> values) {
    return safe(values).stream()
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparing(
                    PaymentOperation::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    PaymentOperation::id, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private List<LedgerEntry> orderedLedgerEntries(List<LedgerEntry> values) {
    return safe(values).stream()
        .filter(Objects::nonNull)
        .sorted(
            Comparator.comparing(
                    LedgerEntry::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LedgerEntry::getId, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  private PaymentOperationsResponse.Payment toPayment(Payment payment) {
    return new PaymentOperationsResponse.Payment(
        payment.id(),
        payment.status(),
        payment.selectedQuoteId(),
        payment.eventSequenceCounter(),
        payment.createdAt(),
        payment.updatedAt());
  }

  private PaymentOperationsResponse.OutboxEvent toOutboxEvent(
      OutboxDelivery delivery, OutboxEvent event) {
    if (event == null) {
      return new PaymentOperationsResponse.OutboxEvent(
          delivery.eventId(),
          null,
          delivery.aggregateSequence(),
          null,
          Map.of(),
          toDelivery(delivery));
    }
    var envelope = eventCodec.read(event.payload());
    return new PaymentOperationsResponse.OutboxEvent(
        event.id() == null ? delivery.eventId() : event.id(),
        envelope.eventType(),
        delivery.aggregateSequence(),
        event.createdAt(),
        envelope.payload(),
        toDelivery(delivery));
  }

  private PaymentOperationsResponse.Delivery toDelivery(OutboxDelivery delivery) {
    return new PaymentOperationsResponse.Delivery(
        delivery.state(),
        delivery.attemptCount(),
        delivery.nextAttemptAt(),
        delivery.sentAt(),
        delivery.lastError());
  }

  private PaymentOperationsResponse.TimelineEvent toTimelineEvent(PaymentEvent event) {
    return new PaymentOperationsResponse.TimelineEvent(
        event.eventId(),
        event.eventType(),
        event.kafkaTopic(),
        event.correlationId(),
        decodeTimelineObject(event.payload()),
        event.occurredAt());
  }

  private PaymentOperationsResponse.Attempt toAttempt(
      PayoutAttempt attempt,
      Map<UUID, TransferRoute> routeById,
      Map<Integer, String> failureMessages) {
    TransferRoute route = routeById.get(attempt.routeId());
    String routeCode = route == null ? null : route.code();
    String providerCode =
        route == null || route.provider() == null ? null : route.provider().code();
    return new PaymentOperationsResponse.Attempt(
        attempt.id(),
        attempt.attemptNumber(),
        attempt.status(),
        routeCode,
        providerCode,
        attempt.providerReference(),
        attempt.errorCode(),
        failureMessages.get(attempt.attemptNumber()),
        attempt.initiatedAt(),
        attempt.completedAt());
  }

  private Map<Integer, String> failureMessages(List<PaymentOperationsResponse.OutboxEvent> events) {
    Map<Integer, String> result = new LinkedHashMap<>();
    for (var event : events) {
      if (!EventTopics.PAYOUT_FAILED.equals(event.eventType())) continue;
      Integer attempt = integerValue(event.payload().get("attempt"));
      if (attempt != null) {
        result.put(attempt, textValue(event.payload().get("errorMessage")));
      }
    }
    return result;
  }

  private List<PaymentOperationsResponse.Operation> toOperations(List<PaymentOperation> values) {
    return values.stream()
        .map(
            operation ->
                new PaymentOperationsResponse.Operation(
                    operation.id(),
                    operation.namespace(),
                    operation.operationType(),
                    operation.clientKey(),
                    operation.status(),
                    operation.outcomeStatus(),
                    decodeObject(operation.responseData()),
                    operation.createdAt()))
        .toList();
  }

  private List<PaymentOperationsResponse.LedgerEntry> toLedgerEntries(List<LedgerEntry> values) {
    return values.stream()
        .map(
            entry ->
                new PaymentOperationsResponse.LedgerEntry(
                    entry.getId(),
                    entry.getJournalReference(),
                    entry.getIdempotencyKey(),
                    entry.getEntryType(),
                    entry.getAmount().toPlainString(),
                    entry.getCurrency(),
                    entry.getNarration(),
                    entry.getCreatedAt()))
        .toList();
  }

  private PaymentOperationsResponse.Recovery recovery(
      Payment payment,
      PayoutAttempt latestAttempt,
      List<PaymentOperation> operationEntities,
      List<PaymentOperationsResponse.OutboxEvent> outbox,
      List<PaymentOperationsResponse.TimelineEvent> timeline) {
    long automatedRetryCount =
        operationEntities.stream()
            .filter(
                operation ->
                    operation.namespace() == PaymentOperation.Namespace.INTERNAL
                        && "AUTO_RETRY".equals(operation.operationType()))
            .count();
    Instant nextRun = nextRun(outbox);
    RecoveryDecision decision = RecoveryDecision.NOT_REQUIRED;

    if (payment.status() == PaymentStatus.REFUNDED
        || hasEvent(EventTopics.PAYMENT_REFUNDED, outbox, timeline)) {
      decision = RecoveryDecision.REFUNDED;
    } else if (latestAttempt != null
        && payment.status() == PaymentStatus.PROCESSING
        && latestAttempt.status() == PayoutAttemptStatus.PROCESSING
        && !hasTerminalPayoutFor(latestAttempt.attemptNumber(), outbox, timeline)) {
      decision = RecoveryDecision.RECONCILIATION_REQUIRED;
    } else if (latestAttempt != null
        && hasStaleRecoveryCommand(latestAttempt.attemptNumber(), outbox)) {
      decision = RecoveryDecision.STALE;
    } else if (latestAttempt != null
        && hasCurrentCommand(EventTopics.PAYOUT_REFUND, latestAttempt.attemptNumber(), outbox)) {
      decision = RecoveryDecision.REFUND_SCHEDULED;
    } else if (latestAttempt != null
        && hasCurrentCommand(EventTopics.PAYOUT_RETRY, latestAttempt.attemptNumber(), outbox)) {
      decision = RecoveryDecision.RETRY_SCHEDULED;
    }
    return new PaymentOperationsResponse.Recovery(automatedRetryCount, decision, nextRun);
  }

  private Instant nextRun(List<PaymentOperationsResponse.OutboxEvent> events) {
    PaymentOperationsResponse.OutboxEvent highest = null;
    for (var event : events) {
      if (!isRecoveryCommand(event.eventType())) continue;
      if (highest == null || event.aggregateSequence() >= highest.aggregateSequence()) {
        highest = event;
      }
    }
    if (highest == null) return null;
    Object value = highest.payload().get("nextRun");
    if (!(value instanceof String text) || text.isBlank()) return null;
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ignored) {
      return null;
    }
  }

  private boolean hasEvent(
      String eventType,
      List<PaymentOperationsResponse.OutboxEvent> outbox,
      List<PaymentOperationsResponse.TimelineEvent> timeline) {
    return outbox.stream().anyMatch(event -> eventType.equals(event.eventType()))
        || timeline.stream().anyMatch(event -> eventType.equals(event.eventType()));
  }

  private boolean hasTerminalPayoutFor(
      int attemptNumber,
      List<PaymentOperationsResponse.OutboxEvent> outbox,
      List<PaymentOperationsResponse.TimelineEvent> timeline) {
    return outbox.stream()
            .filter(
                event ->
                    EventTopics.PAYOUT_COMPLETED.equals(event.eventType())
                        || EventTopics.PAYOUT_FAILED.equals(event.eventType()))
            .anyMatch(event -> matchesAttempt(event.payload().get("attempt"), attemptNumber))
        || timeline.stream()
            .filter(
                event ->
                    EventTopics.PAYOUT_COMPLETED.equals(event.eventType())
                        || EventTopics.PAYOUT_FAILED.equals(event.eventType()))
            .anyMatch(event -> matchesAttempt(event.payload().get("attempt"), attemptNumber));
  }

  private boolean hasStaleRecoveryCommand(
      int latestAttemptNumber, List<PaymentOperationsResponse.OutboxEvent> events) {
    return events.stream()
        .filter(event -> isRecoveryCommand(event.eventType()))
        .map(event -> integerValue(event.payload().get("failedAttempt")))
        .anyMatch(attempt -> attempt != null && attempt != latestAttemptNumber);
  }

  private boolean hasCurrentCommand(
      String eventType,
      int latestAttemptNumber,
      List<PaymentOperationsResponse.OutboxEvent> events) {
    return events.stream()
        .filter(event -> eventType.equals(event.eventType()))
        .anyMatch(
            event -> matchesAttempt(event.payload().get("failedAttempt"), latestAttemptNumber));
  }

  private boolean matchesAttempt(Object value, int expected) {
    Integer actual = integerValue(value);
    return actual != null && actual == expected;
  }

  private boolean isRecoveryCommand(String eventType) {
    return EventTopics.PAYOUT_RETRY.equals(eventType)
        || EventTopics.PAYOUT_REFUND.equals(eventType);
  }

  private Integer integerValue(Object value) {
    if (value instanceof Number number) {
      try {
        return new BigDecimal(number.toString()).intValueExact();
      } catch (ArithmeticException | NumberFormatException ignored) {
        return null;
      }
    }
    if (value instanceof String text) {
      try {
        return Integer.valueOf(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private String textValue(Object value) {
    if (value == null) return null;
    return value instanceof String text ? text : value.toString();
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }
}
