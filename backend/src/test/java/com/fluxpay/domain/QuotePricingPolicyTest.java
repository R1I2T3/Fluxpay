package com.fluxpay.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuotePricingPolicyTest {
  private final QuotePricingPolicy policy = new QuotePricingPolicy();

  @Test
  void sourceFeeIsDeductedBeforeConversion() {
    var result =
        policy.price(
            new BigDecimal("100.0000"),
            new BigDecimal("5.0000"),
            BigDecimal.ZERO,
            new BigDecimal("80.000000"));
    assertThat(result.netSourceAmount()).isEqualTo(new BigDecimal("95.0000"));
    assertThat(result.offeredRate()).isEqualTo(new BigDecimal("80.000000"));
    assertThat(result.recipientAmount()).isEqualTo(new BigDecimal("7600.0000"));
  }

  @ParameterizedTest
  @CsvSource({
    "1.00005,1,1.0000,1.000000,1.0000",
    "1.00015,1,1.0002,1.000000,1.0002",
    "1,1.0000005,1.0000,1.000000,1.0000",
    "1,1.0000015,1.0000,1.000002,1.0000",
    "1,1.00005,1.0000,1.000050,1.0000",
    "1,1.00015,1.0000,1.000150,1.0002"
  })
  void roundsMoneyAndRatesHalfEven(
      String gross, String rate, String net, String offered, String recipient) {
    var result =
        policy.price(new BigDecimal(gross), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(rate));
    assertThat(result.netSourceAmount()).isEqualTo(new BigDecimal(net));
    assertThat(result.offeredRate()).isEqualTo(new BigDecimal(offered));
    assertThat(result.recipientAmount()).isEqualTo(new BigDecimal(recipient));
  }

  @ParameterizedTest
  @CsvSource({
    "5,5,0,80",
    "4,5,0,80",
    "1,0,0,0.00005",
    "100,-1,0,80",
    "100,5,-1,80",
    "100,5,100,80",
    "100,5,0,0"
  })
  void rejectsInvalidOrNonpositiveEconomics(String gross, String fee, String spread, String rate) {
    assertThatThrownBy(
            () ->
                policy.price(
                    new BigDecimal(gross),
                    new BigDecimal(fee),
                    new BigDecimal(spread),
                    new BigDecimal(rate)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
