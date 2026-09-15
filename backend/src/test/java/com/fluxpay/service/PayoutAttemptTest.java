package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutAttemptTest {
  private static final UUID ATTEMPT_ID = UUID.nameUUIDFromBytes("fluxpay:attempt:a-1".getBytes());
  private static final UUID ROUTE_ID =
      UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes());

  @Test
  void followsInitiatedProcessingFailedAndKeepsFailureDetails() {
    var attempt = PayoutAttempt.initiated(ATTEMPT_ID, "P-001", 1, ROUTE_ID, Instant.EPOCH);
    attempt.markProcessing();
    attempt.markFailed("PROVIDER_TIMEOUT", "Simulated bank timeout", Instant.EPOCH.plusSeconds(1));
    assertThat(attempt.status()).isEqualTo(PayoutAttemptStatus.FAILED);
    assertThat(attempt.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
  }

  @Test
  void terminalAttemptCannotTransitionAgain() {
    var attempt = PayoutAttempt.initiated(ATTEMPT_ID, "P-001", 1, ROUTE_ID, Instant.EPOCH);
    attempt.markProcessing();
    attempt.markCompleted("SB-1", Instant.EPOCH.plusSeconds(1));
    assertThatThrownBy(
            () -> attempt.markFailed("LATE", "late result", Instant.EPOCH.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("attempt is terminal");
  }

  @Test
  void terminalTimestampComesFromTheApplicationClock() {
    Instant completedAt = Instant.parse("2026-09-15T04:30:00Z");
    var attempt = PayoutAttempt.initiated(ATTEMPT_ID, "P-001", 1, ROUTE_ID, Instant.EPOCH);
    attempt.markProcessing();

    attempt.markCompleted("SB-1", completedAt);

    assertThat(attempt.completedAt()).isEqualTo(completedAt);
  }
}
