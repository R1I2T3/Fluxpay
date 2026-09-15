package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaTransportTest {
  @Test
  void sendsTopicPayloadWithKey() throws Exception {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    var transport = new KafkaTransport(kafka);

    transport.send("payment.initiated", "{\"a\":1}", "P-001");

    ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(topic.capture(), key.capture(), payload.capture());
    org.assertj.core.api.Assertions.assertThat(topic.getValue()).isEqualTo("payment.initiated");
    org.assertj.core.api.Assertions.assertThat(key.getValue()).isEqualTo("P-001");
    org.assertj.core.api.Assertions.assertThat(payload.getValue()).isEqualTo("{\"a\":1}");
  }

  @Test
  void wrapsSendFailures() {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
    var transport = new KafkaTransport(kafka);

    assertThatThrownBy(() -> transport.send("payout.submitted", "{}", "P-001"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Kafka send failed");
  }
}
