package com.fluxpay.service;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.config.SystemAccountConfig;
import com.fluxpay.exception.SystemAccountUnavailableException;
import com.fluxpay.repository.WalletRepository;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;

/** Resolves pre-provisioned wallets owned by the canonical system identity. */
@Service
public class SystemAccountService {
  private final WalletRepository wallets;
  private final SystemAccountConfig config;

  public SystemAccountService(WalletRepository wallets, SystemAccountConfig config) {
    this.wallets = wallets;
    this.config = config;
  }

  public Wallet require(String currency, WalletAccountRole role) {
    if (role == null || role == WalletAccountRole.CUSTOMER) {
      throw new SystemAccountUnavailableException(currency, role);
    }
    var owner = config.requireSystemUserId();
    try {
      return wallets
          .findByUserIdAndCurrencyAndAccountRole(owner, currency, role)
          .filter(
              wallet ->
                  owner.equals(wallet.getUserId())
                      && currency.equals(wallet.getCurrency())
                      && role == wallet.getAccountRole())
          .orElseThrow(() -> new SystemAccountUnavailableException(currency, role));
    } catch (IncorrectResultSizeDataAccessException ambiguous) {
      throw new SystemAccountUnavailableException(currency, role);
    }
  }
}
