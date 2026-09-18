package com.fluxpay.domain;

import java.util.Locale;

public record ExternalAccountDestination(
    String accountReference, String bankName, String country, String currency)
    implements TransferDestination {
  public ExternalAccountDestination {
    if (accountReference == null || accountReference.isBlank()) {
      throw new IllegalArgumentException("accountReference must not be blank");
    }
    if (country == null || country.isBlank()) {
      throw new IllegalArgumentException("country must not be blank");
    }
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    accountReference = accountReference.trim();
    bankName = bankName == null ? null : bankName.trim();
    country = country.trim().toUpperCase(Locale.ROOT);
    currency = currency.trim().toUpperCase(Locale.ROOT);
  }

  @Override
  public DestinationType type() {
    return DestinationType.EXTERNAL_ACCOUNT;
  }
}
