package com.fluxpay.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Original source-side accounts and economics, independent of subsequent payout routing. */
public record PaymentPostingSnapshot(
    UUID customerWalletId,
    UUID clearingWalletId,
    UUID feeWalletId,
    String currency,
    BigDecimal gross,
    BigDecimal net,
    BigDecimal fee,
    String originalJournalReference) {
  public PaymentPostingSnapshot {
    if (customerWalletId == null
        || clearingWalletId == null
        || feeWalletId == null
        || customerWalletId.equals(clearingWalletId)
        || customerWalletId.equals(feeWalletId)
        || clearingWalletId.equals(feeWalletId)) {
      throw new IllegalArgumentException(
          "Original posting requires three distinct wallet identities");
    }
    if (currency == null || !Set.of("USD", "EUR", "INR").contains(currency)) {
      throw new IllegalArgumentException("Invalid original posting currency");
    }
    if (gross == null
        || net == null
        || fee == null
        || gross.signum() <= 0
        || net.signum() <= 0
        || fee.signum() < 0
        || gross.compareTo(net.add(fee)) != 0) {
      throw new IllegalArgumentException(
          "Original posting requires positive gross/net and gross = net + nonnegative fee");
    }
    for (BigDecimal amount : List.of(gross, net, fee)) {
      if (amount.scale() > 4 || amount.setScale(4).precision() > 19) {
        throw new IllegalArgumentException("Original posting amounts must fit NUMBER(19,4)");
      }
    }
    if (originalJournalReference == null
        || originalJournalReference.isBlank()
        || originalJournalReference.length() > 64) {
      throw new IllegalArgumentException(
          "Original journal reference is required and must fit 64 characters");
    }
  }
}
