package com.fluxpay.service;

import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.M3PostingAccounts;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentPostingService implements M3PostingPort {
  private final M3WalletPort wallets; private final LedgerWriter ledger; private final Clock clock;
  public PaymentPostingService(M3WalletPort wallets, LedgerWriter ledger, Clock clock) { this.wallets = wallets; this.ledger = ledger; this.clock = clock; }
  @Override public void postApprovedPayment(UUID paymentId, UUID userId, UUID walletId, String currency, BigDecimal gross, BigDecimal fee, Instant quoteExpiry) {
    M3PostingAccounts accounts = wallets.lockPostingAccounts(userId, walletId, currency, gross);
    if (!Instant.now(clock).isBefore(quoteExpiry)) throw new M3BusinessException(HttpStatus.GONE, "QUOTE_EXPIRED", "The selected quote has expired.");
    BigDecimal net = gross.subtract(fee);
    ledger.append(accounts.customerWalletId(), "DEBIT", gross, currency, "m3:" + paymentId + ":customer");
    ledger.append(accounts.clearingWalletId(), "CREDIT", net, currency, "m3:" + paymentId + ":clearing");
    ledger.append(accounts.feeWalletId(), "CREDIT", fee, currency, "m3:" + paymentId + ":fee");
  }
}
