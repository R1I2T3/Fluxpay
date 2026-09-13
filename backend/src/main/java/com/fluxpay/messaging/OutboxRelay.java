package com.fluxpay.messaging;

import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.common.contracts.TransportPort;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
  private final OutboxDeliveryRepository deliveries;
  private final OutboxEventRepository events;
  private final TransportPort transport;
  private final Clock clock;

  public OutboxRelay(
      OutboxDeliveryRepository deliveries,
      OutboxEventRepository events,
      TransportPort transport,
      Clock clock) {
    this.deliveries = deliveries;
    this.events = events;
    this.transport = transport;
    this.clock = clock;
  }

  @Transactional
  public int relayOnce(int batchSize) {
    Instant now = Instant.now(clock);
    List<OutboxDelivery> eligible = deliveries.claimEligible(now);
    int sent = 0;
    for (OutboxDelivery delivery : eligible.stream().limit(batchSize).toList()) {
      String claim = UUID.randomUUID().toString();
      Instant leaseExpiry = now.plus(Duration.ofSeconds(60));
      delivery.claim(claim, leaseExpiry);
      deliveries.saveAndFlush(delivery);
      OutboxEvent event =
          events
              .findById(delivery.eventId())
              .orElseThrow(
                  () -> new IllegalStateException("Missing outbox event " + delivery.eventId()));
      try {
        transport.send(event.topic(), event.payload(), delivery.paymentId().toString());
      } catch (Exception e) {
        long delaySeconds = Math.min(60, 1L << Math.min(delivery.attemptCount(), 5));
        delivery.scheduleRetry(
            Instant.now(clock).plus(Duration.ofSeconds(delaySeconds)), e.getMessage());
        deliveries.save(delivery);
        continue;
      }
      if (!claim.equals(delivery.claimToken())) {
        continue;
      }
      delivery.markSent(Instant.now(clock));
      deliveries.save(delivery);
      sent++;
    }
    return sent;
  }
}
