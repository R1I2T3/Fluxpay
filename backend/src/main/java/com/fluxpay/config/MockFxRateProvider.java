package com.fluxpay.config;

import com.fluxpay.common.contracts.FxRateProvider;
import java.math.BigDecimal;

/** Fixed FX rate for mock-profile acceptance tests until the production provider is available. */
public class MockFxRateProvider implements FxRateProvider {

  private static final BigDecimal USD_TO_KES_RATE = new BigDecimal("148.0000");

  @Override
  public BigDecimal rate(String from, String to) {
    return USD_TO_KES_RATE;
  }
}
