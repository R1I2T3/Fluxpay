package com.fluxpay.service;

import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PaymentPostingSnapshot;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record PaymentSnapshot(
    String paymentId,
    UUID senderUserId,
    UUID senderWalletId,
    UUID payoutClearingWalletId,
    BigDecimal amount,
    String sourceCurrency,
    String targetCurrency,
    PaymentStatus status,
    PaymentPostingSnapshot posting) {
  public PaymentSnapshot(
      String paymentId,
      UUID senderUserId,
      UUID senderWalletId,
      UUID payoutClearingWalletId,
      BigDecimal amount,
      String sourceCurrency,
      String targetCurrency,
      PaymentStatus status) {
    this(
        paymentId,
        senderUserId,
        senderWalletId,
        payoutClearingWalletId,
        amount,
        sourceCurrency,
        targetCurrency,
        status,
        null);
  }

  public PaymentSnapshot {
    if (paymentId == null || paymentId.isBlank()) {
      throw new IllegalArgumentException("paymentId must not be blank");
    }
    Objects.requireNonNull(senderUserId, "senderUserId must not be null");
    Objects.requireNonNull(senderWalletId, "senderWalletId must not be null");
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (sourceCurrency == null || sourceCurrency.isBlank()) {
      throw new IllegalArgumentException("sourceCurrency must not be blank");
    }
    if (targetCurrency == null || targetCurrency.isBlank()) {
      throw new IllegalArgumentException("targetCurrency must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
    if (posting != null
        && (!posting.customerWalletId().equals(senderWalletId)
            || !posting.clearingWalletId().equals(payoutClearingWalletId)
            || !posting.currency().equals(sourceCurrency)
            || posting.gross().compareTo(amount) != 0
            || !posting.originalJournalReference().equals("payment:" + paymentId))) {
      throw new IllegalArgumentException(
          "Original posting does not match this payment's source funding");
    }
  }
}
