package com.fluxpay.common.contracts;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Immutable facts required to screen one payment and its selected recipient. */
public record ComplianceScreeningInput(
    UUID userId, String recipientName, BigDecimal amount, String currency) {
  public ComplianceScreeningInput {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    if (currency.isBlank()) throw new IllegalArgumentException("currency must not be blank");
    recipientName = recipientName == null ? "" : recipientName;
  }
}
