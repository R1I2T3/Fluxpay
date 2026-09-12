package com.fluxpay.dto;

public record FxQuoteResponse(
    String from, String to, String rate, String fetchedAt, boolean stale, boolean mock) {
  public static FxQuoteResponse from(FxSnapshot snapshot) {
    return new FxQuoteResponse(
        snapshot.from(),
        snapshot.to(),
        snapshot.rate().toPlainString(),
        snapshot.fetchedAt().toString(),
        snapshot.stale(),
        snapshot.mock());
  }
}
