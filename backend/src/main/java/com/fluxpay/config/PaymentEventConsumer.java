package com.fluxpay.config;

import com.fluxpay.dto.EventTopics;
import com.fluxpay.service.PaymentEventIngestionService;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
  public static final String RECOVERY_DLT = "payout.recovery.dlt";

  private final PaymentEventIngestionService ingestionService;
  private final KafkaTemplate<String, String> kafkaTemplate;

  public PaymentEventConsumer(
      PaymentEventIngestionService ingestionService, KafkaTemplate<String, String> kafkaTemplate) {
    this.ingestionService =
        Objects.requireNonNull(ingestionService, "ingestionService must not be null");
    this.kafkaTemplate = kafkaTemplate;
  }

  // Single-threaded to preserve per-partition order; provider I/O never runs here (timeline only).
  @Transactional
  @KafkaListener(
      topics = {
        EventTopics.PAYMENT_INITIATED,
        EventTopics.PAYMENT_ROUTE_SELECTED,
        EventTopics.PAYMENT_SCREENING_COMPLETED,
        EventTopics.PAYOUT_SUBMITTED,
        EventTopics.PAYOUT_FAILED,
        EventTopics.PAYOUT_COMPLETED,
        EventTopics.PAYMENT_REFUNDED
      },
      groupId = "${fluxpay.kafka.timeline-group:fluxpay-timeline}",
      concurrency = "1")
  public void onEvent(
      String json,
      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) Integer partition,
      @Header(value = KafkaHeaders.OFFSET, required = false) Long offset) {
    try {
      ingestionService.ingest(topic, json);
    } catch (IllegalArgumentException e) {
      // Poison record: quarantine to DLT with original location + deterministic ID, then ack.
      // Prevents one bad envelope from blocking the partition forever.
      log.warn(
          "quarantining poison event topic={} partition={} offset={}: {}",
          topic,
          partition,
          offset,
          e.getMessage());
      quarantine(topic, partition, offset, json, e);
    }
  }

  private void quarantine(String topic, Integer partition, Long offset, String json, Exception e) {
    if (kafkaTemplate == null) {
      return;
    }
    String quarantineId =
        UUID.nameUUIDFromBytes(
                (topic + ":" + partition + ":" + offset + ":" + json)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    String dltJson =
        "{\"quarantineId\":\""
            + quarantineId
            + "\",\"sourceTopic\":\""
            + topic
            + "\",\"partition\":"
            + partition
            + ",\"offset\":"
            + offset
            + ",\"error\":\""
            + e.getMessage().replace("\"", "'")
            + "\"}";
    try {
      kafkaTemplate.send(RECOVERY_DLT, quarantineId, dltJson).join();
    } catch (Exception sendFailure) {
      // DLT publish failed: rethrow to retry (don't ack) so DB outage / Kafka outage doesn't drop.
      log.error("DLT publish failed for quarantine {}", quarantineId, sendFailure);
      throw new IllegalStateException("DLT publish failed", sendFailure);
    }
  }
}
