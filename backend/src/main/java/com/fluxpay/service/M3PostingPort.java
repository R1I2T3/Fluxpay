package com.fluxpay.service;

import com.fluxpay.dto.M3PostingAccounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface M3PostingPort {
  M3PostingAccounts postApprovedPayment(
      UUID paymentId,
      UUID userId,
      UUID walletId,
      String currency,
      BigDecimal gross,
      BigDecimal fee,
      Instant quoteExpiry);
}
