package com.fluxpay.dto;

import com.fluxpay.beans.PayoutAttemptStatus;
import java.util.List;
import java.util.Objects;

public record PayoutOutcome(PayoutAttemptStatus status, List<RecoveryAction> allowed) {
  public PayoutOutcome {
    Objects.requireNonNull(status, "status must not be null");
    allowed = allowed == null ? List.of() : List.copyOf(allowed);
  }

  public static PayoutOutcome completed() {
    return new PayoutOutcome(PayoutAttemptStatus.COMPLETED, List.of());
  }

  public static PayoutOutcome failed() {
    return new PayoutOutcome(
        PayoutAttemptStatus.FAILED,
        List.of(RecoveryAction.RETRY, RecoveryAction.SWITCH, RecoveryAction.REFUND));
  }
}
