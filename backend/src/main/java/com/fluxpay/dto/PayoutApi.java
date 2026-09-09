package com.fluxpay.dto;

import java.util.List;

/** REST shapes for payout submission and recovery. */
public final class PayoutApi {
  private PayoutApi() {}

  public record SubmitRequest(String routeCode) {}

  public record SwitchRequest(String routeCode) {}

  public record OutcomeResponse(
      Integer attemptNumber,
      String routeCode,
      String status,
      String providerRef,
      String error,
      List<RecoveryAction> allowed,
      Boolean alreadyConfirmed,
      String originalEventId) {
    public OutcomeResponse {
      allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }
  }
}
