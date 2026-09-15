package com.fluxpay.exception;

import java.util.UUID;

public class InsufficientWalletFundsException extends RuntimeException {
  public InsufficientWalletFundsException(UUID walletId) {
    super("Insufficient available funds in wallet " + walletId);
  }
}
