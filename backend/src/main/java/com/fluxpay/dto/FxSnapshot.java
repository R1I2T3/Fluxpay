package com.fluxpay.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FxSnapshot(
    String from, String to, BigDecimal rate, Instant fetchedAt, boolean stale, boolean mock) {
  public FxSnapshot asStale() {
    return new FxSnapshot(from, to, rate, fetchedAt, true, mock);
  }

  public FxSnapshot asFresh() {
    return new FxSnapshot(from, to, rate, fetchedAt, false, mock);
  }
}
