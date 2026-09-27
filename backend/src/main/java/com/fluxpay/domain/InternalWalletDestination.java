package com.fluxpay.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record InternalWalletDestination(UUID userId, UUID walletId, String currency)
    implements TransferDestination {
  public InternalWalletDestination {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(walletId, "walletId must not be null");
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    currency = currency.trim().toUpperCase(Locale.ROOT);
  }

  @Override
  public DestinationType type() {
    return DestinationType.INTERNAL_WALLET;
  }
}
