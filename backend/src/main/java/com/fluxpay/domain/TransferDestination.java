package com.fluxpay.domain;

/** Immutable execution-time destination for a transfer rail command. */
public sealed interface TransferDestination
    permits InternalWalletDestination, ExternalAccountDestination {
  DestinationType type();
}
