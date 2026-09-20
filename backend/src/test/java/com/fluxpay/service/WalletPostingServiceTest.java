package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.beans.LedgerTransactionCategory;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.domain.ConversionCalculation;
import com.fluxpay.repository.WalletOperationRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WalletPostingServiceTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SYSTEM_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private SystemAccountService systemAccounts;
  private WalletRepository wallets;
  private WalletOperationRepository operations;
  private LedgerJournalService journals;
  private WalletPostingService service;

  @BeforeEach
  void setUp() {
    systemAccounts = mock(SystemAccountService.class);
    wallets = mock(WalletRepository.class);
    operations = mock(WalletOperationRepository.class);
    journals = mock(LedgerJournalService.class);
    service =
        new WalletPostingService(
            systemAccounts,
            wallets,
            operations,
            journals,
            new ObjectMapper().findAndRegisterModules(),
            mock(FxQuoteValidator.class));
  }

  @Test
  void demoFundingUsesABusinessJournalNamespace() {
    Wallet clearing = new Wallet(SYSTEM_ID, "USD", WalletAccountRole.DEMO_CLEARING);
    Wallet customer = new Wallet(USER_ID, "USD", WalletAccountRole.CUSTOMER);
    when(systemAccounts.require("USD", WalletAccountRole.DEMO_CLEARING)).thenReturn(clearing);
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "RECEIVE_DEMO", "demo-key"))
        .thenReturn(Optional.empty());
    when(wallets.findByUserIdAndCurrencyAndAccountRole(USER_ID, "USD", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(customer));

    var response = service.receiveDemo(USER_ID, "USD", new BigDecimal("10.0000"), "{}", "demo-key");

    assertThat(response.journalReference()).startsWith("wallet:demo:").doesNotContain("M2");
  }

  @Test
  void conversionUsesABusinessJournalNamespace() {
    Wallet sourceClearing = new Wallet(SYSTEM_ID, "USD", WalletAccountRole.FX_CLEARING);
    Wallet targetClearing = new Wallet(SYSTEM_ID, "INR", WalletAccountRole.FX_CLEARING);
    Wallet source = new Wallet(USER_ID, "USD", WalletAccountRole.CUSTOMER);
    Wallet target = new Wallet(USER_ID, "INR", WalletAccountRole.CUSTOMER);
    when(systemAccounts.require("USD", WalletAccountRole.FX_CLEARING)).thenReturn(sourceClearing);
    when(systemAccounts.require("INR", WalletAccountRole.FX_CLEARING)).thenReturn(targetClearing);
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", "fx-key"))
        .thenReturn(Optional.empty());
    when(wallets.findByUserIdAndCurrencyAndAccountRole(USER_ID, "USD", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(source));
    when(wallets.findByUserIdAndCurrencyAndAccountRole(USER_ID, "INR", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(target));

    var response =
        service.convert(
            USER_ID,
            "USD",
            "INR",
            new ConversionCalculation(
                new BigDecimal("10.0000"),
                BigDecimal.ZERO.setScale(4),
                new BigDecimal("10.0000"),
                new BigDecimal("830.0000"),
                new BigDecimal("83.00000000")),
            new FxSnapshot(
                "USD",
                "INR",
                new BigDecimal("83.0000"),
                Instant.parse("2026-09-15T00:00:00Z"),
                false),
            "11111111-1111-1111-1111-111111111111",
            "{}",
            "fx-key");

    assertThat(response.journalReference()).startsWith("wallet:fx:").doesNotContain("M2");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<LedgerJournalLine>> lines = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<WalletReservation> reservation = ArgumentCaptor.forClass(WalletReservation.class);
    verify(journals)
        .postReserved(
            eq(response.journalReference()),
            eq(LedgerTransactionCategory.SELF_TRANSFER),
            lines.capture(),
            reservation.capture(),
            any(Runnable.class));
    assertThat(lines.getValue())
        .allSatisfy(
            line -> {
              assertThat(line.rate()).isEqualByComparingTo("83.00000000");
              assertThat(line.quoteId()).isEqualTo("11111111-1111-1111-1111-111111111111");
            });
    assertThat(reservation.getValue().walletId()).isEqualTo(source.getId());
    assertThat(reservation.getValue().amount()).isEqualByComparingTo("10.0000");
  }

  @Test
  void retainedQuoteThatAgesWhileWaitingForJournalLockRequiresRequote() {
    Instant fetchedAt = Instant.parse("2026-09-15T00:00:00Z");
    AtomicReference<Instant> now = new AtomicReference<>(fetchedAt.plusSeconds(3599));
    Clock clock =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    service =
        new WalletPostingService(
            systemAccounts,
            wallets,
            operations,
            journals,
            new ObjectMapper().findAndRegisterModules(),
            new FxQuoteValidator(clock));
    Wallet sourceClearing = new Wallet(SYSTEM_ID, "USD", WalletAccountRole.FX_CLEARING);
    Wallet targetClearing = new Wallet(SYSTEM_ID, "INR", WalletAccountRole.FX_CLEARING);
    Wallet source = new Wallet(USER_ID, "USD", WalletAccountRole.CUSTOMER);
    Wallet target = new Wallet(USER_ID, "INR", WalletAccountRole.CUSTOMER);
    when(systemAccounts.require("USD", WalletAccountRole.FX_CLEARING)).thenReturn(sourceClearing);
    when(systemAccounts.require("INR", WalletAccountRole.FX_CLEARING)).thenReturn(targetClearing);
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", "aged-key"))
        .thenReturn(Optional.empty());
    when(wallets.findByUserIdAndCurrencyAndAccountRole(USER_ID, "USD", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(source));
    when(wallets.findByUserIdAndCurrencyAndAccountRole(USER_ID, "INR", WalletAccountRole.CUSTOMER))
        .thenReturn(Optional.of(target));
    doAnswer(
            invocation -> {
              now.set(fetchedAt.plusSeconds(3600));
              invocation.getArgument(4, Runnable.class).run();
              return null;
            })
        .when(journals)
        .postReserved(any(), any(), any(), any(), any());

    org.junit.jupiter.api.Assertions.assertThrows(
        com.fluxpay.exception.RequoteRequiredException.class,
        () ->
            service.convert(
                USER_ID,
                "USD",
                "INR",
                new ConversionCalculation(
                    new BigDecimal("10.0000"),
                    new BigDecimal("0.0000"),
                    new BigDecimal("10.0000"),
                    new BigDecimal("830.0000"),
                    new BigDecimal("83.00000000")),
                new FxSnapshot(
                    "USD", "INR", new BigDecimal("83.00000000"), fetchedAt, false),
                "11111111-1111-1111-1111-111111111111",
                "{}",
                "aged-key"));
  }
}
