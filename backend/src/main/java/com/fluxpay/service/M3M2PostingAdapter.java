package com.fluxpay.service;

import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.M3PostingAccounts;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;

/** M3 payment posting through M2's balanced journal, in the caller's payment transaction. */
public class M3M2PostingAdapter implements M3PostingPort {
  private final M3WalletPort wallets;
  private final LedgerJournalService journal;
  private final Clock clock;
  public M3M2PostingAdapter(M3WalletPort wallets,LedgerJournalService journal,Clock clock) {
    this.wallets=wallets; this.journal=journal; this.clock=clock;
  }
  @Override public M3PostingAccounts postApprovedPayment(UUID paymentId,UUID userId,UUID walletId,
      String currency,BigDecimal gross,BigDecimal fee,Instant quoteExpiry) {
    if (gross == null || fee == null || gross.signum() <= 0 || fee.signum() < 0 || gross.compareTo(fee) <= 0)
      throw new M3BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_AMOUNT","Gross must exceed a non-negative fee");
    M3PostingAccounts accounts=wallets.lockPostingAccounts(userId,walletId,currency,gross);
    if (quoteExpiry == null || !clock.instant().isBefore(quoteExpiry))
      throw new M3BusinessException(HttpStatus.GONE,"QUOTE_EXPIRED","The selected quote has expired.");
    String key="m3:"+paymentId;
    List<LedgerJournalLine> lines=new ArrayList<>();
    lines.add(new LedgerJournalLine(accounts.customerWalletId(),"DEBIT",gross,currency,key+":customer","Payment source debit"));
    lines.add(new LedgerJournalLine(accounts.clearingWalletId(),"CREDIT",gross.subtract(fee),currency,key+":clearing","Payment payout clearing"));
    if (fee.signum()>0) lines.add(new LedgerJournalLine(accounts.feeWalletId(),"CREDIT",fee,currency,key+":fee","Payment fee"));
    journal.post(key,lines);
    return accounts;
  }
}
