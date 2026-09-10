package com.fluxpay.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money calculations shared by the M2 wallet conversion services. */
public class M2ConversionMath {
  private static final BigDecimal FEE_RATE = new BigDecimal("0.005");
  private static final int MONEY_SCALE = 4;

  // Oracle NUMBER(19,4) permits 15 digits before the decimal point and 4 after it.
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999.9999");

  /** Calculates the 0.5% source-currency fee. Very small amounts can have a zero fee. */
  public BigDecimal fee(BigDecimal sourceAmount) {
    validateSourceAmount(sourceAmount);
    return sourceAmount.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
  }

  /** Deducts the fee before applying the rate; rounds only the resulting target amount. */
  public BigDecimal convertedAmount(BigDecimal sourceAmount, BigDecimal rate) {
    BigDecimal fee = fee(sourceAmount);
    if (rate == null || rate.signum() <= 0) {
      throw new IllegalArgumentException("Exchange rate must be present and greater than zero");
    }

    BigDecimal amountAfterFee = sourceAmount.subtract(fee);
    if (amountAfterFee.signum() <= 0) {
      throw new IllegalArgumentException("Amount remaining after the fee must be greater than zero");
    }

    // Rates may have more than four decimal places; do not round the rate first.
    BigDecimal convertedAmount =
        amountAfterFee.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    if (convertedAmount.signum() <= 0) {
      throw new IllegalArgumentException("Converted amount must be at least 0.0001");
    }
    if (convertedAmount.compareTo(MAX_MONEY) > 0) {
      throw new IllegalArgumentException("Converted amount exceeds the NUMBER(19,4) money limit");
    }
    return convertedAmount;
  }

  private void validateSourceAmount(BigDecimal sourceAmount) {
    if (sourceAmount == null || sourceAmount.signum() <= 0) {
      throw new IllegalArgumentException("Source amount must be present and greater than zero");
    }
    // Reject excess input decimal places, including trailing zeros, rather than round requests.
    if (sourceAmount.scale() > MONEY_SCALE) {
      throw new IllegalArgumentException("Source amount must have no more than four decimal places");
    }
    if (sourceAmount.compareTo(MAX_MONEY) > 0) {
      throw new IllegalArgumentException("Source amount exceeds the NUMBER(19,4) money limit");
    }
  }
}
