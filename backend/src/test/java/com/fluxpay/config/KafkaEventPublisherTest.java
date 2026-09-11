package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.dto.EventEnvelopeCodec;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventPayload;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaEventPublisherTest {
  @Test
  void wrapsSynchronousAndAsynchronousSendFailures() {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var codec = new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());
    var publisher = new KafkaEventPublisher(kafka, codec);
    var payload = PaymentEventPayload.random("P-001", Instant.now(), Map.of());
    var failure = new org.apache.kafka.common.errors.TimeoutException("metadata unavailable");
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenThrow(failure)
        .thenReturn(CompletableFuture.failedFuture(failure));

    for (int i = 0; i < 2; i++) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> publisher.publish(EventTopics.PAYOUT_SUBMITTED, payload, "c-uuid"))
          .isInstanceOf(com.fluxpay.exception.EventPublishException.class)
          .hasRootCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
    }
  }

  @Test
  void sendsOnceWithPaymentIdAsKeyAndACompleteEnvelope() {
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    var codec = new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());
    var publisher = new KafkaEventPublisher(kafka, codec);
    var payload =
        PaymentEventPayload.random(
            "P-001", Instant.parse("2026-09-04T10:00:00Z"), Map.of("attempt", 1));

    publisher.publish(EventTopics.PAYOUT_SUBMITTED, payload, "c-uuid");

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq("payout.submitted"), eq("P-001"), json.capture());
    assertThat(codec.read(json.getValue()).eventId()).isEqualTo(payload.eventId());
  }
}
