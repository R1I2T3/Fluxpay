package com.fluxpay.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.CurrencyConfiguration;
import com.fluxpay.config.ConversionFeeSchedule;
import com.fluxpay.repository.CurrencyConfigurationRepository;
import com.fluxpay.service.CurrencyScaleService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConversionMathTest {
  private ConversionFeeSchedule fees;
  private ConversionMath calculator;

  @BeforeEach
  void setUp() {
    CurrencyConfigurationRepository currencies = mock(CurrencyConfigurationRepository.class);
    CurrencyConfiguration twoDecimals = mock(CurrencyConfiguration.class);
    when(twoDecimals.getScale()).thenReturn(2);
    when(currencies.findById("USD")).thenReturn(Optional.of(twoDecimals));
    when(currencies.findById("EUR")).thenReturn(Optional.of(twoDecimals));
    when(currencies.findById("INR")).thenReturn(Optional.of(twoDecimals));
    fees = new ConversionFeeSchedule();
    calculator = new ConversionMath(fees, new CurrencyScaleService(currencies));
  }

  @Test
  void calculatesOneDefaultFeeNetAndCreditResultWithCurrencyHalfUpRounding() {
    ConversionCalculation result =
        calculator.calculate("USD", "INR", new BigDecimal("101.00"), new BigDecimal("1.234567894"));

    assertEquals(new BigDecimal("101.0000"), result.gross());
    assertEquals(new BigDecimal("0.5100"), result.fee());
    assertEquals(new BigDecimal("100.4900"), result.net());
    assertEquals(new BigDecimal("124.0600"), result.credit());
    assertEquals(new BigDecimal("1.23456789"), result.rate());
  }

  @Test
  void usesConfiguredFeeForTheSourceCurrencyOnly() {
    fees.setFeeRates(Map.of("USD", new BigDecimal("0.01")));

    ConversionCalculation usd =
        calculator.calculate("USD", "INR", new BigDecimal("100"), BigDecimal.ONE);
    ConversionCalculation eur =
        calculator.calculate("EUR", "INR", new BigDecimal("100"), BigDecimal.ONE);

    assertEquals(new BigDecimal("1.0000"), usd.fee());
    assertEquals(new BigDecimal("0.5000"), eur.fee());
  }

  @Test
  void acceptsInsignificantTrailingZerosWithinCurrencyPrecision() {
    ConversionCalculation result =
        calculator.calculate("USD", "EUR", new BigDecimal("1.2300"), BigDecimal.ONE);

    assertEquals(new BigDecimal("1.2300"), result.gross());
  }

  @Test
  void rejectsExcessFractionalSourceValueInsteadOfRoundingIt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.calculate("USD", "EUR", new BigDecimal("1.2340"), BigDecimal.ONE));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"0", "-0.01", "1000000000000000"})
  void rejectsMissingNonpositiveOrStorageOverflowSourceAmount(String source) {
    BigDecimal amount = source == null ? null : new BigDecimal(source);
    assertThrows(
        IllegalArgumentException.class,
        () -> calculator.calculate("USD", "EUR", amount, BigDecimal.ONE));
  }

  @Test
  void rejectsConvertedCreditBeyondStorageLimit() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            calculator.calculate(
                "USD",
                "EUR",
                new BigDecimal("999999999999999.99"),
                new BigDecimal("100.00000000")));
  }

  @Test
  void rejectsRateThatCannotBePersistedAfterCanonicalRounding() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            calculator.calculate(
                "USD", "EUR", new BigDecimal("1.00"), new BigDecimal("999999999999.99999999")));
  }

  @Test
  void rejectsCreditThatRoundsToZeroAtTargetCurrencyPrecision() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            calculator.calculate(
                "USD", "EUR", new BigDecimal("0.01"), new BigDecimal("0.00000001")));
  }

  @Test
  void targetGrossUpReusesConfiguredSourceCurrencyFee() {
    fees.setFeeRates(Map.of("USD", new BigDecimal("0.01")));
    ConversionCalculation result =
        calculator.calculateTarget("USD", "INR", new BigDecimal("99"), BigDecimal.ONE);
    assertEquals(new BigDecimal("100.0000"), result.gross());
    assertEquals(new BigDecimal("1.0000"), result.fee());
    assertEquals(new BigDecimal("99.0000"), result.net());
  }

  @Test
  void targetGrossUpHandlesNearOneFeeAndRejectsUnfundableTarget() {
    fees.setFeeRates(Map.of("USD", new BigDecimal("0.999")));
    ConversionCalculation result =
        calculator.calculateTarget("USD", "EUR", new BigDecimal("0.01"), BigDecimal.ONE);
    assertEquals(new BigDecimal("5.0100"), result.gross());
    assertEquals(new BigDecimal("5.0000"), result.fee());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            calculator.calculateTarget(
                "USD", "EUR", new BigDecimal("999999999999999.99"), BigDecimal.ONE));
  }
}
