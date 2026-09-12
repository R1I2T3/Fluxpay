package com.fluxpay.service;

public class DemoClearingWalletNotFoundException extends RuntimeException {
  public DemoClearingWalletNotFoundException(String currency) {
    super("Demo clearing wallet is not configured for " + currency);
  }
}
