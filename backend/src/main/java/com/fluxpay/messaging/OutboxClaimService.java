package com.fluxpay.messaging;

import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns short transactional claim/mark/retry steps for the durable outbox. Publishing happens
 * outside these transactions in {@link OutboxRelay}.
 */
@Service
public class OutboxClaimService {
  private final OutboxDeliveryRepository deliveries;
  private final OutboxEventRepository events;
  private final Clock clock;

  public OutboxClaimService(
      OutboxDeliveryRepository deliveries, OutboxEventRepository events, Clock clock) {
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public record Claimed(
      UUID eventId,
      String topic,
      String payload,
      String paymentId,
      String claim,
      int attemptCount) {}

  @Transactional
  public List<Claimed> claimBatch(Instant now, int batchSize, Duration lease) {
    List<OutboxDelivery> eligible = deliveries.claimEligible(now);
    List<Claimed> claimed = new ArrayList<>();
    for (OutboxDelivery delivery : eligible) {
      if (claimed.size() >= batchSize) {
        break;
      }
      // Per-payment ordering: never let a later sequence overtake an earlier unsent event.
      if (deliveries.existsUnsentEarlier(delivery.paymentId(), delivery.aggregateSequence())) {
        continue;
      }
      // Re-check state inside the write lock: skip SENDING with a live lease that became
      // visible after the query (concurrent dispatcher already holds it).
      if ("SENDING".equals(delivery.state())
          && delivery.leaseExpiresAt() != null
          && delivery.leaseExpiresAt().isAfter(now)) {
        continue;
      }
      if (!"PENDING".equals(delivery.state()) && !"SENDING".equals(delivery.state())) {
        continue;
      }
      String token = UUID.randomUUID().toString();
      delivery.claim(token, now.plus(lease));
      deliveries.saveAndFlush(delivery);
      OutboxEvent event =
          events
              .findById(delivery.eventId())
              .orElseThrow(
                  () -> new IllegalStateException("Missing outbox event " + delivery.eventId()));
      claimed.add(
          new Claimed(
              delivery.eventId(),
              event.topic(),
              event.payload(),
              delivery.paymentId().toString(),
              token,
              delivery.attemptCount()));
    }
    return claimed;
  }

  @Transactional
  public void markSent(UUID eventId, String claimToken, Instant now) {
    var delivery = deliveries.findById(eventId).orElse(null);
    if (delivery == null || !"SENDING".equals(delivery.state())) {
      return;
    }
    if (claimToken == null || !claimToken.equals(delivery.claimToken())) {
      return;
    }
    delivery.markSent(now);
    deliveries.save(delivery);
  }

  @Transactional
  public void scheduleRetry(UUID eventId, String claimToken, Instant nextAttempt, String error) {
    var delivery = deliveries.findById(eventId).orElse(null);
    if (delivery == null || !"SENDING".equals(delivery.state())) {
      return;
    }
    if (claimToken == null || !claimToken.equals(delivery.claimToken())) {
      return;
    }
    delivery.scheduleRetry(nextAttempt, error);
    deliveries.save(delivery);
  }

  public Instant retryDelay(int attemptCount, Instant now) {
    long delaySeconds = Math.min(60, 1L << Math.min(attemptCount, 5));
    return now.plus(Duration.ofSeconds(delaySeconds));
  }
}
