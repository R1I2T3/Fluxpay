package com.fluxpay.config;

import com.fluxpay.common.contracts.WalletProvisioner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Solo-development wallet provisioner; it returns stable stubs and creates no wallet records. */
@Component
@ConditionalOnMissingBean(WalletProvisioner.class)
public class M1MockWalletProvisioner implements WalletProvisioner {
  private final Set<UUID> provisionedUsers = ConcurrentHashMap.newKeySet();

  @Override
  public List<UUID> provision(UUID userId) {
    provisionedUsers.add(userId);
    return List.of(stableWalletId(userId, 1), stableWalletId(userId, 2), stableWalletId(userId, 3));
  }

  public boolean wasProvisioned(UUID userId) {
    return provisionedUsers.contains(userId);
  }

  private UUID stableWalletId(UUID userId, int slot) {
    return UUID.nameUUIDFromBytes((userId + ":wallet:" + slot).getBytes(StandardCharsets.UTF_8));
  }
}
