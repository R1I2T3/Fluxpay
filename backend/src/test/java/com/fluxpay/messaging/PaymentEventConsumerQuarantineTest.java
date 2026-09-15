package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.service.PaymentEventIngestionService;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class PaymentEventConsumerQuarantineTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void quarantineUsesObjectMapperAndRetainsOriginalPayload() {
    var ingestion = mock(PaymentEventIngestionService.class);
    doThrow(new IllegalArgumentException("bad envelope with \"quotes\""))
        .when(ingestion)
        .ingest(anyString(), anyString());
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    var consumer = new PaymentEventConsumer(ingestion, kafka, mapper);

    String poison = "{\"eventType\":\"payout.completed\",\"broken\": \"unclosed";
    consumer.onEvent(poison, EventTopics.PAYOUT_COMPLETED, 2, 99L);

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq(PaymentEventConsumer.RECOVERY_DLT), anyString(), json.capture());
    JsonNode node;
    try {
      node = mapper.readTree(json.getValue());
    } catch (Exception e) {
      throw new AssertionError("quarantine record must be valid JSON", e);
    }
    assertThat(node.get("sourceTopic").asText()).isEqualTo("payout.completed");
    assertThat(node.get("partition").asInt()).isEqualTo(2);
    assertThat(node.get("offset").asLong()).isEqualTo(99L);
    assertThat(node.get("originalPayload").asText()).isEqualTo(poison);
    assertThat(node.get("error").asText()).contains("bad envelope");
    assertThat(node.has("quarantineId")).isTrue();
  }

  @Test
  void invalidJsonAndEscapingDoNotBreakQuarantine() {
    var ingestion = mock(PaymentEventIngestionService.class);
    doThrow(new IllegalArgumentException("invalid \"json\" \\ test"))
        .when(ingestion)
        .ingest(anyString(), anyString());
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    var consumer = new PaymentEventConsumer(ingestion, kafka, mapper);

    String tricky = "not-json-\"\\\u0000-payload";
    consumer.onEvent(tricky, EventTopics.PAYMENT_INITIATED, null, null);

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq(PaymentEventConsumer.RECOVERY_DLT), anyString(), json.capture());
    // Must still be parseable JSON despite tricky bytes and null coordinates.
    try {
      JsonNode node = mapper.readTree(json.getValue());
      assertThat(node.get("originalPayload").asText()).isEqualTo(tricky);
      assertThat(node.get("sourceTopic").asText()).isEqualTo("payment.initiated");
    } catch (Exception e) {
      throw new AssertionError("quarantine must survive escaping", e);
    }
  }
}
