package com.fluxpay.service;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.M3PostingAccounts;
import com.fluxpay.dto.M3WalletSnapshot;
import com.fluxpay.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Opt-in M3 glue over M2 accounts; never creates accounts or invents spendable funds. */
public class M3M2WalletAdapter implements M3WalletPort {
  private final WalletRepository wallets;
  private final EntityManager entities;
  private final UUID systemOwner;

  public M3M2WalletAdapter(WalletRepository wallets, EntityManager entities, UUID systemOwner) {
    this.wallets = Objects.requireNonNull(wallets);
    this.entities = Objects.requireNonNull(entities);
    this.systemOwner = Objects.requireNonNull(systemOwner, "m5.integration.system-user-id is required");
  }

  @Override public Optional<M3WalletSnapshot> findOwned(UUID userId, UUID walletId) {
    return wallets.findById(walletId).filter(w -> userId.equals(w.getUserId())
        && w.getAccountRole() == WalletAccountRole.CUSTOMER)
        .map(w -> new M3WalletSnapshot(w.getId(),w.getUserId(),w.getCurrency(),w.getAvailableBalance(),true));
  }

  @Override public M3PostingAccounts lockPostingAccounts(UUID userId, UUID walletId,
      String currency, BigDecimal gross) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Posting account locks require the payment transaction");
    if (gross == null || gross.signum() <= 0) throw failure("INVALID_AMOUNT", "Amount must be positive");
    Wallet customer = wallets.findById(walletId).orElseThrow(() -> failure("WALLET_NOT_FOUND", "Wallet not found"));
    Wallet clearing = internal(currency,WalletAccountRole.PAYOUT_CLEARING);
    Wallet fee = internal(currency,WalletAccountRole.FEE_REVENUE);
    Set<UUID> ids = Set.of(customer.getId(),clearing.getId(),fee.getId());
    // Repository query orders RAW IDs, the same order used by M2's journal writer.
    List<Wallet> locked = wallets.findAllByIdForUpdate(ids);
    if (locked.size() != 3) throw failure("POSTING_ACCOUNTS_UNAVAILABLE", "Posting accounts are unavailable");
    Map<UUID,Wallet> fresh = new HashMap<>();
    for (Wallet wallet : locked) {
      entities.refresh(wallet); // Avoid values cached before the pessimistic lock was acquired.
      fresh.put(wallet.getId(),wallet);
    }
    requireAccount(fresh.get(walletId),userId,currency,WalletAccountRole.CUSTOMER);
    requireAccount(fresh.get(clearing.getId()),systemOwner,currency,WalletAccountRole.PAYOUT_CLEARING);
    requireAccount(fresh.get(fee.getId()),systemOwner,currency,WalletAccountRole.FEE_REVENUE);
    if (fresh.get(walletId).getAvailableBalance().compareTo(gross) < 0)
      throw failure("INSUFFICIENT_FUNDS", "Available funds are insufficient");
    return new M3PostingAccounts(walletId,clearing.getId(),fee.getId());
  }

  private Wallet internal(String currency,WalletAccountRole role) {
    return wallets.findByUserIdAndCurrencyAndAccountRole(systemOwner,currency,role)
        .orElseThrow(() -> failure("POSTING_ACCOUNTS_UNAVAILABLE", "Provision the configured system posting accounts before confirming payments"));
  }
  private void requireAccount(Wallet wallet,UUID owner,String currency,WalletAccountRole role) {
    if (wallet == null || !owner.equals(wallet.getUserId()) || !currency.equals(wallet.getCurrency())
        || wallet.getAccountRole() != role)
      throw failure("WALLET_UNAVAILABLE", "Posting account owner, currency or role does not match");
  }
  private M3BusinessException failure(String code,String message) {
    return new M3BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,code,message);
  }
}
