package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.LedgerJournal;
import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.PaymentOperationsResponse;
import com.fluxpay.dto.PaymentOperationsResponse.RecoveryDecision;
import com.fluxpay.dto.PaymentOperationsResponse.TimelineEvent;
import com.fluxpay.messaging.EventEnvelopeCodec;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.messaging.PaymentEventEnvelope;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.LedgerJournalRepository;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentEventRepository;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentOperationsServiceTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID PAYMENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID ROUTE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID PROVIDER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID QUOTE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final Instant BASE = Instant.parse("2026-09-24T10:00:00Z");
  private static final Instant NEXT_RUN = BASE.plusSeconds(120);

  @Mock private PaymentRepository payments;
  @Mock private PayoutAttemptRepository attempts;
  @Mock private OutboxDeliveryRepository deliveries;
  @Mock private OutboxEventRepository outboxEvents;
  @Mock private PaymentEventRepository timelineEvents;
  @Mock private PaymentOperationRepository operations;
  @Mock private LedgerJournalRepository journals;
  @Mock private LedgerEntryRepository ledgerEntries;
  @Mock private TransferRouteRepository routes;

  private ObjectMapper objectMapper;
  private EventEnvelopeCodec eventCodec;
  private PaymentOperationsService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    eventCodec = new EventEnvelopeCodec(objectMapper);
    service =
        new PaymentOperationsService(
            payments,
            attempts,
            deliveries,
            outboxEvents,
            timelineEvents,
            operations,
            journals,
            ledgerEntries,
            routes,
            objectMapper,
            eventCodec);
  }

  @Test
  void rejectsUnknownPaymentId() {
    when(payments.findById(PAYMENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(PAYMENT_ID.toString()))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }

  @Test
  void rejectsMalformedPaymentIdWithoutCallingRepositories() {
    assertThatThrownBy(() -> service.get("not-a-uuid"))
        .isInstanceOf(java.util.NoSuchElementException.class);

    verifyNoInteractions(
        payments,
        attempts,
        deliveries,
        outboxEvents,
        timelineEvents,
        operations,
        journals,
        ledgerEntries,
        routes);
  }

  @Test
  void correlatesEvidenceByExactIdsAndMapsPersistedRelationships() throws Exception {
    Payment payment = payment(PaymentStatus.FAILED);
    PayoutAttempt attempt = failedAttempt(1, "SIMULATED_PROVIDER_FAILURE", "provider rejected it");
    UUID firstEventId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    UUID secondEventId = UUID.fromString("77777777-7777-7777-7777-777777777777");
    OutboxEvent firstEvent =
        outboxEvent(
            firstEventId,
            EventTopics.PAYOUT_FAILED,
            1,
            Map.of("attempt", 1, "errorMessage", "provider rejected it"));
    OutboxEvent secondEvent =
        outboxEvent(
            secondEventId,
            EventTopics.PAYOUT_COMPLETED,
            2,
            Map.of("attempt", 1, "providerRef", "BANK-1"));
    OutboxDelivery firstDelivery = new OutboxDelivery(firstEventId, PAYMENT_ID, 1, BASE);
    OutboxDelivery secondDelivery = new OutboxDelivery(secondEventId, PAYMENT_ID, 2, BASE);
    PaymentEvent firstTimeline =
        timelineEvent(
            firstEventId,
            EventTopics.PAYOUT_FAILED,
            "corr-first",
            Map.of("attempt", 1, "errorMessage", "provider rejected it"),
            BASE.plusSeconds(1));
    PaymentEvent secondTimeline =
        timelineEvent(
            secondEventId,
            EventTopics.PAYOUT_COMPLETED,
            "corr-second",
            Map.of("attempt", 1),
            BASE.plusSeconds(2));
    PaymentOperation operation =
        new PaymentOperation(
            UUID.fromString("88888888-8888-8888-8888-888888888888"),
            USER_ID,
            PaymentOperation.Namespace.INTERNAL,
            "AUTO_RETRY",
            "auto:retry:" + PAYMENT_ID + ":1",
            "{\"action\":\"AUTO_RETRY\"}",
            200,
            "{\"eventId\":\"" + secondEventId + "\"}",
            PAYMENT_ID,
            BASE.plusSeconds(3));
    LedgerEntry firstLedger =
        ledgerEntry(
            "payment:" + PAYMENT_ID, "payment-entry-1", "DEBIT", "100.0000", BASE.plusSeconds(1));
    LedgerEntry secondLedger =
        ledgerEntry(
            "refund:" + PAYMENT_ID + ":clearing:debit",
            "refund-entry-1",
            "DEBIT",
            "25.0000",
            BASE.plusSeconds(2));
    TransferRoute route = route();

    stub(payment);
    when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(attempt));
    when(deliveries.findByPaymentIdOrderByAggregateSequenceAsc(PAYMENT_ID))
        .thenReturn(List.of(secondDelivery, firstDelivery));
    when(outboxEvents.findAllById(anySet())).thenReturn(List.of(secondEvent, firstEvent));
    when(timelineEvents.findByPaymentIdOrderByOccurredAtAscEventIdAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(secondTimeline, firstTimeline));
    when(operations.findByPaymentIdOrderByCreatedAtAscIdAsc(PAYMENT_ID))
        .thenReturn(List.of(operation));
    when(ledgerEntries.findByJournalReferenceInOrderByCreatedAtAscIdAsc(anyCollection()))
        .thenReturn(List.of(secondLedger, firstLedger));
    when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of(route));

    PaymentOperationsResponse response = service.get(PAYMENT_ID.toString());

    assertThat(response.payment().id()).isEqualTo(PAYMENT_ID);
    assertThat(response.payment().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(response.payment().eventSequence()).isEqualTo(0);
    assertThat(response.attempts())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.routeCode()).isEqualTo("BANK_STANDARD");
              assertThat(row.providerCode()).isEqualTo("BANK_ALPHA");
              assertThat(row.errorCode()).isEqualTo("SIMULATED_PROVIDER_FAILURE");
              assertThat(row.errorMessage()).isEqualTo("provider rejected it");
            });
    assertThat(response.outboxEvents())
        .extracting(PaymentOperationsResponse.OutboxEvent::eventId)
        .containsExactly(firstEventId, secondEventId);
    assertThat(response.outboxEvents().get(0).payload())
        .containsEntry("errorMessage", "provider rejected it");
    assertThat(response.timelineEvents())
        .extracting(TimelineEvent::eventId)
        .containsExactly(firstEventId, secondEventId);
    assertThat(response.operations())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.namespace()).isEqualTo(PaymentOperation.Namespace.INTERNAL);
              assertThat(row.response()).containsEntry("eventId", secondEventId.toString());
            });
    assertThat(response.ledgerEntries())
        .extracting(PaymentOperationsResponse.LedgerEntry::journalReference)
        .containsExactly(firstLedger.getJournalReference(), secondLedger.getJournalReference());
    assertThat(response.recovery().decision()).isEqualTo(RecoveryDecision.NOT_REQUIRED);
    verify(outboxEvents).findAllById(Set.of(firstEventId, secondEventId));
    verify(journals)
        .findByJournalReferenceInOrderByJournalReferenceAsc(
            List.of(
                "payment:" + PAYMENT_ID,
                "refund:" + PAYMENT_ID + ":clearing:debit",
                "refund:" + PAYMENT_ID + ":sender:credit",
                "refund:" + PAYMENT_ID + ":fee:debit"));
    verifyNoWrites();
  }

  @Test
  void sentDeliveryIsNotPresentedAsConsumed() {
    Payment payment = payment(PaymentStatus.PROCESSING);
    PayoutAttempt attempt = processingAttempt(1);
    UUID eventId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    OutboxEvent event = outboxEvent(eventId, EventTopics.PAYOUT_SUBMITTED, 1, Map.of("attempt", 1));
    OutboxDelivery delivery = new OutboxDelivery(eventId, PAYMENT_ID, 1, BASE);
    setField(delivery, "state", "SENT");
    setField(delivery, "attemptCount", 2);
    setField(delivery, "sentAt", BASE.plusSeconds(5));
    setField(delivery, "lastError", "not a delivery failure");

    stub(payment);
    when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(attempt));
    when(deliveries.findByPaymentIdOrderByAggregateSequenceAsc(PAYMENT_ID))
        .thenReturn(List.of(delivery));
    when(outboxEvents.findAllById(Set.of(eventId))).thenReturn(List.of(event));

    PaymentOperationsResponse response = service.get(PAYMENT_ID.toString());

    assertThat(response.outboxEvents())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.eventId()).isEqualTo(eventId);
              assertThat(row.delivery().state()).isEqualTo("SENT");
              assertThat(row.delivery().attemptCount()).isEqualTo(2);
              assertThat(row.delivery().sentAt()).isEqualTo(BASE.plusSeconds(5));
              assertThat(row.delivery().lastError()).isEqualTo("not a delivery failure");
            });
    assertThat(response.timelineEvents()).isEmpty();
    assertThat(response.recovery().decision()).isEqualTo(RecoveryDecision.RECONCILIATION_REQUIRED);
  }

  @Test
  void derivesRetryDecisionAndNextRunFromPersistedCommand() {
    Payment payment = payment(PaymentStatus.FAILED);
    PayoutAttempt attempt = failedAttempt(1, "FAILURE", "failed");
    UUID commandId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    OutboxEvent command =
        outboxEvent(
            commandId,
            EventTopics.PAYOUT_RETRY,
            4,
            Map.of("failedAttempt", 1, "nextRun", NEXT_RUN.toString()));
    OutboxDelivery delivery = new OutboxDelivery(commandId, PAYMENT_ID, 4, BASE);
    PaymentOperation retry =
        new PaymentOperation(
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            USER_ID,
            PaymentOperation.Namespace.INTERNAL,
            "AUTO_RETRY",
            "auto:retry:" + PAYMENT_ID + ":1",
            "{}",
            200,
            "{\"eventId\":\"" + commandId + "\"}",
            PAYMENT_ID,
            BASE);
    PaymentOperation publicRetry =
        new PaymentOperation(
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            USER_ID,
            PaymentOperation.Namespace.PUBLIC,
            "AUTO_RETRY",
            "public-retry",
            "{}",
            200,
            "{}",
            PAYMENT_ID,
            BASE);

    stub(payment);
    when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(attempt));
    when(deliveries.findByPaymentIdOrderByAggregateSequenceAsc(PAYMENT_ID))
        .thenReturn(List.of(delivery));
    when(outboxEvents.findAllById(Set.of(commandId))).thenReturn(List.of(command));
    when(operations.findByPaymentIdOrderByCreatedAtAscIdAsc(PAYMENT_ID))
        .thenReturn(List.of(retry, publicRetry));

    PaymentOperationsResponse response = service.get(PAYMENT_ID.toString());

    assertThat(response.recovery().decision()).isEqualTo(RecoveryDecision.RETRY_SCHEDULED);
    assertThat(response.recovery().automatedRetryCount()).isEqualTo(1);
    assertThat(response.recovery().nextRun()).isEqualTo(NEXT_RUN);
  }

  @Test
  void derivesRefundDecisionFromPersistedPaymentStatus() {
    Payment payment = payment(PaymentStatus.REFUNDED);
    PayoutAttempt attempt = failedAttempt(6, "FAILURE", "exhausted");
    UUID refundCommandId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    OutboxEvent refundCommand =
        outboxEvent(
            refundCommandId,
            EventTopics.PAYOUT_REFUND,
            8,
            Map.of("failedAttempt", 6, "nextRun", BASE.toString()));
    OutboxDelivery delivery = new OutboxDelivery(refundCommandId, PAYMENT_ID, 8, BASE);

    stub(payment);
    when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(attempt));
    when(deliveries.findByPaymentIdOrderByAggregateSequenceAsc(PAYMENT_ID))
        .thenReturn(List.of(delivery));
    when(outboxEvents.findAllById(Set.of(refundCommandId))).thenReturn(List.of(refundCommand));

    PaymentOperationsResponse response = service.get(PAYMENT_ID.toString());

    assertThat(response.recovery().decision()).isEqualTo(RecoveryDecision.REFUNDED);
  }

  @Test
  void derivesReconciliationForCurrentProcessingAttemptWithoutTerminalEvent() {
    Payment payment = payment(PaymentStatus.PROCESSING);
    PayoutAttempt attempt = processingAttempt(2);

    stub(payment);
    when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(attempt));

    PaymentOperationsResponse response = service.get(PAYMENT_ID.toString());

    assertThat(response.recovery().decision()).isEqualTo(RecoveryDecision.RECONCILIATION_REQUIRED);
  }

  @Test
  void marksAnOlderRecoveryCommandStaleBeforeConsideringCurrentCommands() {
    Payment payment = payment(PaymentStatus.FAILED);
    PayoutAttempt attempt = failedAttempt(2, "FAILURE", "latest failure");
    UUID staleId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    UUID currentId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    OutboxEvent stale =
        outboxEvent(
            staleId,
            EventTopics.PAYOUT_RETRY,
            3,
            Map.of("failedAttempt", 1, "nextRun", BASE.plusSeconds(1).toString()));
    OutboxEvent current =
        outboxEvent(
            currentId,
            EventTopics.PAYOUT_RETRY,
            4,
            Map.of("failedAttempt", 2, "nextRun", BASE.plusSeconds(2).toString()));
    OutboxDelivery staleDelivery = new OutboxDelivery(staleId, PAYMENT_ID, 3, BASE);
    OutboxDelivery currentDelivery = new OutboxDelivery(currentId, PAYMENT_ID, 4, BASE);

    stub(payment);
    when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(attempt));
    when(deliveries.findByPaymentIdOrderByAggregateSequenceAsc(PAYMENT_ID))
        .thenReturn(List.of(staleDelivery, currentDelivery));
    when(outboxEvents.findAllById(Set.of(staleId, currentId))).thenReturn(List.of(current, stale));

    PaymentOperationsResponse response = service.get(PAYMENT_ID.toString());

    assertThat(response.recovery().decision()).isEqualTo(RecoveryDecision.STALE);
    assertThat(response.recovery().nextRun()).isEqualTo(BASE.plusSeconds(2));
  }

  @Test
  void malformedPersistedTimelineAndOperationJsonFailWithAUsefulError() {
    Payment payment = payment(PaymentStatus.FAILED);
    PaymentEvent invalidTimeline =
        PaymentEvent.create(
            UUID.fromString("12121212-1212-1212-1212-121212121212"),
            PAYMENT_ID.toString(),
            EventTopics.PAYOUT_FAILED,
            EventTopics.PAYOUT_FAILED,
            "corr",
            "not-json",
            BASE);
    PaymentOperation invalidOperation =
        new PaymentOperation(
            UUID.fromString("13131313-1313-1313-1313-131313131313"),
            USER_ID,
            PaymentOperation.Namespace.INTERNAL,
            "AUTO_RETRY",
            "invalid-response",
            "{}",
            200,
            "{}",
            PAYMENT_ID,
            BASE);
    setField(invalidOperation, "responseData", "not-json");

    stub(payment);
    when(timelineEvents.findByPaymentIdOrderByOccurredAtAscEventIdAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of(invalidTimeline));
    when(operations.findByPaymentIdOrderByCreatedAtAscIdAsc(PAYMENT_ID))
        .thenReturn(List.of(invalidOperation));

    assertThatThrownBy(() -> service.get(PAYMENT_ID.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("persisted operations JSON is invalid");
  }

  private void stub(Payment payment) {
    lenient().when(payments.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
    lenient()
        .when(attempts.findByPaymentIdOrderByAttemptNumberAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of());
    lenient()
        .when(deliveries.findByPaymentIdOrderByAggregateSequenceAsc(PAYMENT_ID))
        .thenReturn(List.of());
    lenient().when(outboxEvents.findAllById(anySet())).thenReturn(List.of());
    lenient()
        .when(timelineEvents.findByPaymentIdOrderByOccurredAtAscEventIdAsc(PAYMENT_ID.toString()))
        .thenReturn(List.of());
    lenient()
        .when(operations.findByPaymentIdOrderByCreatedAtAscIdAsc(PAYMENT_ID))
        .thenReturn(List.of());
    lenient()
        .when(journals.findByJournalReferenceInOrderByJournalReferenceAsc(anyCollection()))
        .thenReturn(List.of());
    lenient()
        .when(ledgerEntries.findByJournalReferenceInOrderByCreatedAtAscIdAsc(anyCollection()))
        .thenReturn(List.of());
    lenient().when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of());
  }

  private Payment payment(PaymentStatus status) {
    Recipient recipient =
        new Recipient(
            UUID.fromString("14141414-1414-1414-1414-141414141414"),
            USER_ID,
            "Recipient",
            "account",
            "Bank",
            "US",
            "USD",
            RecipientStatus.ACTIVE,
            BASE);
    Payment payment =
        new Payment(
            PAYMENT_ID,
            USER_ID,
            UUID.fromString("15151515-1515-1515-1515-151515151515"),
            recipient,
            new BigDecimal("100.0000"),
            "USD",
            "USD",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{}",
            BASE);
    payment.quoted(1, BASE);
    if (status != PaymentStatus.DRAFT) {
      payment.selectAndProcess(QUOTE_ID, BASE.plusSeconds(1));
    }
    if (status == PaymentStatus.FAILED || status == PaymentStatus.REFUNDED) {
      payment.failPayout(BASE.plusSeconds(2));
    }
    if (status == PaymentStatus.REFUNDED) {
      payment.refundPayout(BASE.plusSeconds(3));
    }
    if (status == PaymentStatus.COMPLETED) {
      payment.completePayout(BASE.plusSeconds(2));
    }
    return payment;
  }

  private TransferRoute route() {
    TransferProvider provider =
        TransferProvider.create(
            PROVIDER_ID, "BANK_ALPHA", "Bank Alpha", RailType.BANK_NETWORK, true, false, BASE);
    return TransferRoute.create(
        ROUTE_ID,
        provider,
        "BANK_STANDARD",
        "Bank Standard",
        DestinationType.EXTERNAL_ACCOUNT,
        "US",
        "USD",
        new BigDecimal("5.0000"),
        new BigDecimal("0.500000"),
        60,
        new BigDecimal("99.00"),
        new BigDecimal("1.0000"),
        new BigDecimal("500000.0000"),
        true,
        false,
        BASE);
  }

  private PayoutAttempt processingAttempt(int number) {
    PayoutAttempt attempt =
        PayoutAttempt.initiated(attemptId(number), PAYMENT_ID.toString(), number, ROUTE_ID, BASE);
    attempt.markProcessing();
    return attempt;
  }

  private PayoutAttempt failedAttempt(int number, String code, String message) {
    PayoutAttempt attempt = processingAttempt(number);
    attempt.markFailed(code, message, BASE.plusSeconds(number));
    return attempt;
  }

  private UUID attemptId(int number) {
    return UUID.nameUUIDFromBytes(("attempt:" + number).getBytes());
  }

  private OutboxEvent outboxEvent(UUID id, String type, int sequence, Map<String, Object> details) {
    PaymentEventEnvelope envelope =
        PaymentEventEnvelope.create(
            type,
            id.toString(),
            PAYMENT_ID.toString(),
            "corr-" + id,
            BASE.plusSeconds(sequence),
            1,
            sequence,
            details);
    return new OutboxEvent(id, type, eventCodec.write(envelope), BASE.plusSeconds(sequence));
  }

  private PaymentEvent timelineEvent(
      UUID id, String type, String correlationId, Map<String, Object> payload, Instant occurredAt) {
    try {
      return PaymentEvent.create(
          id,
          PAYMENT_ID.toString(),
          type,
          type,
          correlationId,
          objectMapper.writeValueAsString(payload),
          occurredAt);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }

  private LedgerEntry ledgerEntry(
      String reference, String idempotencyKey, String type, String amount, Instant createdAt) {
    return new LedgerEntry(
        UUID.fromString("16161616-1616-1616-1616-161616161616"),
        type,
        new BigDecimal(amount),
        "USD",
        idempotencyKey,
        reference,
        "operations fixture",
        createdAt);
  }

  private void setField(Object target, String name, Object value) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private void verifyNoWrites() {
    verify(payments, never()).save(any(Payment.class));
    verify(payments, never()).saveAndFlush(any(Payment.class));
    verify(payments, never()).delete(any(Payment.class));
    verify(payments, never()).deleteAll();
    verify(attempts, never()).save(any(PayoutAttempt.class));
    verify(attempts, never()).saveAndFlush(any(PayoutAttempt.class));
    verify(attempts, never()).delete(any(PayoutAttempt.class));
    verify(attempts, never()).deleteAll();
    verify(outboxEvents, never()).save(any(OutboxEvent.class));
    verify(outboxEvents, never()).saveAndFlush(any(OutboxEvent.class));
    verify(outboxEvents, never()).delete(any(OutboxEvent.class));
    verify(outboxEvents, never()).deleteAll();
    verify(deliveries, never()).save(any(OutboxDelivery.class));
    verify(deliveries, never()).saveAndFlush(any(OutboxDelivery.class));
    verify(deliveries, never()).delete(any(OutboxDelivery.class));
    verify(deliveries, never()).deleteAll();
    verify(deliveries, never())
        .markSentIfClaimed(any(UUID.class), any(String.class), any(Instant.class));
    verify(deliveries, never())
        .scheduleRetryIfClaimed(
            any(UUID.class), any(String.class), any(Instant.class), any(String.class));
    verify(timelineEvents, never()).save(any(PaymentEvent.class));
    verify(timelineEvents, never()).saveAndFlush(any(PaymentEvent.class));
    verify(timelineEvents, never()).delete(any(PaymentEvent.class));
    verify(timelineEvents, never()).deleteAll();
    verify(operations, never()).save(any(PaymentOperation.class));
    verify(operations, never()).saveAndFlush(any(PaymentOperation.class));
    verify(operations, never()).delete(any(PaymentOperation.class));
    verify(operations, never()).deleteAll();
    verify(journals, never()).saveAndFlush(any(LedgerJournal.class));
    verify(ledgerEntries, never()).save(any(LedgerEntry.class));
  }
}
