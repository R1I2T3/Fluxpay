package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.BankAccount;
import com.fluxpay.repository.BankAccountRepository;
import java.util.*;
import org.junit.jupiter.api.Test;

class BankAccountListTest {
  @Test
  void queriesOnlyTheAuthenticatedOwnersAccounts() {
    UUID user = UUID.randomUUID();
    var accounts = mock(BankAccountRepository.class);
    var normalize = mock(WalletRequestNormalizer.class);
    var bank = mock(BankAccount.class);
    when(bank.getId()).thenReturn(UUID.randomUUID());
    when(bank.getBankName()).thenReturn("Example Bank");
    when(bank.getAccountLast4()).thenReturn("1234");
    when(bank.getCurrency()).thenReturn("USD");
    when(bank.getStatus()).thenReturn("VERIFIED");
    when(accounts.findByUserId(user)).thenReturn(List.of(bank));
    var service = new BankAccountService(null, null, accounts, normalize);
    var result = service.list(user);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).accountLast4()).isEqualTo("1234");
    verify(normalize).user(user);
    verify(accounts).findByUserId(user);
    verifyNoMoreInteractions(accounts);
  }
}
