package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fluxpay.common.contracts.FxRateProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "fluxpay.fx-mode=mock")
class FxQuoteServicePrimaryTest {
  @Autowired FxRateProvider fx;

  @Test
  void mockModeResolvesThroughFxQuoteService() {
    assertTrue(fx instanceof FxQuoteService);
    BigDecimal rate = fx.rate("USD", "INR");
    assertEquals(0, rate.compareTo(new BigDecimal("83.50")));
  }
}
