package com.fluxpay.domain;

import com.fluxpay.config.ConversionFeeSchedule;
import com.fluxpay.service.CurrencyScaleService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Money calculations shared by the wallet conversion services. */
@Component
public class ConversionMath {
  // Oracle NUMBER(19,4) permits 15 digits before the decimal point and 4 after it.
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999.9999");
  private static final int STORAGE_SCALE = 4;
  private static final int RATE_SCALE = 8;

  private final ConversionFeeSchedule fees;
  private final CurrencyScaleService currencies;

  public ConversionMath(ConversionFeeSchedule fees, CurrencyScaleService currencies) {
    this.fees = fees;
    this.currencies = currencies;
  }

  public ConversionCalculation calculate(
      String sourceCurrency, String targetCurrency, BigDecimal sourceAmount, BigDecimal rate) {
    int sourceScale = currencies.scale(sourceCurrency);
    int targetScale = currencies.scale(targetCurrency);
    validateSourceAmount(sourceAmount, sourceScale);
    BigDecimal acceptedRate = acceptedRate(rate);
    BigDecimal businessGross = sourceAmount.setScale(sourceScale, RoundingMode.UNNECESSARY);
    BigDecimal fee =
        businessGross
            .multiply(fees.rateFor(sourceCurrency))
            .setScale(sourceScale, RoundingMode.HALF_UP);
    BigDecimal net = businessGross.subtract(fee).setScale(sourceScale, RoundingMode.HALF_UP);
    if (net.signum() <= 0) {
      throw new IllegalArgumentException(
          "Amount remaining after the fee must be greater than zero");
    }
    BigDecimal credit = net.multiply(acceptedRate).setScale(targetScale, RoundingMode.HALF_UP);
    if (credit.signum() <= 0) {
      throw new IllegalArgumentException("Converted amount is below target currency precision");
    }
    validateStorageLimit(credit, "Converted amount");
    return new ConversionCalculation(
        storage(businessGross), storage(fee), storage(net), storage(credit), acceptedRate);
  }

  /** Smallest source minor-unit gross whose post-fee net funds the requested target. */
  public ConversionCalculation calculateTarget(
      String from, String to, BigDecimal target, BigDecimal rate) {
    validateSourceAmount(target, currencies.scale(to));
    int scale = currencies.scale(from);
    BigDecimal accepted = acceptedRate(rate);
    BigDecimal requiredNet = target.divide(accepted, scale, RoundingMode.CEILING);
    java.math.BigInteger low = requiredNet.movePointRight(scale).toBigIntegerExact();
    java.math.BigInteger high =
        MAX_MONEY.setScale(scale, RoundingMode.DOWN).movePointRight(scale).toBigIntegerExact();
    BigDecimal feeRate = fees.rateFor(from);
    while (low.compareTo(high) < 0) {
      java.math.BigInteger middle = low.add(high).shiftRight(1);
      BigDecimal gross = new BigDecimal(middle, scale);
      BigDecimal net =
          gross.subtract(gross.multiply(feeRate).setScale(scale, RoundingMode.HALF_UP));
      if (net.compareTo(requiredNet) >= 0) high = middle;
      else low = middle.add(java.math.BigInteger.ONE);
    }
    ConversionCalculation calculation = calculate(from, to, new BigDecimal(low, scale), accepted);
    if (calculation.net().compareTo(requiredNet) < 0 || calculation.credit().compareTo(target) < 0)
      throw new IllegalArgumentException("Target amount cannot be funded within the money limit");
    return calculation;
  }

  private static void validateSourceAmount(BigDecimal sourceAmount, int currencyScale) {
    if (sourceAmount == null || sourceAmount.signum() <= 0) {
      throw new IllegalArgumentException("Source amount must be present and greater than zero");
    }
    if (sourceAmount.stripTrailingZeros().scale() > currencyScale) {
      throw new IllegalArgumentException(
          "Source amount exceeds " + currencyScale + " decimal places for the currency");
    }
    validateStorageLimit(sourceAmount, "Source amount");
  }

  private static BigDecimal acceptedRate(BigDecimal rate) {
    if (rate == null || rate.signum() <= 0) {
      throw new IllegalArgumentException("Exchange rate must be present and greater than zero");
    }
    BigDecimal accepted = rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    if (accepted.signum() <= 0 || accepted.precision() > 19) {
      throw new IllegalArgumentException("Exchange rate cannot be persisted as NUMBER(19,8)");
    }
    return accepted;
  }

  private static void validateStorageLimit(BigDecimal amount, String label) {
    if (amount.compareTo(MAX_MONEY) > 0 || amount.setScale(STORAGE_SCALE).precision() > 19) {
      throw new IllegalArgumentException(label + " exceeds the NUMBER(19,4) money limit");
    }
  }

  private static BigDecimal storage(BigDecimal amount) {
    return amount.setScale(STORAGE_SCALE, RoundingMode.UNNECESSARY);
  }
}
