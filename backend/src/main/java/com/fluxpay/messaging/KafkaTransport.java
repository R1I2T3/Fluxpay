package com.fluxpay.messaging;

import com.fluxpay.common.contracts.TransportPort;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaTransport implements TransportPort {
  private final KafkaTemplate<String, String> kafka;

  public KafkaTransport(KafkaTemplate<String, String> kafka) {
    this.kafka = kafka;
  }

  @Override
  public void send(String topic, String payload, String key) throws Exception {
    try {
      kafka.send(topic, key, payload).get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Kafka send interrupted for topic " + topic, e);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      throw new RuntimeException("Kafka send failed for topic " + topic, e);
    }
  }
}
