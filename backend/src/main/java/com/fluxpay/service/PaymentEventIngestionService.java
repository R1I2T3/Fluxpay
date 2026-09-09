package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.dto.EventEnvelopeCodec;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventEnvelope;
import com.fluxpay.repository.PaymentEventStore;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Decodes canonical envelopes off Kafka and persists them idempotently.
 *
 * <p>A duplicate {@code eventId} is acknowledged normally (returns {@code false}) so the Kafka
 * offset can be committed; only the first delivery is stored.
 */
@Service
public class PaymentEventIngestionService {

  private final EventEnvelopeCodec codec;
  private final PaymentEventStore store;
  private final ObjectMapper payloadMapper = new ObjectMapper();

  public PaymentEventIngestionService(EventEnvelopeCodec codec, PaymentEventStore store) {
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  public boolean ingest(String receivedTopic, String json) {
    if (receivedTopic == null || !EventTopics.ALL.contains(receivedTopic)) {
      throw new IllegalArgumentException("unknown event topic: " + receivedTopic);
    }
    PaymentEventEnvelope envelope = codec.read(json);
    if (!receivedTopic.equals(envelope.eventType())) {
      throw new IllegalArgumentException(
          "received topic does not match envelope event type: " + receivedTopic);
    }
    if (envelope.correlationId() == null || envelope.correlationId().isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    String payloadJson;
    try {
      payloadJson = payloadMapper.writeValueAsString(envelope.payload());
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("event payload cannot be serialized", e);
    }
    PaymentEvent event =
        PaymentEvent.create(
            envelope.eventId(),
            envelope.paymentId(),
            envelope.eventType(),
            receivedTopic,
            envelope.correlationId(),
            payloadJson,
            envelope.occurredAt());
    return store.appendIfAbsent(event);
  }
}
