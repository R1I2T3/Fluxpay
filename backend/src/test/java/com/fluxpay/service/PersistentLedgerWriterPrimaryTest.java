package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PersistentLedgerWriterPrimaryTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(LedgerPostingContext.class)
          .withBean(WalletRepository.class, () -> org.mockito.Mockito.mock(WalletRepository.class))
          .withBean(
              LedgerEntryRepository.class,
              () -> org.mockito.Mockito.mock(LedgerEntryRepository.class))
          .withBean(PersistentLedgerWriter.class);

  @Test
  void persistentLedgerIsTheSoleLedgerWriterBean() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(LedgerWriter.class);
          assertThat(context).hasSingleBean(PersistentLedgerWriter.class);
          assertThat(context.getBean(LedgerWriter.class))
              .isInstanceOf(PersistentLedgerWriter.class);
        });
  }
}
