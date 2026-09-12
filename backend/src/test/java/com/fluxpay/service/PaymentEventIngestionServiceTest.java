package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.dto.EventEnvelopeCodec;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventEnvelope;
import com.fluxpay.dto.PaymentEventPayload;
import com.fluxpay.repository.PaymentEventStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentEventIngestionServiceTest {
  @Test
  void duplicateEventIdIsAcknowledgedButStoredOnce() {
    var store = new RecordingStore();
    var codec = new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());
    var service = new PaymentEventIngestionService(codec, store);
    var payload =
        PaymentEventPayload.random(
            "P-001", Instant.parse("2026-09-04T10:00:00Z"), Map.of("summary", "Payout submitted"));
    String json =
        codec.write(PaymentEventEnvelope.from(EventTopics.PAYOUT_SUBMITTED, "c-uuid", payload));

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
