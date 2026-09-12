package com.fluxpay.dto;

import java.math.BigDecimal;

public record PayoutCmd(
    String paymentId,
    BigDecimal amount,
    String sourceCurrency,
    String targetCurrency,
    String routeCode,
    BigDecimal routeBaseFee,
    int attemptNumber) {
  public PayoutCmd {
    if (paymentId == null || paymentId.isBlank()) {
      throw new IllegalArgumentException("paymentId must not be blank");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (sourceCurrency == null || sourceCurrency.isBlank()) {
      throw new IllegalArgumentException("sourceCurrency must not be blank");
    }
    if (targetCurrency == null || targetCurrency.isBlank()) {
      throw new IllegalArgumentException("targetCurrency must not be blank");
    }
    if (routeCode == null || routeCode.isBlank()) {
      throw new IllegalArgumentException("routeCode must not be blank");
    }
    if (routeBaseFee == null || routeBaseFee.signum() < 0) {
      throw new IllegalArgumentException("routeBaseFee must not be negative");
    }
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be at least 1");
    }
  }
}
