package com.fluxpay.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configurable source-currency fee rates for wallet conversions. */
@Component
@ConfigurationProperties(prefix = "fluxpay.wallet.conversion")
public class ConversionFeeSchedule {
  private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.005");

  private Map<String, BigDecimal> feeRates = Map.of();

  public BigDecimal rateFor(String sourceCurrency) {
    BigDecimal configured = feeRates.get(normalize(sourceCurrency));
    return configured == null ? DEFAULT_RATE : configured;
  }

  public Map<String, BigDecimal> getFeeRates() {
    return feeRates;
  }

  public void setFeeRates(Map<String, BigDecimal> feeRates) {
    Map<String, BigDecimal> validated = new HashMap<>();
    if (feeRates != null) {
      feeRates.forEach(
          (currency, rate) -> {
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
              throw new IllegalArgumentException(
                  "Conversion fee rate must be at least zero and less than one");
            }
            validated.put(normalize(currency), rate);
          });
    }
    this.feeRates = Map.copyOf(validated);
  }

  private static String normalize(String currency) {
    return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
  }
}
