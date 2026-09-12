package com.fluxpay.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class InMemoryPaymentEligibilityGateTest {
  @Test
  void onlyCompletedReservationsReplayAnActualExecutionEvent() {
    var gate = new InMemoryPaymentEligibilityGate();
    var payment = new InMemoryPaymentReader().get("P-001");
    var reservation = gate.confirmIdempotent(payment, "key");
    assertThat(reservation.alreadyConfirmed()).isFalse();
    assertThat(reservation.originalEventId()).isNull();
    assertThatThrownBy(() -> gate.confirmIdempotent(payment, "key"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("in progress");
    gate.complete(payment, "key", "published-event");
    gate.release(payment, "key");
    var replay = gate.confirmIdempotent(payment, "key");
    assertThat(replay.alreadyConfirmed()).isTrue();
    assertThat(replay.originalEventId()).isEqualTo("published-event");
  }

  @Test
  void failedReservationCanBeRetried() {
    var gate = new InMemoryPaymentEligibilityGate();
    var payment = new InMemoryPaymentReader().get("P-001");
    gate.confirmIdempotent(payment, "key");
    gate.release(payment, "key");
    assertThat(gate.confirmIdempotent(payment, "key").alreadyConfirmed()).isFalse();
  }
}
