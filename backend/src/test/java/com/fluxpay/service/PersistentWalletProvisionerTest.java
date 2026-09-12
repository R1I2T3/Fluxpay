package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.Wallet;
import com.fluxpay.common.contracts.WalletProvisioner;
import com.fluxpay.repository.WalletRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistentWalletProvisionerTest {
  @Test
  void provisionCreatesThreeCustomerWalletsOnce() {
    WalletRepository wallets = mock(WalletRepository.class);
    UUID user = UUID.randomUUID();
    when(wallets.findByUserIdAndCurrencyAndAccountRole(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(wallets.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0, Wallet.class));
    WalletProvisioner provisioner = new PersistentWalletProvisioner(wallets);
    List<UUID> first = provisioner.provision(user);
    assertEquals(3, first.size());
    verify(wallets, times(3)).saveAndFlush(any(Wallet.class));
  }
}
