package com.fluxpay.service;

public class FxSystemWalletNotFoundException extends RuntimeException {
  public FxSystemWalletNotFoundException(String currency, String role) {
    super("FX system wallet is not configured for " + currency + " / " + role);
  }
}
