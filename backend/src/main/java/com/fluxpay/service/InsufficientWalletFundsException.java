package com.fluxpay.service;

import java.util.UUID;

public class InsufficientWalletFundsException extends RuntimeException {
  public InsufficientWalletFundsException(UUID walletId) {
    super("Insufficient available funds in wallet " + walletId);
  }
}
