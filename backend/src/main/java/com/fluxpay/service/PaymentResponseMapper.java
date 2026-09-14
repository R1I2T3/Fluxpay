package com.fluxpay.service;

import com.fluxpay.beans.Payment;
import com.fluxpay.dto.PaymentResponse;

public final class PaymentResponseMapper {
  private PaymentResponseMapper() {}

  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.id(),
        payment.sourceWalletId(),
        payment.recipientId(),
        payment.sourceAmount().toPlainString(),
        payment.sourceCurrency(),
        payment.payoutCurrency(),
        payment.status(),
        payment.selectedQuoteId(),
        payment.createdAt());
  }
}
