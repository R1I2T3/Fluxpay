package com.fluxpay.service;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.WalletProvisioner;
import com.fluxpay.repository.WalletRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PersistentWalletProvisioner implements WalletProvisioner {
  private static final List<String> CURRENCIES = List.of("USD", "EUR", "INR");
  private final WalletRepository wallets;

  public PersistentWalletProvisioner(WalletRepository wallets) {
    this.wallets = Objects.requireNonNull(wallets, "wallets must not be null");
  }

  @Override
  @Transactional
  public List<UUID> provision(UUID userId) {
    Objects.requireNonNull(userId, "userId must not be null");
    List<UUID> ids = new ArrayList<>(3);
    for (String currency : CURRENCIES) {
      Wallet wallet =
          wallets
              .findByUserIdAndCurrencyAndAccountRole(userId, currency, WalletAccountRole.CUSTOMER)
              .orElseGet(
                  () ->
                      wallets.saveAndFlush(
                          new Wallet(userId, currency, WalletAccountRole.CUSTOMER)));
      ids.add(wallet.getId());
    }
    return List.copyOf(ids);
  }
}
