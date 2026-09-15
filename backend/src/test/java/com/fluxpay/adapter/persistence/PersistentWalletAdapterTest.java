package com.fluxpay.adapter.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.dto.WalletSnapshot;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistentWalletAdapterTest {
  @Test
  void missingClearingFailsWithoutCreatingAnAccount() {
    WalletRepository wallets = mock(WalletRepository.class);
    UUID user = UUID.randomUUID();
    UUID system = UUID.randomUUID();
    Wallet customer = new Wallet(user, "USD", WalletAccountRole.CUSTOMER);
    customer.setBalance(new BigDecimal("100.0000"));
    when(wallets.findById(customer.getId())).thenReturn(Optional.of(customer));
    when(wallets.findByIdForUpdate(customer.getId())).thenReturn(Optional.of(customer));
    when(wallets.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              throw new AssertionError("Payout must not manufacture a missing system account");
            });
    var adapter =
        new PersistentWalletAdapter(
            wallets,
            new com.fluxpay.service.SystemAccountService(
                wallets, new com.fluxpay.config.SystemAccountConfig(system.toString())));
    assertThatThrownBy(
            () ->
                adapter.lockPostingAccounts(
                    user, customer.getId(), "USD", new BigDecimal("100.0000")))
        .isInstanceOf(com.fluxpay.exception.SystemAccountUnavailableException.class);
  }

  @Test
  void findOwnedMapsAvailableFunds() {
    WalletRepository wallets = mock(WalletRepository.class);
    UUID user = UUID.randomUUID();
    Wallet w = new Wallet(user, "USD", WalletAccountRole.CUSTOMER);
    when(wallets.findByUserIdAndCurrencyAndAccountRole(user, "USD", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(w));
    PersistentWalletAdapter adapter =
        new PersistentWalletAdapter(
            wallets,
            new com.fluxpay.service.SystemAccountService(
                wallets, new com.fluxpay.config.SystemAccountConfig(UUID.randomUUID().toString())));
    Optional<WalletSnapshot> snap = adapter.findOwned(user, w.getId());
    assertTrue(snap.isPresent());
    assertEquals("USD", snap.get().currency());
    assertEquals(0, snap.get().availableFunds().compareTo(BigDecimal.ZERO));
  }
}
