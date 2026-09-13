package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersistentLedgerWriterClockTest {
  @Test
  void postingUsesApplicationClockForLedgerAuditTime() {
    WalletRepository wallets = mock(WalletRepository.class);
    LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
    Wallet wallet = new Wallet(UUID.randomUUID(), "USD", WalletAccountRole.CUSTOMER);
    when(wallets.findByIdForUpdate(wallet.getId())).thenReturn(Optional.of(wallet));
    new PersistentLedgerWriter(
            wallets,
            entries,
            new LedgerPostingContext(),
            java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC))
        .append(wallet.getId(), "CREDIT", new BigDecimal("10.0000"), "USD", "audit-key");
    ArgumentCaptor<LedgerEntry> entry = ArgumentCaptor.forClass(LedgerEntry.class);
    verify(entries).save(entry.capture());
    assertThat(entry.getValue().getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
  }
}
