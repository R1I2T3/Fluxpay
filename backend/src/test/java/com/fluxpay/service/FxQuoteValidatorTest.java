package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.exception.RequoteRequiredException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FxQuoteValidatorTest {
  private static final Instant NOW = Instant.parse("2026-09-18T06:30:00Z");
  private final FxQuoteValidator validator =
      new FxQuoteValidator(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void acceptsAndCanonicalizesAPersistableFreshSnapshot() {
    FxSnapshot accepted =
        validator.accept(
            new FxSnapshot(
                "USD",
                "INR",
                new BigDecimal("83.123456789"),
                NOW.minusSeconds(3599),
                false),
            "USD",
            "INR");

    assertEquals(new BigDecimal("83.12345679"), accepted.rate());
  }

  @Test
  void rejectsExplicitlyStaleSnapshot() {
    assertThrows(
        RequoteRequiredException.class,
        () ->
            validator.accept(
                new FxSnapshot("USD", "INR", BigDecimal.ONE, NOW.minusSeconds(1), true),
                "USD",
                "INR"));
  }

  @Test
  void rejectsSnapshotAtTheOneHourBoundaryAndFromTheFuture() {
    assertThrows(
        RequoteRequiredException.class,
        () ->
            validator.accept(
                new FxSnapshot("USD", "INR", BigDecimal.ONE, NOW.minusSeconds(3600), false),
                "USD",
                "INR"));
    assertThrows(
        RequoteRequiredException.class,
        () ->
            validator.accept(
                new FxSnapshot("USD", "INR", BigDecimal.ONE, NOW.plusSeconds(1), false),
                "USD",
                "INR"));
  }

  @Test
  void rejectsWrongPairAndRateThatRoundsToZero() {
    assertThrows(
        RequoteRequiredException.class,
        () ->
            validator.accept(
                new FxSnapshot("EUR", "INR", BigDecimal.ONE, NOW, false), "USD", "INR"));
    assertThrows(
        RequoteRequiredException.class,
        () ->
            validator.accept(
                new FxSnapshot("USD", "INR", new BigDecimal("0.000000001"), NOW, false),
                "USD",
                "INR"));
  }

  @Test
  void derivesTheSameStableUuidForTheSameAcceptedQuote() {
    FxSnapshot quote =
        new FxSnapshot("USD", "INR", new BigDecimal("83.50000000"), NOW, false);

    assertEquals(FxQuoteValidator.quoteId(quote), FxQuoteValidator.quoteId(quote));
    assertEquals(36, FxQuoteValidator.quoteId(quote).length());
  }
}
