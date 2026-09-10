package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class M2ConversionMathTest {
  private final M2ConversionMath calculator = new M2ConversionMath();

  // Each row runs as a separate test. Equality also checks the four-decimal scale.
  @ParameterizedTest
  @CsvSource({
    "100.0000, 0.5000",
    "100, 0.5000",
    "1.0100, 0.0050",
    "1.0300, 0.0052",
    "0.0001, 0.0000",
    "999999999999999.9999, 5000000000000.0000"
  })
  void calculatesFeeWithFourDecimalHalfEvenRounding(String source, String expected) {
    assertEquals(new BigDecimal(expected), calculator.fee(new BigDecimal(source)));
  }

  @ParameterizedTest
  @CsvSource({
    "100.0000, 83.50, 8308.2500",
    "0.0001, 2.5, 0.0002",
    "0.0001, 3.5, 0.0004",
    "100.0000, 1.23456789, 122.8395",
    "0.0001, 9999999999999999999, 999999999999999.9999"
  })
  void convertsNetAmountWithoutPrematurelyRoundingRate(
      String source, String rate, String expected) {
    BigDecimal actual = calculator.convertedAmount(new BigDecimal(source), new BigDecimal(rate));
    assertEquals(new BigDecimal(expected), actual);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-0.0001", "1.00001", "1.00000", "1000000000000000"})
  void rejectsInvalidSourceAmountWhenCalculatingFee(String source) {
    BigDecimal amount = source == null ? null : new BigDecimal(source);
    assertThrows(IllegalArgumentException.class, () -> calculator.fee(amount));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-0.0001", "1.00001", "1.00000", "1000000000000000"})
  void rejectsInvalidSourceAmountWhenConverting(String source) {
    BigDecimal amount = source == null ? null : new BigDecimal(source);
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.convertedAmount(amount, new BigDecimal("83.50")));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-0.01"})
  void rejectsMissingOrNonpositiveRate(String rateText) {
    BigDecimal rate = rateText == null ? null : new BigDecimal(rateText);
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.convertedAmount(new BigDecimal("100.0000"), rate));
  }

  @Test
  void rejectsConversionThatRoundsBeyondOracleMoneyLimit() {
    // The exact product ends in .99995, which rounds beyond NUMBER(19,4).
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.convertedAmount(
            new BigDecimal("0.0001"), new BigDecimal("9999999999999999999.5")));
  }

  @Test
  void rejectsConversionThatRoundsToZero() {
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.convertedAmount(new BigDecimal("0.0001"), new BigDecimal("0.1")));
  }
}
