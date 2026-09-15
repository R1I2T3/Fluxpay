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
import org.springframework.data.domain.PageRequest;
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
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(lease, "lease must not be null");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive");
    }
    if (lease.isZero() || lease.isNegative()) {
      throw new IllegalArgumentException("lease must be positive");
    }
    List<OutboxDelivery> eligible = deliveries.claimEligible(now, PageRequest.of(0, batchSize));
    List<Claimed> claimed = new ArrayList<>();
    for (OutboxDelivery delivery : eligible) {
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
    if (claimToken == null) {
      return;
    }
    deliveries.markSentIfClaimed(eventId, claimToken, now);
  }

  @Transactional
  public void scheduleRetry(UUID eventId, String claimToken, Instant nextAttempt, String error) {
    if (claimToken == null) {
      return;
    }
    String lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    deliveries.scheduleRetryIfClaimed(eventId, claimToken, nextAttempt, lastError);
  }

  public Instant retryDelay(int attemptCount, Instant now) {
    long delaySeconds = Math.min(60, 1L << Math.min(attemptCount, 5));
    return now.plus(Duration.ofSeconds(delaySeconds));
  }
}
