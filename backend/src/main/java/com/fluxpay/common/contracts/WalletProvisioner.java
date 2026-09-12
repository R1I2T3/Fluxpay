package com.fluxpay.common.contracts;

import java.util.List;
import java.util.UUID;

/** Provisions a new user's wallets as part of registration. */
public interface WalletProvisioner {
  List<UUID> provision(UUID userId);
}
