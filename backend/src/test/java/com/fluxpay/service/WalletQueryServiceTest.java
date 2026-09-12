package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.dto.LedgerPageResponse;
import com.fluxpay.dto.WalletSummaryResponse;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class WalletQueryServiceTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Mock WalletRepository wallets;
  @Mock LedgerEntryRepository entries;
  WalletQueryService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new WalletQueryService(wallets, entries);
  }

  @Test
  void listsOnlyRepositoryFilteredCustomerWalletsAsExactMoneyStrings() {
    Wallet eur = customer(USER_ID, "EUR", "50.0000", "5.2500");
    Wallet usd = customer(USER_ID, "USD", "100.0000", "25.0000");
    when(wallets.findByUserIdAndAccountRoleOrderByCurrencyAsc(USER_ID, WalletAccountRole.CUSTOMER))
        .thenReturn(List.of(eur, usd));

    List<WalletSummaryResponse> result = service.wallets(USER_ID);

    assertEquals(2, result.size());
    assertEquals(eur.getId().toString(), result.get(0).walletId());
    assertEquals("EUR", result.get(0).currency());
    assertEquals("5.2500", result.get(0).heldBalance());
    assertEquals("44.7500", result.get(0).availableBalance());
    assertEquals("75.0000", result.get(1).availableBalance());
  }

  @Test
  void ledgerReturnsStablePageMetadataAndSafeEntryFields() {
    Wallet wallet = customer(USER_ID, "USD", "100.0000", "0.0000");
    Instant created = Instant.parse("2026-09-11T04:05:06Z");
    LedgerEntry entry =
        new LedgerEntry(
            wallet.getId(),
            "DEBIT",
            new BigDecimal("10.5000"),
            "USD",
            "private-line-key",
            "M2-FX-journal",
            "FX conversion gross debit",
            created);
    PageRequest request = PageRequest.of(1, 2);
    when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
    when(entries.findByWalletIdOrderByCreatedAtDescIdDesc(wallet.getId(), request))
        .thenReturn(new PageImpl<>(List.of(entry), request, 5));

    LedgerPageResponse result = service.ledger(USER_ID, wallet.getId(), 1, 2);

    assertEquals(1, result.page());
    assertEquals(2, result.size());
    assertEquals(5, result.totalElements());
    assertEquals(3, result.totalPages());
    assertEquals(1, result.entries().size());
    assertEquals(entry.getId().toString(), result.entries().get(0).entryId());
    assertEquals("DEBIT", result.entries().get(0).entryType());
    assertEquals("10.5000", result.entries().get(0).amount());
    assertEquals("M2-FX-journal", result.entries().get(0).journalReference());
    assertEquals("FX conversion gross debit", result.entries().get(0).narration());
    assertEquals("2026-09-11T04:05:06Z", result.entries().get(0).createdAt());
    verify(entries).findByWalletIdOrderByCreatedAtDescIdDesc(wallet.getId(), request);
  }

  @Test
  void foreignWalletAndMissingWalletUseTheSameNotFoundResult() {
    Wallet foreign =
        customer(
            UUID.fromString("22222222-2222-2222-2222-222222222222"), "USD", "1.0000", "0.0000");
    when(wallets.findById(foreign.getId())).thenReturn(Optional.of(foreign));
    UUID missing = UUID.fromString("33333333-3333-3333-3333-333333333333");
    when(wallets.findById(missing)).thenReturn(Optional.empty());

    assertThrows(
        WalletNotFoundException.class, () -> service.ledger(USER_ID, foreign.getId(), 0, 20));
    assertThrows(WalletNotFoundException.class, () -> service.ledger(USER_ID, missing, 0, 20));
    verifyNoInteractions(entries);
  }

  @Test
  void systemWalletIsNotVisibleThroughCustomerLedgerApi() {
    Wallet system = new Wallet(USER_ID, "USD", WalletAccountRole.FX_CLEARING);
    when(wallets.findById(system.getId())).thenReturn(Optional.of(system));

    assertThrows(
        WalletNotFoundException.class, () -> service.ledger(USER_ID, system.getId(), 0, 20));
    verifyNoInteractions(entries);
  }

  @ParameterizedTest
  @CsvSource({"-1,20", "0,0", "0,101"})
  void rejectsUnsafePaginationBounds(int page, int size) {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.ledger(USER_ID, UUID.randomUUID(), page, size));
    verifyNoInteractions(wallets, entries);
  }

  private static Wallet customer(UUID userId, String currency, String balance, String held) {
    Wallet wallet = new Wallet(userId, currency, WalletAccountRole.CUSTOMER);
    wallet.setBalance(new BigDecimal(balance));
    wallet.setHeldBalance(new BigDecimal(held));
    return wallet;
  }
}
