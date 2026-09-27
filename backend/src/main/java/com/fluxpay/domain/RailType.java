package com.fluxpay.domain;

public enum RailType {
  INTERNAL_LEDGER("Internal Ledger"),
  BANK_NETWORK("Bank Network"),
  REAL_TIME_NETWORK("Real-Time Network"),
  PARTNER_NETWORK("Partner Network");

  private final String displayLabel;

  RailType(String displayLabel) {
    this.displayLabel = displayLabel;
  }

  public String displayLabel() {
    return displayLabel;
  }
}
