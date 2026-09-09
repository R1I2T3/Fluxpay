package com.fluxpay.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeCodecTest {
  private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
  private final EventEnvelopeCodec codec =
      new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());

  @Test
  void createsTheFrozenEnvelopeShape() {
    var payload =
        PaymentEventPayload.random(
            "P-001",
            NOW,
            Map.of("routeCode", "STANDARD_BANK", "attempt", 1, "summary", "Payout failed"));
    var envelope = PaymentEventEnvelope.from(EventTopics.PAYOUT_FAILED, "c-uuid", payload);

    String json = codec.write(envelope);
    PaymentEventEnvelope decoded = codec.read(json);

    assertThat(decoded.eventType()).isEqualTo("payout.failed");
    assertThat(decoded.paymentId()).isEqualTo("P-001");
    assertThat(decoded.correlationId()).isEqualTo("c-uuid");
    assertThat(decoded.occurredAt()).isEqualTo(NOW);
    assertThat(decoded.payload()).containsEntry("routeCode", "STANDARD_BANK");
  }

  @Test
  void refundIdentityIsStableAcrossRepeatedRequests() {
    String first = PaymentEventPayload.refund("P-001", NOW, Map.of()).eventId();
    String second = PaymentEventPayload.refund("P-001", NOW.plusSeconds(10), Map.of()).eventId();
    assertThat(first).isEqualTo(second);
  }
}
