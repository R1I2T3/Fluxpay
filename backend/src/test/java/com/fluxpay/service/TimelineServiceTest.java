package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.dto.TimelineEventResponse;
import com.fluxpay.repository.PaymentEventStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TimelineServiceTest {
  private static final UUID E1 =
      UUID.nameUUIDFromBytes("fluxpay:e-1".getBytes(StandardCharsets.UTF_8));
  private static final UUID E2 =
      UUID.nameUUIDFromBytes("fluxpay:e-2".getBytes(StandardCharsets.UTF_8));

  @Test
  void sortsByOccurredAtThenEventIdEvenWhenStoreArrivalIsReversed() {
    PaymentEvent later =
        PaymentEvent.create(
            E2,
            "P-001",
            "payout.failed",
            "payout.failed",
            "c-2",
            "{\"summary\":\"Failed\"}",
            Instant.parse("2026-09-04T10:00:02Z"));
    PaymentEvent earlier =
        PaymentEvent.create(
            E1,
            "P-001",
            "payout.submitted",
            "payout.submitted",
            "c-1",
            "{\"summary\":\"Submitted\"}",
            Instant.parse("2026-09-04T10:00:01Z"));
    PaymentEventStore store = new StubTimelineStore(List.of(later, earlier));
    var service = new TimelineService(store, new ObjectMapper());

    assertThat(service.getTimeline("P-001"))
        .extracting(TimelineEventResponse::eventId)
        .containsExactly(E1.toString(), E2.toString());
  }

  private static final class StubTimelineStore implements PaymentEventStore {
    private final List<PaymentEvent> rows;

    private StubTimelineStore(List<PaymentEvent> rows) {
      this.rows = List.copyOf(rows);
    }

    @Override
    public boolean appendIfAbsent(PaymentEvent event) {
      throw new UnsupportedOperationException("read-only stub");
    }

    @Override
    public List<PaymentEvent> timeline(String paymentId) {
      return rows;
    }

    @Override
    public boolean contains(String paymentId, String eventType) {
      return false;
    }
  }
}
