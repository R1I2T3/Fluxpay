package com.fluxpay.messaging;

public interface EventPublisher {
  void publish(String topic, Object payload, String correlationId);
}
