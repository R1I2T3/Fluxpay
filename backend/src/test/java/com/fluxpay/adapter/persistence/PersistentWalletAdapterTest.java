package com.fluxpay.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
  void findOwnedMapsAvailableFunds() {
    WalletRepository wallets = mock(WalletRepository.class);
    UUID user = UUID.randomUUID();
    Wallet w = new Wallet(user, "USD", WalletAccountRole.CUSTOMER);
    when(wallets.findByUserIdAndCurrencyAndAccountRole(user, "USD", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(w));
    PersistentWalletAdapter adapter = new PersistentWalletAdapter(wallets, () -> UUID.randomUUID());
    Optional<WalletSnapshot> snap = adapter.findOwned(user, w.getId());
    assertTrue(snap.isPresent());
    assertEquals("USD", snap.get().currency());
    assertEquals(0, snap.get().availableFunds().compareTo(BigDecimal.ZERO));
  }
}
