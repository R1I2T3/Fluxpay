package com.fluxpay.dto;

import java.time.Instant;
import java.util.*;

public record QuoteResponse(
    UUID paymentId,
    UUID recommendedQuoteId,
    String recommendationReason,
    Instant expiresAt,
    Instant serverTime,
    List<Quote> quotes) {
  public record Quote(
      UUID id,
      String routeCode,
      String marketRate,
      String offeredRate,
      String feeAmount,
      String recipientAmount,
      int estimatedMinutes,
      boolean recommended,
      UUID routeId,
      UUID providerId,
      String effectiveReliability,
      String rankingScore,
      int rankingPosition) {
    /** Backwards-compatible alias for the frozen route-code snapshot. */
    public String route() {
      return routeCode;
    }
  }
}
