package com.fluxpay.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.domain.PaymentStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentOperationsResponseTest {
  @Test
  void nullCollectionsAndMapsBecomeImmutableEmptyValues() {
    var response =
        new PaymentOperationsResponse(
            new PaymentOperationsResponse.Payment(
                UUID.randomUUID(), PaymentStatus.PROCESSING, null, 0, Instant.EPOCH, Instant.EPOCH),
            null,
            null,
            null,
            null,
            null,
            new PaymentOperationsResponse.Recovery(
                0, PaymentOperationsResponse.RecoveryDecision.NOT_REQUIRED, null));

    assertThat(response.attempts()).isEmpty();
    assertThat(response.outboxEvents()).isEmpty();
    assertThat(response.timelineEvents()).isEmpty();
    assertThat(response.operations()).isEmpty();
    assertThat(response.ledgerEntries()).isEmpty();
    assertThatThrownBy(() -> response.attempts().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void payloadMapsPreserveNullValuesAndAreImmutable() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("nullableValue", null);

    var outboxEvent =
        new PaymentOperationsResponse.OutboxEvent(
            UUID.randomUUID(), "payment.updated", 1, Instant.EPOCH, payload, null);
    var timelineEvent =
        new PaymentOperationsResponse.TimelineEvent(
            UUID.randomUUID(),
            "payment.updated",
            "payment.updated",
            "correlation-id",
            payload,
            Instant.EPOCH);
    var operation =
        new PaymentOperationsResponse.Operation(
            UUID.randomUUID(),
            PaymentOperation.Namespace.PUBLIC,
            "payment",
            "client-key",
            "COMPLETED",
            200,
            payload,
            Instant.EPOCH);

    assertThat(outboxEvent.payload()).containsEntry("nullableValue", null);
    assertThat(timelineEvent.payload()).containsEntry("nullableValue", null);
    assertThat(operation.response()).containsEntry("nullableValue", null);
    assertThatThrownBy(() -> outboxEvent.payload().put("newValue", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> timelineEvent.payload().put("newValue", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> operation.response().put("newValue", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
