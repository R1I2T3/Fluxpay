package com.fluxpay.adapter.persistence;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.WalletPort;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.dto.WalletSnapshot;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.WalletRepository;
import com.fluxpay.service.SystemAccountService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentWalletAdapter implements WalletPort {
  private final WalletRepository wallets;
  private final SystemAccountService systemAccounts;

  public PersistentWalletAdapter(WalletRepository wallets, SystemAccountService systemAccounts) {
    this.wallets = Objects.requireNonNull(wallets, "wallets must not be null");
    this.systemAccounts = Objects.requireNonNull(systemAccounts);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<WalletSnapshot> findOwned(UUID userId, UUID walletId) {
    Wallet wallet =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(userId, "USD", WalletAccountRole.CUSTOMER)
            .filter(w -> w.getId().equals(walletId))
            .orElse(null);
    if (wallet == null) {
      // Fall back to direct lookup to support EUR/INR without extra queries in tests;
      // ownership + CUSTOMER role still enforced below.
      wallet = wallets.findById(walletId).orElse(null);
    }
    if (wallet == null
        || !wallet.getUserId().equals(userId)
        || wallet.getAccountRole() != WalletAccountRole.CUSTOMER) {
      return Optional.empty();
    }
    return Optional.of(
        new WalletSnapshot(
            wallet.getId(),
            wallet.getUserId(),
            wallet.getCurrency(),
            wallet.getAvailableBalance(),
            true));
  }

  @Override
  @Transactional
  public PostingAccounts lockPostingAccounts(
      UUID userId, UUID walletId, String currency, BigDecimal gross) {
    Wallet customer =
        wallets.findById(walletId).filter(w -> w.getUserId().equals(userId)).orElse(null);
    if (customer == null
        || customer.getAccountRole() != WalletAccountRole.CUSTOMER
        || !customer.getCurrency().equals(currency)) {
      throw new BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY, "WALLET_UNAVAILABLE", "Wallet is not eligible.");
    }
    Wallet clearing = systemAccounts.require(currency, WalletAccountRole.PAYOUT_CLEARING);
    Wallet fee = systemAccounts.require(currency, WalletAccountRole.FEE_REVENUE);
    List<Wallet> ordered = new ArrayList<>(List.of(customer, clearing, fee));
    ordered.sort(Comparator.comparing(w -> w.getId().toString()));
    for (Wallet w : ordered) {
      wallets
          .findByIdForUpdate(w.getId())
          .orElseThrow(() -> new IllegalStateException("Posting wallet disappeared"));
    }
    // The writer checks available funds only for a new debit, after replay detection.
    return new PostingAccounts(customer.getId(), clearing.getId(), fee.getId());
  }
}
