package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class M2MockFxRateProviderTest {
  private static final Instant NOW = Instant.parse("2026-09-11T01:02:03Z");

  @ParameterizedTest
  @MethodSource("pairs")
  void derivesARealRateForEverySupportedDirectedPair(String from, String to, BigDecimal expected) {
    M2MockFxRateProvider provider = new M2MockFxRateProvider(Clock.fixed(NOW, ZoneOffset.UTC));

    var snapshot = provider.fetch(from, to);

    assertEquals(0, expected.compareTo(snapshot.rate()));
    assertEquals(NOW, snapshot.fetchedAt());
    assertTrue(snapshot.mock());
  }

  private static Stream<Arguments> pairs() {
    BigDecimal usdInr = new BigDecimal("83.50");
    BigDecimal usdEur = new BigDecimal("0.92");
    return Stream.of(
        Arguments.of("USD", "INR", usdInr),
        Arguments.of("USD", "EUR", usdEur),
        Arguments.of("INR", "USD", BigDecimal.ONE.divide(usdInr, MathContext.DECIMAL128)),
        Arguments.of("EUR", "USD", BigDecimal.ONE.divide(usdEur, MathContext.DECIMAL128)),
        Arguments.of("EUR", "INR", usdInr.divide(usdEur, MathContext.DECIMAL128)),
        Arguments.of("INR", "EUR", usdEur.divide(usdInr, MathContext.DECIMAL128)));
  }
}
