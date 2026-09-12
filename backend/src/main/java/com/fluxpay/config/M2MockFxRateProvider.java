package com.fluxpay.config;

import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.service.FxSnapshotSource;
import com.fluxpay.service.FxUnavailableException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;

public class M2MockFxRateProvider implements FxSnapshotSource {
  private static final MathContext RATE_CONTEXT = MathContext.DECIMAL128;
  private static final BigDecimal USD_INR = new BigDecimal("83.50");
  private static final BigDecimal USD_EUR = new BigDecimal("0.92");

  private final Clock clock;

  public M2MockFxRateProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public FxSnapshot fetch(String from, String to) {
    return new FxSnapshot(from, to, rateFor(from, to), clock.instant(), false, true);
  }

  private static BigDecimal rateFor(String from, String to) {
    if ("USD".equals(from) && "INR".equals(to)) {
      return USD_INR;
    }
    if ("USD".equals(from) && "EUR".equals(to)) {
      return USD_EUR;
    }
    if ("INR".equals(from) && "USD".equals(to)) {
      return BigDecimal.ONE.divide(USD_INR, RATE_CONTEXT);
    }
    if ("EUR".equals(from) && "USD".equals(to)) {
      return BigDecimal.ONE.divide(USD_EUR, RATE_CONTEXT);
    }
    if ("EUR".equals(from) && "INR".equals(to)) {
      return USD_INR.divide(USD_EUR, RATE_CONTEXT);
    }
    if ("INR".equals(from) && "EUR".equals(to)) {
      return USD_EUR.divide(USD_INR, RATE_CONTEXT);
    }
    throw new FxUnavailableException("Mock FX pair is unsupported: " + from + "/" + to);
  }
}
