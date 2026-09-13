package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Durable payout intent. Task 8 owns the common outbox API and delivery lifecycle. */
@Service
public class PayoutOutboxService {
  private final OutboxEventRepository events;
  private final OutboxDeliveryRepository deliveries;
  private final ObjectMapper mapper;
  private final Clock clock;

  public PayoutOutboxService(
      OutboxEventRepository events,
      OutboxDeliveryRepository deliveries,
      ObjectMapper mapper,
      Clock clock) {
    this.events = events;
    this.deliveries = deliveries;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String enqueue(
      Payment payment, String topic, String correlationId, Map<String, Object> details) {
    UUID id =
        topic.equals(com.fluxpay.messaging.EventTopics.PAYMENT_REFUNDED)
            ? UUID.fromString(
                com.fluxpay.messaging.PaymentEventPayload.refund(
                        payment.id().toString(), clock.instant(), Map.of())
                    .eventId())
            : UUID.randomUUID();
    int sequence = payment.nextEventSequence();
    var payload = new LinkedHashMap<String, Object>(details);
    payload.put("schemaVersion", 1);
    payload.put("aggregateSequence", sequence);
    var envelope =
        Map.of(
            "eventType",
            topic,
            "eventId",
            id.toString(),
            "paymentId",
            payment.id().toString(),
            "correlationId",
            correlationId,
            "occurredAt",
            clock.instant().toString(),
            "payload",
            payload);
    try {
      events.save(new OutboxEvent(id, topic, mapper.writeValueAsString(envelope), clock.instant()));
      deliveries.save(new OutboxDelivery(id, payment.id(), sequence, clock.instant()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Could not store payout event", ex);
    }
    return id.toString();
  }
}
