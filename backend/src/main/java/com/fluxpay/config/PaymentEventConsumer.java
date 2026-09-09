package com.fluxpay.config;

import com.fluxpay.dto.EventTopics;
import com.fluxpay.service.PaymentEventIngestionService;
import java.util.Objects;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentEventConsumer {

  private final PaymentEventIngestionService ingestionService;

  public PaymentEventConsumer(PaymentEventIngestionService ingestionService) {
    this.ingestionService =
        Objects.requireNonNull(ingestionService, "ingestionService must not be null");
  }

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
      groupId = "${fluxpay.kafka.timeline-group:fluxpay-timeline}")
  public void onEvent(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    ingestionService.ingest(topic, json);
  }
}
