package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.dto.M3WalletSnapshot;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistentM3WalletAdapterTest {
  @Test
  void findOwnedMapsAvailableFunds() {
    WalletRepository wallets = mock(WalletRepository.class);
    UUID user = UUID.randomUUID();
    Wallet w = new Wallet(user, "USD", WalletAccountRole.CUSTOMER);
    when(wallets.findByUserIdAndCurrencyAndAccountRole(user, "USD", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(w));
    PersistentM3WalletAdapter adapter =
        new PersistentM3WalletAdapter(wallets, () -> UUID.randomUUID());
    Optional<M3WalletSnapshot> snap = adapter.findOwned(user, w.getId());
    assertTrue(snap.isPresent());
    assertEquals("USD", snap.get().currency());
    assertEquals(0, snap.get().availableFunds().compareTo(BigDecimal.ZERO));
  }
}
