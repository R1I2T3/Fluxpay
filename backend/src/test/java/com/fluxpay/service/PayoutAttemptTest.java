package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PayoutAttemptTest {
  @Test
  void followsInitiatedProcessingFailedAndKeepsFailureDetails() {
    var attempt = PayoutAttempt.initiated("a-1", "P-001", 1, "r-standard", Instant.EPOCH);
    attempt.markProcessing();
    attempt.markFailed("PROVIDER_TIMEOUT", "Simulated bank timeout");
    assertThat(attempt.status()).isEqualTo(PayoutAttemptStatus.FAILED);
    assertThat(attempt.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
  }

  @Test
  void terminalAttemptCannotTransitionAgain() {
    var attempt = PayoutAttempt.initiated("a-1", "P-001", 1, "r-standard", Instant.EPOCH);
    attempt.markProcessing();
    attempt.markCompleted("SB-1");
    assertThatThrownBy(() -> attempt.markFailed("LATE", "late result"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("attempt is terminal");
  }
}
