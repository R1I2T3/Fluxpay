package com.fluxpay.dto;

import com.fluxpay.beans.Payment;
import java.math.BigDecimal;
import java.util.UUID;

/** Immutable facts captured before screening and compared again under the payment lock. */
public record M3PaymentFacts(UUID paymentId, UUID senderId, UUID walletId, UUID recipientId,
    BigDecimal amount, String sourceCurrency, String payoutCurrency, String purpose,
    String recipientSnapshot, long recipientVersion) {
  public static M3PaymentFacts from(Payment p) {
    return new M3PaymentFacts(p.id(),p.senderId(),p.sourceWalletId(),p.recipientId(),
        p.sourceAmount().stripTrailingZeros(),p.sourceCurrency(),p.payoutCurrency(),
        p.purpose()==null?null:p.purpose().name(),p.recipientSnapshot(),p.recipientVersion());
  }
}
