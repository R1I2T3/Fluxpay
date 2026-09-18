package com.fluxpay.messaging;

import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical durable outbox writer. Persists the stable event and its delivery row in the caller's
 * transaction; a scheduled relay delivers later. The envelope event ID is reused as the outbox row
 * ID so terminal responses mean durable IDs, not Kafka acknowledgements.
 */
@Service
public class OutboxService {
  private final OutboxEventRepository events;
  private final OutboxDeliveryRepository deliveries;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public OutboxService(
      OutboxEventRepository events,
      OutboxDeliveryRepository deliveries,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String enqueue(PaymentEventEnvelope envelope, int sequence) {
    return enqueue(envelope, sequence, clock.instant());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public String enqueue(PaymentEventEnvelope envelope, int sequence, java.time.Instant nextRun) {
    Objects.requireNonNull(nextRun, "nextRun must not be null");
    PaymentEventEnvelope.validate(envelope);
    if (envelope.aggregateSequence() != sequence) {
      throw new IllegalArgumentException(
          "aggregateSequence "
              + envelope.aggregateSequence()
              + " must equal persisted sequence "
              + sequence);
    }
    UUID eventId;
    UUID paymentId;
    try {
      eventId = UUID.fromString(envelope.eventId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("eventId must be a UUID: " + envelope.eventId(), e);
    }
    try {
      paymentId = UUID.fromString(envelope.paymentId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("paymentId must be a UUID: " + envelope.paymentId(), e);
    }
    String json = codec.write(envelope);
    events.save(
        new OutboxEvent(
            eventId,
            envelope.eventType(),
            json,
            envelope.occurredAt() != null ? envelope.occurredAt() : clock.instant()));
    deliveries.save(new OutboxDelivery(eventId, paymentId, sequence, nextRun));
    return eventId.toString();
  }
}
