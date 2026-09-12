package com.fluxpay.service;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.M3PostingAccounts;
import com.fluxpay.dto.M3WalletSnapshot;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentM3WalletAdapter implements M3WalletPort {
  private final WalletRepository wallets;
  private final Supplier<UUID> systemUserSupplier;

  public PersistentM3WalletAdapter(
      WalletRepository wallets,
      com.fluxpay.config.M2DemoFundingConfig demoConfig,
      com.fluxpay.config.M2FxConfig fxConfig) {
    this(wallets, () -> firstPresent(demoConfig.getSystemUserId(), fxConfig.getSystemUserId()));
  }

  PersistentM3WalletAdapter(WalletRepository wallets, Supplier<UUID> systemUserSupplier) {
    this.wallets = Objects.requireNonNull(wallets, "wallets must not be null");
    this.systemUserSupplier =
        Objects.requireNonNull(systemUserSupplier, "systemUserSupplier must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<M3WalletSnapshot> findOwned(UUID userId, UUID walletId) {
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
        new M3WalletSnapshot(
            wallet.getId(),
            wallet.getUserId(),
            wallet.getCurrency(),
            wallet.getAvailableBalance(),
            true));
  }

  @Override
  @Transactional
  public M3PostingAccounts lockPostingAccounts(
      UUID userId, UUID walletId, String currency, BigDecimal gross) {
    Wallet customer =
        wallets.findByIdForUpdate(walletId).filter(w -> w.getUserId().equals(userId)).orElse(null);
    if (customer == null
        || customer.getAccountRole() != WalletAccountRole.CUSTOMER
        || !customer.getCurrency().equals(currency)
        || customer.getAvailableBalance().compareTo(gross) < 0) {
      throw new M3BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY, "WALLET_UNAVAILABLE", "Wallet is not eligible.");
    }
    UUID systemUser = systemUserSupplier.get();
    Wallet clearing =
        findOrCreateSystemWallet(systemUser, currency, WalletAccountRole.PAYOUT_CLEARING);
    Wallet fee = findOrCreateSystemWallet(systemUser, currency, WalletAccountRole.FEE_REVENUE);
    List<Wallet> ordered = new ArrayList<>(List.of(customer, clearing, fee));
    ordered.sort(Comparator.comparing(w -> w.getId().toString()));
    for (Wallet w : ordered) {
      wallets.findByIdForUpdate(w.getId());
    }
    return new M3PostingAccounts(customer.getId(), clearing.getId(), fee.getId());
  }

  private Wallet findOrCreateSystemWallet(
      UUID systemUser, String currency, WalletAccountRole role) {
    return wallets
        .findByUserIdAndCurrencyAndAccountRole(systemUser, currency, role)
        .orElseGet(() -> wallets.saveAndFlush(new Wallet(systemUser, currency, role)));
  }

  private static UUID firstPresent(UUID first, UUID second) {
    if (first != null) {
      return first;
    }
    if (second != null) {
      return second;
    }
    throw new IllegalStateException("no system user configured for M3 posting accounts");
  }
}
