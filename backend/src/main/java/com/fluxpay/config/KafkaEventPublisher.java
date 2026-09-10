package com.fluxpay.config;

import com.fluxpay.common.event.EventPublisher;
import com.fluxpay.dto.EventEnvelopeCodec;
import com.fluxpay.dto.PaymentEventEnvelope;
import com.fluxpay.dto.PaymentEventPayload;
import com.fluxpay.exception.EventPublishException;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher implements EventPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final EventEnvelopeCodec codec;

  public KafkaEventPublisher(
      KafkaTemplate<String, String> kafkaTemplate, EventEnvelopeCodec codec) {
    this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  @Override
  public void publish(String topic, Object payload, String correlationId) {
    if (!(payload instanceof PaymentEventPayload eventPayload)) {
      throw new IllegalArgumentException("payload must be PaymentEventPayload");
    }
    PaymentEventEnvelope event = PaymentEventEnvelope.from(topic, correlationId, eventPayload);
    String json = codec.write(event);
    try {
      kafkaTemplate.send(topic, event.paymentId(), json).join();
    } catch (CompletionException e) {
      throw new EventPublishException("Kafka publish failed for " + topic, e);
    }
  }
}
