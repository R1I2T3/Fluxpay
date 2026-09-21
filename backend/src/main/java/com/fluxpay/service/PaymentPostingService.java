package com.fluxpay.service;

import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.contracts.WalletPort;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.exception.InsufficientWalletFundsException;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentPostingService implements PostingPort {
  private final WalletPort wallets;
  private final LedgerJournalService journals;
  private final Clock clock;

  public PaymentPostingService(WalletPort wallets, LedgerJournalService journals, Clock clock) {
    this.wallets = wallets;
    this.journals = journals;
    this.clock = clock;
  }

  @Override
  @Transactional
  public PostingAccounts postApprovedPayment(
      UUID paymentId,
      UUID userId,
      UUID walletId,
      String currency,
      BigDecimal gross,
      BigDecimal fee,
      Instant approvalExpiry) {
    String reference = "payment:" + paymentId;
    journals.lockReference(reference);
    PostingAccounts accounts = wallets.lockPostingAccounts(userId, walletId, currency, gross);
    if (!Instant.now(clock).isBefore(approvalExpiry))
      throw new BusinessException(
          HttpStatus.GONE, "QUOTE_EXPIRED", "The payment approval has expired.");
    BigDecimal net = gross.subtract(fee);
    var lines = new ArrayList<LedgerJournalLine>();
    lines.add(
        new LedgerJournalLine(
            accounts.customerWalletId(),
            "DEBIT",
            gross,
            currency,
            reference + ":customer",
            "Payment gross debit"));
    lines.add(
        new LedgerJournalLine(
            accounts.clearingWalletId(),
            "CREDIT",
            net,
            currency,
            reference + ":clearing",
            "Payment net clearing"));
    if (fee.signum() != 0)
      lines.add(
          new LedgerJournalLine(
              accounts.feeWalletId(), "CREDIT", fee, currency, reference + ":fee", "Payment fee"));
    try {
      journals.post(reference, lines);
    } catch (InsufficientWalletFundsException insufficient) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY, "WALLET_UNAVAILABLE", "Wallet is not eligible.");
    }
    return accounts;
  }
}
