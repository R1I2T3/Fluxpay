package com.fluxpay.common.contracts;

import com.fluxpay.dto.PostingAccounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface PostingPort {
  PostingAccounts postApprovedPayment(
      UUID paymentId,
      UUID userId,
      UUID walletId,
      String currency,
      BigDecimal gross,
      BigDecimal fee,
      Instant approvalExpiry);
}
