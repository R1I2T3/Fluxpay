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
  private final com.fluxpay.messaging.OutboxService outbox;
  private final Clock clock;

  public PayoutOutboxService(
      OutboxEventRepository events,
      OutboxDeliveryRepository deliveries,
      ObjectMapper mapper,
      Clock clock) {
    this(
        new com.fluxpay.messaging.OutboxService(
            events, deliveries, new com.fluxpay.messaging.EventEnvelopeCodec(mapper), clock),
        clock);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PayoutOutboxService(com.fluxpay.messaging.OutboxService outbox, Clock clock) {
    this.outbox = java.util.Objects.requireNonNull(outbox, "outbox must not be null");
    this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String enqueue(
      Payment payment, String topic, String correlationId, Map<String, Object> details) {
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    UUID id =
        topic.equals(com.fluxpay.messaging.EventTopics.PAYMENT_REFUNDED)
            ? UUID.fromString(
                com.fluxpay.messaging.PaymentEventPayload.refund(
                        payment.id().toString(), clock.instant(), Map.of())
                    .eventId())
            : UUID.randomUUID();
    int sequence = payment.nextEventSequence();
    var envelope =
        com.fluxpay.messaging.PaymentEventEnvelope.create(
            topic,
            id.toString(),
            payment.id().toString(),
            correlationId,
            clock.instant(),
            1,
            sequence,
            details == null ? Map.of() : details);
    return outbox.enqueue(envelope, sequence);
  }
}
