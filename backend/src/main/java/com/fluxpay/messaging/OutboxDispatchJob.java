package com.fluxpay.messaging;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled caller for the durable outbox relay (repairs the previously missing caller). */
@Component
public class OutboxDispatchJob {
  private static final Logger log = LoggerFactory.getLogger(OutboxDispatchJob.class);
  private final OutboxRelay relay;
  private final int batchSize;

  public OutboxDispatchJob(
      OutboxRelay relay, @Value("${fluxpay.outbox.batch-size:50}") int batchSize) {
    this.relay = Objects.requireNonNull(relay, "relay must not be null");
    this.batchSize = batchSize;
  }

  @Scheduled(
      fixedDelayString = "${fluxpay.outbox.dispatch-delay-ms:1000}",
      initialDelayString = "${fluxpay.outbox.dispatch-initial-delay-ms:0}")
  public void dispatch() {
    try {
      relay.relayOnce(batchSize);
    } catch (Exception e) {
      log.warn("outbox dispatch failed", e);
    }
  }
}
