package com.fluxpay.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Immutable execution-time destination for a transfer rail command. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ExternalAccountDestination.class, name = "externalAccount"),
  @JsonSubTypes.Type(value = InternalWalletDestination.class, name = "internalWallet")
})
public sealed interface TransferDestination
    permits InternalWalletDestination, ExternalAccountDestination {
  DestinationType type();
}
