package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.dto.TimelineEventResponse;
import com.fluxpay.repository.PaymentEventStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class TimelineService {

  private final PaymentEventStore store;
  private final ObjectMapper objectMapper;

  public TimelineService(PaymentEventStore store, ObjectMapper objectMapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public List<TimelineEventResponse> getTimeline(String paymentId) {
    return store.timeline(paymentId).stream()
        .map(this::toResponse)
        .sorted(
            Comparator.comparing(TimelineEventResponse::occurredAt)
                .thenComparing(TimelineEventResponse::eventId))
        .toList();
  }

  private TimelineEventResponse toResponse(PaymentEvent event) {
    Map<String, Object> payload;
    try {
      payload =
          objectMapper.readValue(event.payload(), new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new IllegalStateException("stored event payload is invalid", e);
    }
    return new TimelineEventResponse(
        event.eventId(),
        event.paymentId(),
        event.eventType(),
        event.kafkaTopic(),
        event.correlationId(),
        payload,
        event.occurredAt());
  }
}
