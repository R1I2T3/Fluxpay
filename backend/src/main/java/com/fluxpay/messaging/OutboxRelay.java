package com.fluxpay.messaging;

import com.fluxpay.common.contracts.TransportPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Durable relay: claims a bounded ordered batch in a short transaction, publishes outside any
 * transaction, then marks success/retry in follow-up transactions. Expired SENDING leases are
 * reclaimed; per-payment sequence order is preserved by the claim service.
 */
@Service
public class OutboxRelay {
  private final com.fluxpay.repository.OutboxDeliveryRepository deliveries;
  private final com.fluxpay.repository.OutboxEventRepository events;
  private final TransportPort transport;
  private final Clock clock;
  private final OutboxClaimService claims;
  private final Duration lease;

  @org.springframework.beans.factory.annotation.Autowired
  public OutboxRelay(
      com.fluxpay.repository.OutboxDeliveryRepository deliveries,
      com.fluxpay.repository.OutboxEventRepository events,
      TransportPort transport,
      Clock clock,
      OutboxClaimService claims,
      @org.springframework.beans.factory.annotation.Value("${fluxpay.outbox.lease-seconds:60}")
          long leaseSeconds) {
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.claims = Objects.requireNonNull(claims, "claims must not be null");
    this.lease = Duration.ofSeconds(leaseSeconds);
  }

  public OutboxRelay(
      com.fluxpay.repository.OutboxDeliveryRepository deliveries,
      com.fluxpay.repository.OutboxEventRepository events,
      TransportPort transport,
      Clock clock) {
    this(
        deliveries,
        events,
        transport,
        clock,
        new OutboxClaimService(deliveries, events, clock),
        60);
  }

  public int relayOnce(int batchSize) {
    Instant now = Instant.now(clock);
    List<OutboxClaimService.Claimed> batch = claims.claimBatch(now, batchSize, lease);
    int sent = 0;
    for (OutboxClaimService.Claimed claimed : batch) {
      try {
        transport.send(claimed.topic(), claimed.payload(), claimed.paymentId());
      } catch (Exception e) {
        Instant retryAt = claims.retryDelay(claimed.attemptCount(), Instant.now(clock));
        claims.scheduleRetry(claimed.eventId(), claimed.claim(), retryAt, message(e));
        continue;
      }
      claims.markSent(claimed.eventId(), claimed.claim(), Instant.now(clock));
      sent++;
    }
    return sent;
  }

  private static String message(Exception e) {
    String m = e.getMessage();
    return m == null ? e.toString() : m;
  }
}
