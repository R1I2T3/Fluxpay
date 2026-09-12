package com.fluxpay.dto;

import java.math.BigDecimal;
import java.util.Objects;

public record PayoutResult(
    boolean success,
    String providerRef,
    String errorCode,
    String errorMessage,
    BigDecimal providerFee) {
  public PayoutResult {
    Objects.requireNonNull(providerFee, "providerFee must not be null");
    if (success) {
      if (providerRef == null || providerRef.isBlank()) {
        throw new IllegalArgumentException("providerRef must be present on success");
      }
      if (errorCode != null) {
        throw new IllegalArgumentException("errorCode must be absent on success");
      }
      if (errorMessage != null) {
        throw new IllegalArgumentException("errorMessage must be absent on success");
      }
    } else {
      if (providerRef != null) {
        throw new IllegalArgumentException("providerRef must be absent on failure");
      }
      if (errorCode == null || errorCode.isBlank()) {
        throw new IllegalArgumentException("errorCode must be present on failure");
      }
      if (errorMessage == null || errorMessage.isBlank()) {
        throw new IllegalArgumentException("errorMessage must be present on failure");
      }
    }
  }

  public static PayoutResult ok(String providerRef, BigDecimal providerFee) {
    return new PayoutResult(true, providerRef, null, null, providerFee);
  }

  public static PayoutResult failed(String errorCode, String errorMessage, BigDecimal providerFee) {
    return new PayoutResult(false, null, errorCode, errorMessage, providerFee);
  }
}
