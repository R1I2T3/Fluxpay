package com.fluxpay.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FxSnapshot(
    String from, String to, BigDecimal rate, Instant fetchedAt, boolean stale) {
  public FxSnapshot asStale() {
    return new FxSnapshot(from, to, rate, fetchedAt, true);
  }

  public FxSnapshot asFresh() {
    return new FxSnapshot(from, to, rate, fetchedAt, false);
  }
}
