package com.fluxpay.service;

import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.contracts.WalletPort;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentPostingService implements PostingPort {
  private final WalletPort wallets;
  private final LedgerWriter ledger;
  private final Clock clock;

  public PaymentPostingService(WalletPort wallets, LedgerWriter ledger, Clock clock) {
    this.wallets = wallets;
    this.ledger = ledger;
    this.clock = clock;
  }

  @Override
  public com.fluxpay.dto.PostingAccounts postApprovedPayment(
      UUID paymentId,
      UUID userId,
      UUID walletId,
      String currency,
      BigDecimal gross,
      BigDecimal fee,
      Instant quoteExpiry) {
    PostingAccounts accounts = wallets.lockPostingAccounts(userId, walletId, currency, gross);
    if (!Instant.now(clock).isBefore(quoteExpiry))
      throw new BusinessException(
          HttpStatus.GONE, "QUOTE_EXPIRED", "The selected quote has expired.");
    BigDecimal net = gross.subtract(fee);
    ledger.append(
        accounts.customerWalletId(), "DEBIT", gross, currency, "m3:" + paymentId + ":customer");
    ledger.append(
        accounts.clearingWalletId(), "CREDIT", net, currency, "m3:" + paymentId + ":clearing");
    ledger.append(accounts.feeWalletId(), "CREDIT", fee, currency, "m3:" + paymentId + ":fee");
    return accounts;
  }
}
