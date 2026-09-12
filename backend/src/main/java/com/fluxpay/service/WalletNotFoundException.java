package com.fluxpay.service;

public class WalletNotFoundException extends RuntimeException {
  public WalletNotFoundException() {
    super("Wallet not found");
  }
}
