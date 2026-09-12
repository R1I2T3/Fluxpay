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
      String route,
      String marketRate,
      String offeredRate,
      String feeAmount,
      String recipientAmount,
      int estimatedMinutes,
      boolean recommended) {}
}
