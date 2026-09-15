package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.messaging.EventEnvelopeCodec;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.messaging.PaymentEventEnvelope;
import com.fluxpay.messaging.PaymentEventPayload;
import com.fluxpay.repository.PaymentEventStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentEventIngestionServiceTest {
  @Test
  void malformedCanonicalPayloadIsRejectedBeforeTimelinePersistence() {
    var store = new RecordingStore();
    var service =
        new PaymentEventIngestionService(
            new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules()), store);
    String json =
        """
        {
          "eventType":"payment.initiated",
          "eventId":"11111111-1111-1111-1111-111111111111",
          "paymentId":"22222222-2222-2222-2222-222222222222",
          "correlationId":"corr-invalid",
          "occurredAt":"2026-09-13T10:00:00Z",
          "payload":{"aggregateSequence":1}
        }
        """;

    assertThatThrownBy(() -> service.ingest(EventTopics.PAYMENT_INITIATED, json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload.schemaVersion must equal 1");
    assertThat(store.events).isEmpty();
  }

  @Test
  void duplicateEventIdIsAcknowledgedButStoredOnce() {
    var store = new RecordingStore();
    var codec = new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());
    var service = new PaymentEventIngestionService(codec, store);
    var payload =
        PaymentEventPayload.random(
            "P-001", Instant.parse("2026-09-04T10:00:00Z"), Map.of("summary", "Payout submitted"));
    String json =
        codec.write(
            PaymentEventEnvelope.create(
                EventTopics.PAYOUT_SUBMITTED,
                payload.eventId(),
                payload.paymentId(),
                "c-uuid",
                payload.occurredAt(),
                1,
                1,
                payload.details()));

    service.ingest(EventTopics.PAYOUT_SUBMITTED, json);
    service.ingest(EventTopics.PAYOUT_SUBMITTED, json);

    assertThat(store.events).hasSize(1);
    assertThat(store.events.get(0).kafkaTopic()).isEqualTo("payout.submitted");
    assertThat(store.events.get(0).correlationId()).isEqualTo("c-uuid");
  }

  private static final class RecordingStore implements PaymentEventStore {
    private final List<PaymentEvent> events = new ArrayList<>();

    public boolean appendIfAbsent(PaymentEvent event) {
      if (events.stream().anyMatch(e -> e.eventId().equals(event.eventId()))) return false;
      events.add(event);
      return true;
    }

    public List<PaymentEvent> timeline(String id) {
      return events.stream().filter(e -> e.paymentId().equals(id)).toList();
    }

    public boolean contains(String paymentId, String eventType) {
      return events.stream()
          .anyMatch(e -> e.paymentId().equals(paymentId) && e.eventType().equals(eventType));
    }
  }
}
