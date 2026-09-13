package com.fluxpay.adapter.persistence;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.WalletPort;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.dto.WalletSnapshot;
import com.fluxpay.exception.BusinessException;
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
public class PersistentWalletAdapter implements WalletPort {
  private final WalletRepository wallets;
  private final Supplier<UUID> systemUserSupplier;

  @org.springframework.beans.factory.annotation.Autowired
  public PersistentWalletAdapter(
      WalletRepository wallets, com.fluxpay.config.SystemAccountConfig systemAccounts) {
    this(wallets, systemAccounts::requireSystemUserId);
  }

  PersistentWalletAdapter(WalletRepository wallets, Supplier<UUID> systemUserSupplier) {
    this.wallets = Objects.requireNonNull(wallets, "wallets must not be null");
    this.systemUserSupplier =
        Objects.requireNonNull(systemUserSupplier, "systemUserSupplier must not be null");
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
        wallets.findByIdForUpdate(walletId).filter(w -> w.getUserId().equals(userId)).orElse(null);
    if (customer == null
        || customer.getAccountRole() != WalletAccountRole.CUSTOMER
        || !customer.getCurrency().equals(currency)
        || customer.getAvailableBalance().compareTo(gross) < 0) {
      throw new BusinessException(
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
    return new PostingAccounts(customer.getId(), clearing.getId(), fee.getId());
  }

  private Wallet findOrCreateSystemWallet(
      UUID systemUser, String currency, WalletAccountRole role) {
    return wallets
        .findByUserIdAndCurrencyAndAccountRole(systemUser, currency, role)
        .orElseGet(() -> wallets.saveAndFlush(new Wallet(systemUser, currency, role)));
  }
}
