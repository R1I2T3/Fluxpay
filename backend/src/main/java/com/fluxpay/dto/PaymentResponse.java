package com.fluxpay.dto;

import com.fluxpay.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    UUID sourceWalletId,
    UUID recipientId,
    String sourceAmount,
    String sourceCurrency,
    String payoutCurrency,
    PaymentStatus status,
    UUID selectedQuoteId,
    Instant createdAt,
    com.fluxpay.beans.PaymentPurpose purpose,
    String purposeReason) {
  public PaymentResponse(
      UUID id,
      UUID sourceWalletId,
      UUID recipientId,
      String sourceAmount,
      String sourceCurrency,
      String payoutCurrency,
      PaymentStatus status,
      UUID selectedQuoteId,
      Instant createdAt) {
    this(
        id,
        sourceWalletId,
        recipientId,
        sourceAmount,
        sourceCurrency,
        payoutCurrency,
        status,
        selectedQuoteId,
        createdAt,
        null,
        null);
  }
}
