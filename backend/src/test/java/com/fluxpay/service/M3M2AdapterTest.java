package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.dto.M3PostingAccounts;
import com.fluxpay.repository.WalletRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class M3M2AdapterTest {
  private static final UUID OWNER = UUID.fromString("51000000-0000-0000-0000-000000000001");
  private static final UUID SYSTEM = UUID.fromString("51000000-0000-0000-0000-000000000002");

  private M3WalletPort walletPort(WalletRepository repository, EntityManager entities) {
    return assertDoesNotThrow(() -> (M3WalletPort) Class.forName("com.fluxpay.service.M3M2WalletAdapter")
        .getConstructor(WalletRepository.class, EntityManager.class, UUID.class)
        .newInstance(repository, entities, SYSTEM), "Real M3/M2 wallet adapter must exist");
  }

  @Test void ownedCustomerReportsSpendableBalanceAndRejectsSystemOrOtherOwner() {
    var repository = mock(WalletRepository.class);
    var customer = new Wallet(OWNER, "USD", WalletAccountRole.CUSTOMER);
    customer.setBalance(new BigDecimal("150.0000")); customer.setHeldBalance(new BigDecimal("40.0000"));
    when(repository.findById(customer.getId())).thenReturn(Optional.of(customer));
    var port = walletPort(repository, mock(EntityManager.class));
    assertEquals(new BigDecimal("110.0000"), port.findOwned(OWNER, customer.getId()).orElseThrow().availableFunds());
    assertTrue(port.findOwned(SYSTEM, customer.getId()).isEmpty());
    var internal = new Wallet(OWNER, "USD", WalletAccountRole.FEE_REVENUE);
    when(repository.findById(internal.getId())).thenReturn(Optional.of(internal));
    assertTrue(port.findOwned(OWNER, internal.getId()).isEmpty());
  }

  @Test void accountLockRechecksRefreshedFundsAndRequiresCallerTransaction() {
    var repository = mock(WalletRepository.class); var entities = mock(EntityManager.class);
    var customer = new Wallet(OWNER, "USD", WalletAccountRole.CUSTOMER);
    customer.setBalance(new BigDecimal("100"));
    var clearing = new Wallet(SYSTEM, "USD", WalletAccountRole.PAYOUT_CLEARING);
    var fee = new Wallet(SYSTEM, "USD", WalletAccountRole.FEE_REVENUE);
    when(repository.findById(customer.getId())).thenReturn(Optional.of(customer));
    when(repository.findByUserIdAndCurrencyAndAccountRole(SYSTEM,"USD",WalletAccountRole.PAYOUT_CLEARING)).thenReturn(Optional.of(clearing));
    when(repository.findByUserIdAndCurrencyAndAccountRole(SYSTEM,"USD",WalletAccountRole.FEE_REVENUE)).thenReturn(Optional.of(fee));
    when(repository.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(customer,clearing,fee));
    var port = walletPort(repository, entities);
    assertThrows(IllegalStateException.class, () -> port.lockPostingAccounts(OWNER,customer.getId(),"USD",new BigDecimal("50")));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertEquals(new M3PostingAccounts(customer.getId(),clearing.getId(),fee.getId()),
          port.lockPostingAccounts(OWNER,customer.getId(),"USD",new BigDecimal("50")));
      doAnswer(call -> { customer.setHeldBalance(new BigDecimal("90")); return null; }).when(entities).refresh(customer);
      var failure = assertThrows(com.fluxpay.config.M3BusinessException.class,
          () -> port.lockPostingAccounts(OWNER,customer.getId(),"USD",new BigDecimal("50")));
      assertEquals("INSUFFICIENT_FUNDS", failure.code());
    } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
  }

  @Test void postingUsesBalancedJournalAndSkipsZeroFeeLine() {
    List<String> posted = new ArrayList<>();
    LedgerWriter writer = (wallet,type,amount,currency,key) -> posted.add(type+":"+amount+":"+key);
    var journal = new LedgerJournalService(writer,new LedgerPostingContext());
    var accounts = new M3PostingAccounts(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    M3WalletPort wallets = new M3WalletPort() {
      public Optional<com.fluxpay.dto.M3WalletSnapshot> findOwned(UUID owner,UUID id) { return Optional.empty(); }
      public M3PostingAccounts lockPostingAccounts(UUID owner,UUID wallet,String c,BigDecimal g) { return accounts; }
    };
    Instant now = Instant.parse("2026-09-13T00:00:00Z");
    M3PostingPort port = assertDoesNotThrow(() -> (M3PostingPort) Class.forName("com.fluxpay.service.M3M2PostingAdapter")
        .getConstructor(M3WalletPort.class,LedgerJournalService.class,Clock.class)
        .newInstance(wallets,journal,Clock.fixed(now,ZoneOffset.UTC)));
    UUID payment = UUID.fromString("51000000-0000-0000-0000-000000000003");
    port.postApprovedPayment(payment,OWNER,accounts.customerWalletId(),"USD",new BigDecimal("100.0000"),BigDecimal.ZERO,now.plusSeconds(1));
    assertEquals(2,posted.size());
    assertTrue(posted.contains("DEBIT:100.0000:m3:"+payment+":customer"));
    assertTrue(posted.contains("CREDIT:100.0000:m3:"+payment+":clearing"));
    posted.clear();
    assertThrows(com.fluxpay.config.M3BusinessException.class, () -> port.postApprovedPayment(payment,OWNER,accounts.customerWalletId(),"USD",new BigDecimal("100"),new BigDecimal("1"),now));
    assertTrue(posted.isEmpty());
  }
}
