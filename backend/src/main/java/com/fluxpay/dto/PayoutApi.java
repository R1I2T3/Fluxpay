package com.fluxpay.dto;

import java.util.List;

/** REST shapes for payout submission and recovery. */
public final class PayoutApi {
  private PayoutApi() {}

  public record SubmitRequest(String routeCode) {}

  public record SwitchRequest(String routeCode, java.util.UUID quoteId) {}

  public record OutcomeResponse(
      Integer attemptNumber,
      String routeCode,
      String status,
      String providerRef,
      String error,
      List<RecoveryAction> allowed,
      Boolean alreadyConfirmed,
      String originalEventId,
      com.fluxpay.domain.AcceptedQuote selectedQuote) {
    public OutcomeResponse(
        Integer attemptNumber,
        String routeCode,
        String status,
        String providerRef,
        String error,
        List<RecoveryAction> allowed,
        Boolean alreadyConfirmed,
        String originalEventId) {
      this(
          attemptNumber,
          routeCode,
          status,
          providerRef,
          error,
          allowed,
          alreadyConfirmed,
          originalEventId,
          null);
    }

    public OutcomeResponse {
      allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }
  }
}
