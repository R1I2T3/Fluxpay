package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.transfer.InternalLedgerTransferRail;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.config.ConversionFeeSchedule;
import com.fluxpay.domain.ConversionMath;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RouteOutcome;
import com.fluxpay.dto.*;
import com.fluxpay.exception.*;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.*;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task3;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = WalletTransferTest.Config.class)
@Import({
  WalletTransferService.class,
  WalletTransferRoutingService.class,
  InternalLedgerTransferRail.class,
  SmartRoutingService.class,
  RailRegistry.class,
  RouteEligibilityService.class,
  RouteReliabilityService.class,
  RouteOutcomeRecorder.class,
  RoutePricingService.class,
  RouteRecommender.class,
  com.fluxpay.domain.QuotePricingPolicy.class,
  WalletPostingService.class,
  BankAccountService.class,
  BankAccountPostingService.class,
  WalletOperationService.class,
  LedgerJournalService.class,
  PersistentLedgerWriter.class,
  LedgerPostingContext.class,
  ConversionMath.class,
  ConversionFeeSchedule.class,
  FxQuoteValidator.class,
  WalletRequestNormalizer.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletTransferTest {
  static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class Config {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    Clock clock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    volatile Instant now = NOW;
    volatile Instant advanceAfterRead;

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Kolkata");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      Instant current = now;
      if (advanceAfterRead != null) {
        now = advanceAfterRead;
        advanceAfterRead = null;
      }
      return current;
    }
  }

  @Autowired WalletTransferService transfers;
  @Autowired WalletTransferRoutingService routed;
  @Autowired TransferProviderRepository providers;
  @Autowired TransferRouteRepository transferRoutes;
  @Autowired TransferRouteOutcomeRepository routeOutcomes;
  @Autowired BankAccountService banks;
  @Autowired WalletRepository wallets;
  @Autowired UserRepository users;
  @Autowired BankAccountRepository accounts;
  @Autowired LedgerEntryRepository entries;
  @Autowired LedgerJournalRepository journals;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @MockBean SystemAccountService systemAccounts;
  @MockBean CurrencyScaleService scales;
  @MockBean FxQuoteService quotes;
  @MockBean KycGate kyc;
  UUID sender;
  UUID recipient;
  Wallet source;

  @BeforeEach
  void setUp() {
    ((TestClock) clock).now = NOW;
    ((TestClock) clock).advanceAfterRead = null;
    jdbc.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS task3_wallet_unique ON wallets(user_id, currency, account_role)");
    for (int id = 0; id < 64; id++) {
      jdbc.update("MERGE INTO ledger_journal_locks(lock_id) KEY(lock_id) VALUES (?)", id);
    }
    sender = user();
    recipient = user();
    source = customer(sender, "USD", "20000");
    for (String currency : List.of("USD", "EUR", "INR")) {
      when(scales.scale(currency)).thenReturn(2);
      for (WalletAccountRole role :
          List.of(
              WalletAccountRole.FX_CLEARING,
              WalletAccountRole.FEE_REVENUE,
              WalletAccountRole.FX_GAIN_LOSS,
              WalletAccountRole.PAYOUT_CLEARING,
              WalletAccountRole.DEMO_CLEARING)) {
        Wallet system = wallets.saveAndFlush(new Wallet(user(), currency, role));
        when(systemAccounts.require(currency, role)).thenReturn(system);
      }
    }
    when(kyc.isVerified(sender)).thenReturn(true);
  }

  @Test
  void sameCurrencyConservesFundsAndReplaysCanonicalRequest() {
    var request = transfer(recipient, "USD", "USD", "20", "SOURCE");
    var result = transfers.transfer(sender, request, "same");
    assertThat(balance(source.getId())).isEqualByComparingTo("19980");
    assertThat(balance(UUID.fromString(result.targetWalletId()))).isEqualByComparingTo("20");
    assertThat(entries.findByJournalReference(result.journalReference())).hasSize(2);
    assertCategory(result.journalReference(), LedgerTransactionCategory.WALLET_TO_WALLET);
    assertBalanced(result.journalReference());
    assertThat(
            transfers.transfer(
                sender, transfer(recipient, " usd ", "usd", "20.000", "source"), "same"))
        .isEqualTo(result);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    sender, transfer(recipient, "USD", "USD", "21", "SOURCE"), "same"))
        .isInstanceOf(LedgerIdempotencyConflictException.class);
  }

  @Test
  void routedSameCurrencyPersistsDecisionAndReplays() {
    internalRoute("FLUXPAY_A", "FLUXPAY_USD_A", "USD");
    var response =
        routed.transfer(sender, transfer(recipient, "USD", "USD", "20", "SOURCE"), "route-same");
    assertThat(response.providerCode()).isEqualTo("FLUXPAY_A");
    assertThat(response.routeCode()).isEqualTo("FLUXPAY_USD_A");
    assertThat(response.railType()).isEqualTo(RailType.INTERNAL_LEDGER);
    assertThat(response.effectiveReliability()).isEqualByComparingTo("99.000000");
    assertThat(balance(source.getId())).isEqualByComparingTo("19980");
    assertThat(entries.findByJournalReference(response.journalReference())).hasSize(2);
    assertThat(entries.findByJournalReference(response.journalReference()))
        .filteredOn(
            line ->
                line.getEntryType().equals("DEBIT") && line.getWalletId().equals(source.getId()))
        .hasSize(1);
    assertCategory(response.journalReference(), LedgerTransactionCategory.WALLET_TO_WALLET);
    assertBalanced(response.journalReference());
    var outcome = routeOutcomes.findByExecutionReference(response.journalReference()).orElseThrow();
    assertThat(outcome.outcome()).isEqualTo(RouteOutcome.COMPLETED);
    assertThat(
            routed.transfer(
                sender, transfer(recipient, "USD", "USD", "20.00", "SOURCE"), "route-same"))
        .isEqualTo(response);
    assertThat(entries.findByJournalReference(response.journalReference())).hasSize(2);
    assertThatThrownBy(
            () ->
                routed.transfer(
                    sender, transfer(recipient, "USD", "USD", "21", "SOURCE"), "route-same"))
        .isInstanceOf(LedgerIdempotencyConflictException.class);
  }

  @Test
  void routedFxPostsSingleSenderDebitThroughInternalRail() {
    internalRoute("FLUXPAY_B", "FLUXPAY_INR_B", "INR");
    quote("USD", "INR", "83.50");
    var response =
        routed.transfer(sender, transfer(recipient, "USD", "INR", "100", "SOURCE"), "route-fx");
    assertThat(response.providerCode()).isEqualTo("FLUXPAY_B");
    assertThat(response.routeCode()).isEqualTo("FLUXPAY_INR_B");
    assertThat(response.railType()).isEqualTo(RailType.INTERNAL_LEDGER);
    assertThat(response.creditedAmount()).isEqualTo("8308.2500");
    assertThat(response.rate()).isEqualTo("83.50000000");
    assertThat(balance(source.getId())).isEqualByComparingTo("19900");
    assertThat(entries.findByJournalReference(response.journalReference())).hasSize(5);
    assertThat(entries.findByJournalReference(response.journalReference()))
        .filteredOn(
            line ->
                line.getEntryType().equals("DEBIT") && line.getWalletId().equals(source.getId()))
        .hasSize(1);
    assertThat(entries.findByJournalReference(response.journalReference()))
        .allSatisfy(line -> assertThat(line.getQuoteId()).isEqualTo(response.quoteId()));
    assertBalanced(response.journalReference());
    assertThat(
            routeOutcomes
                .findByExecutionReference(response.journalReference())
                .orElseThrow()
                .outcome())
        .isEqualTo(RouteOutcome.COMPLETED);
  }

  @Test
  void routedFailureRecordsFailedOutcome() {
    customer(sender, "EUR", "20000");
    TransferRoute route = internalRoute("FLUXPAY_C", "FLUXPAY_EUR_C", "EUR");
    assertThatThrownBy(
            () ->
                routed.transfer(
                    sender, transfer(recipient, "EUR", "EUR", "20001", "SOURCE"), "route-poor"))
        .isInstanceOf(InsufficientWalletFundsException.class);
    var counts = routeOutcomes.countByRouteIds(List.of(route.getId()));
    assertThat(counts).hasSize(1);
    assertThat(counts.get(0).getFailed()).isEqualTo(1);
    assertThat(counts.get(0).getCompleted()).isZero();
  }

  @Test
  void sourceFxPostsGrossFeeNetAndQuoteMetadata() {
    quote("USD", "INR", "83.50");
    var result =
        transfers.transfer(sender, transfer(recipient, "USD", "INR", "100", "SOURCE"), "fx");
    assertThat(result.sourceAmount()).isEqualTo("100.0000");
    assertThat(result.fee()).isEqualTo("0.5000");
    assertThat(result.netAmount()).isEqualTo("99.5000");
    assertThat(result.creditedAmount()).isEqualTo("8308.2500");
    assertThat(balance(source.getId())).isEqualByComparingTo("19900");
    assertThat(balance(UUID.fromString(result.targetWalletId()))).isEqualByComparingTo("8308.25");
    assertThat(entries.findByJournalReference(result.journalReference()))
        .hasSize(5)
        .allSatisfy(
            line -> {
              assertThat(line.getRate()).isEqualByComparingTo("83.50");
              assertThat(line.getQuoteId()).isNotBlank();
            });
    assertBalanced(result.journalReference());
  }

  @Test
  void targetFxGrossesUpAndPostsRoundingGainToCreditExactTarget() {
    quote("USD", "INR", "83.50");
    var result =
        transfers.transfer(sender, transfer(recipient, "USD", "INR", "100", "TARGET"), "target");
    assertThat(result.sourceAmount()).isEqualTo("1.2100");
    assertThat(result.fee()).isEqualTo("0.0100");
    assertThat(result.netAmount()).isEqualTo("1.2000");
    assertThat(result.creditedAmount()).isEqualTo("100.0000");
    assertThat(balance(UUID.fromString(result.targetWalletId()))).isEqualByComparingTo("100");
    assertThat(entries.findByJournalReference(result.journalReference())).hasSize(6);
    UUID gainLossWallet = systemAccounts.require("INR", WalletAccountRole.FX_GAIN_LOSS).getId();
    assertThat(entries.findByJournalReference(result.journalReference()))
        .filteredOn(line -> line.getWalletId().equals(gainLossWallet))
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.getCurrency()).isEqualTo("INR");
              assertThat(line.getEntryType()).isEqualTo("CREDIT");
              assertThat(line.getAmount()).isEqualByComparingTo("0.2000");
              assertThat(line.getRate()).isEqualByComparingTo("83.50000000");
              assertThat(line.getRate()).isEqualByComparingTo(result.rate());
              assertThat(line.getQuoteId()).isEqualTo(result.quoteId());
            });
    assertBalanced(result.journalReference());
  }

  @ParameterizedTest
  @CsvSource({"USD,EUR", "USD,INR", "EUR,USD", "EUR,INR", "INR,USD", "INR,EUR"})
  void allDirectedPairsUseCurrencyScale(String from, String to) {
    if (!from.equals("USD")) customer(sender, from, "100");
    quote(from, to, "1.23456789");
    var result = transfers.transfer(sender, transfer(sender, from, to, "10", "SOURCE"), "pair");
    assertThat(result.creditedAmount()).isEqualTo("12.2800");
    assertCategory(result.journalReference(), LedgerTransactionCategory.SELF_TRANSFER);
    assertBalanced(result.journalReference());
  }

  @Test
  void staleQuoteAndInsufficientFundsLeaveNoPostingOrRecipientWallet() {
    when(quotes.snapshot("USD", "EUR"))
        .thenReturn(
            new FxSnapshot("USD", "EUR", new BigDecimal("0.9"), NOW.minusSeconds(3600), false));
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    sender, transfer(recipient, "USD", "EUR", "10", "SOURCE"), "stale"))
        .isInstanceOf(RequoteRequiredException.class);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    sender, transfer(recipient, "USD", "USD", "20001", "SOURCE"), "poor"))
        .isInstanceOf(InsufficientWalletFundsException.class);
    assertThat(balance(source.getId())).isEqualByComparingTo("20000");
    assertThat(
            wallets.findByUserIdAndCurrencyAndAccountRole(
                recipient, "USD", WalletAccountRole.CUSTOMER))
        .isEmpty();
  }

  @Test
  void rejectsNoOpMissingOrAmbiguousRecipientAndInvalidAmounts() {
    for (WalletTransferRequest request :
        List.of(
            transfer(sender, "USD", "USD", "1", "SOURCE"),
            transfer(UUID.randomUUID(), "USD", "USD", "1", "SOURCE"),
            new WalletTransferRequest(null, null, "USD", "USD", "1", "SOURCE", null),
            new WalletTransferRequest(
                recipient, "x@test.invalid", "USD", "USD", "1", "SOURCE", null),
            transfer(recipient, "USD", "USD", "0", "SOURCE"),
            transfer(recipient, "USD", "USD", "0.001", "SOURCE"),
            transfer(recipient, "USD", "USD", "1000000000000000", "SOURCE"))) {
      assertThatThrownBy(() -> transfers.transfer(sender, request, UUID.randomUUID().toString()))
          .isInstanceOfAny(IllegalArgumentException.class, BusinessException.class);
    }
    var byEmail =
        new WalletTransferRequest(
            null,
            "  "
                + users.findById(recipient).orElseThrow().getEmail().toUpperCase(Locale.ROOT)
                + " ",
            "USD",
            "USD",
            "1",
            "TARGET",
            null);
    assertThat(transfers.transfer(sender, byEmail, "email").creditedAmount()).isEqualTo("1.0000");
  }

  @Test
  void linkValidatesLast4AndReplaysRedactedResponse() throws Exception {
    var result = banks.link(sender, new BankLinkRequest(" Test Bank ", "0123", "usd"), "link");
    assertThat(result.accountLast4()).isEqualTo("0123");
    assertThat(result.status()).isEqualTo("VERIFIED");
    assertThat(banks.link(sender, new BankLinkRequest("Test Bank", "0123", "USD"), "link"))
        .isEqualTo(result);
    assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("accountNumber");
    for (String invalid : List.of("123456789", "abcd", "123", "１２３４")) {
      assertThatThrownBy(
              () -> banks.link(sender, new BankLinkRequest("Bank", invalid, "USD"), "bad"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void withdrawalRequiresOwnedVerifiedMatchingBankAndBalancesPayout() {
    UUID valid = bank(sender, "USD", "VERIFIED");
    var result =
        banks.withdraw(sender, new WalletWithdrawRequest(valid, "USD", "25", "rent"), "withdraw");
    assertThat(balance(source.getId())).isEqualByComparingTo("19975");
    assertCategory(result.journalReference(), LedgerTransactionCategory.SEND_MONEY);
    assertBalanced(result.journalReference());
    for (UUID invalid :
        List.of(
            UUID.randomUUID(),
            bank(recipient, "USD", "VERIFIED"),
            bank(sender, "USD", "PENDING"),
            bank(sender, "EUR", "VERIFIED"))) {
      assertThatThrownBy(
              () ->
                  banks.withdraw(
                      sender,
                      new WalletWithdrawRequest(invalid, "USD", "1", null),
                      UUID.randomUUID().toString()))
          .isInstanceOf(BusinessException.class);
    }
    assertThat(
            banks.withdraw(
                sender, new WalletWithdrawRequest(valid, "USD", "25.00", "rent"), "withdraw"))
        .isEqualTo(result);
  }

  @Test
  void topupRequiresKycAndBankAndEnforcesDurableCapAndReplay() {
    UUID valid = bank(sender, "USD", "VERIFIED");
    when(kyc.isVerified(sender)).thenReturn(false);
    assertThatThrownBy(() -> banks.topup(sender, valid, new BankTopupRequest("1", null), "kyc"))
        .isInstanceOf(BusinessException.class);
    when(kyc.isVerified(sender)).thenReturn(true);
    assertThatThrownBy(
            () ->
                banks.topup(
                    sender,
                    bank(recipient, "USD", "VERIFIED"),
                    new BankTopupRequest("1", null),
                    "owner"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                banks.topup(
                    sender,
                    bank(sender, "USD", "PENDING"),
                    new BankTopupRequest("1", null),
                    "pending"))
        .isInstanceOf(BusinessException.class);
    var result = banks.topup(sender, valid, new BankTopupRequest("10000", null), "cap");
    assertThat(balance(source.getId())).isEqualByComparingTo("30000");
    assertCategory(result.journalReference(), LedgerTransactionCategory.WALLET_TOPUP);
    assertBalanced(result.journalReference());
    assertThat(banks.topup(sender, valid, new BankTopupRequest("10000.00", null), "cap"))
        .isEqualTo(result);
    assertThatThrownBy(() -> banks.topup(sender, valid, new BankTopupRequest("0.01", null), "over"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("TOPUP_CAP_EXCEEDED"));
    banks.topup(
        sender, bank(sender, "EUR", "VERIFIED"), new BankTopupRequest("10000", null), "euro");
  }

  @Test
  void concurrentTopupsCannotExceedCap() throws Exception {
    UUID bank = bank(sender, "USD", "VERIFIED");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<Boolean> call =
          () -> {
            start.await();
            try {
              banks.topup(
                  sender, bank, new BankTopupRequest("6000", null), UUID.randomUUID().toString());
              return true;
            } catch (BusinessException e) {
              assertThat(e.code()).isEqualTo("TOPUP_CAP_EXCEEDED");
              return false;
            }
          };
      Future<Boolean> a = executor.submit(call);
      Future<Boolean> b = executor.submit(call);
      start.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(balance(source.getId())).isEqualByComparingTo("26000");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void capResetsAtUtcMidnightAndIsIndependentForOtherUsers() {
    UUID bank = bank(sender, "USD", "VERIFIED");
    ((TestClock) clock).now = NOW.minusSeconds(1);
    banks.topup(sender, bank, new BankTopupRequest("10000", null), "yesterday");
    ((TestClock) clock).now = NOW;
    banks.topup(sender, bank, new BankTopupRequest("10000", null), "today");
    assertThat(balance(source.getId())).isEqualByComparingTo("40000");
    when(kyc.isVerified(recipient)).thenReturn(true);
    var other =
        banks.topup(
            recipient,
            bank(recipient, "USD", "VERIFIED"),
            new BankTopupRequest("10000", null),
            "other");
    assertThat(balance(UUID.fromString(other.walletId()))).isEqualByComparingTo("10000");
  }

  @Test
  void concurrentTopupsBelowCapPreserveBothCredits() throws Exception {
    UUID bank = bank(sender, "USD", "VERIFIED");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Callable<WalletResponse> call =
          () -> {
            start.await();
            return banks.topup(
                sender, bank, new BankTopupRequest("4000", null), UUID.randomUUID().toString());
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      a.get(20, TimeUnit.SECONDS);
      b.get(20, TimeUnit.SECONDS);
      assertThat(balance(source.getId())).isEqualByComparingTo("28000");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void targetModeUsesSourceFeeAndExactRecipientCredit() {
    quote("USD", "EUR", "2");
    // Default fee: 50.26 - round(50.26 * .005) = 50.01, yielding 100.02 clearing.
    var result =
        transfers.transfer(
            sender, transfer(recipient, "USD", "EUR", "100.01", "TARGET"), "target-two");
    assertThat(result.sourceAmount()).isEqualTo("50.2600");
    assertThat(result.fee()).isEqualTo("0.2500");
    assertThat(result.creditedAmount()).isEqualTo("100.0100");
    assertBalanced(result.journalReference());
  }

  @Test
  void transferValidationRejectsUnsupportedCurrenciesModesNotesAndMissingKeys() {
    for (WalletTransferRequest request :
        List.of(
            transfer(recipient, "GBP", "USD", "1", "SOURCE"),
            transfer(recipient, "USD", "EUR", "1", "OTHER"),
            new WalletTransferRequest(
                recipient, null, "USD", "USD", "1", "SOURCE", "x".repeat(256)))) {
      assertThatThrownBy(() -> transfers.transfer(sender, request, "bad"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                transfers.transfer(sender, transfer(recipient, "USD", "USD", "1", "SOURCE"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentTransfersCannotSpendTheSameFunds() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    customer(recipient, "USD", "0");
    try {
      Callable<Boolean> call =
          () -> {
            start.await();
            try {
              transfers.transfer(
                  sender,
                  transfer(recipient, "USD", "USD", "15000", "SOURCE"),
                  UUID.randomUUID().toString());
              return true;
            } catch (InsufficientWalletFundsException expected) {
              return false;
            }
          };
      var a = executor.submit(call);
      var b = executor.submit(call);
      start.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(balance(source.getId())).isEqualByComparingTo("5000");
      assertThat(wallets.findById(source.getId()).orElseThrow().getHeldBalance())
          .isEqualByComparingTo("0");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void heldFundsAreUnavailableForWithdrawal() {
    source.setHeldBalance(new BigDecimal("19999"));
    wallets.saveAndFlush(source);
    assertThatThrownBy(
            () ->
                banks.withdraw(
                    sender,
                    new WalletWithdrawRequest(bank(sender, "USD", "VERIFIED"), "USD", "2", null),
                    "held"))
        .isInstanceOf(InsufficientWalletFundsException.class);
    assertThat(balance(source.getId())).isEqualByComparingTo("20000");
    assertThat(wallets.findById(source.getId()).orElseThrow().getHeldBalance())
        .isEqualByComparingTo("19999");
  }

  @Test
  void acceptedQuoteThatAgesBeforePostingRollsBackEveryMutation() {
    quote("USD", "EUR", "0.92");
    ((TestClock) clock).advanceAfterRead = NOW.plusSeconds(3600);
    int before = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
    assertThatThrownBy(
            () ->
                transfers.transfer(
                    sender, transfer(recipient, "USD", "EUR", "10", "SOURCE"), "aged"))
        .isInstanceOf(RequoteRequiredException.class);
    assertThat(balance(source.getId())).isEqualByComparingTo("20000");
    assertThat(
            wallets.findByUserIdAndCurrencyAndAccountRole(
                recipient, "EUR", WalletAccountRole.CUSTOMER))
        .isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_operations WHERE client_key='aged'", Integer.class))
        .isZero();
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    users.saveAndFlush(new User(id, id + "@test.invalid", "!", "USER", "Test", NOW, NOW));
    return id;
  }

  private Wallet customer(UUID user, String currency, String amount) {
    Wallet wallet = new Wallet(user, currency, WalletAccountRole.CUSTOMER);
    wallet.setBalance(new BigDecimal(amount).setScale(4));
    return wallets.saveAndFlush(wallet);
  }

  private UUID bank(UUID user, String currency, String status) {
    return accounts.save(new BankAccount(user, "Test Bank", "1234", currency, status)).getId();
  }

  private void quote(String from, String to, String rate) {
    when(quotes.snapshot(from, to))
        .thenReturn(new FxSnapshot(from, to, new BigDecimal(rate), NOW, false));
  }

  private BigDecimal balance(UUID wallet) {
    return wallets.findById(wallet).orElseThrow().getBalance();
  }

  private WalletTransferRequest transfer(
      UUID to, String from, String target, String amount, String mode) {
    return new WalletTransferRequest(to, null, from, target, amount, mode, null);
  }

  private TransferRoute internalRoute(String providerCode, String routeCode, String payout) {
    TransferProvider provider =
        TransferProvider.create(
            UUID.randomUUID(),
            providerCode,
            providerCode + " Ledger",
            RailType.INTERNAL_LEDGER,
            true,
            false,
            NOW);
    providers.saveAndFlush(provider);
    TransferRoute route =
        TransferRoute.create(
            UUID.randomUUID(),
            provider,
            routeCode,
            routeCode + " route",
            DestinationType.INTERNAL_WALLET,
            null,
            payout,
            new BigDecimal("0.0000"),
            new BigDecimal("0.000000"),
            5,
            new BigDecimal("99.00"),
            null,
            null,
            true,
            false,
            NOW);
    return transferRoutes.saveAndFlush(route);
  }

  private void assertCategory(String ref, LedgerTransactionCategory category) {
    assertThat(journals.findByJournalReference(ref).orElseThrow().getTransactionCategory())
        .isEqualTo(category);
  }

  private void assertBalanced(String ref) {
    Map<String, BigDecimal> totals = new HashMap<>();
    entries
        .findByJournalReference(ref)
        .forEach(
            e ->
                totals.merge(
                    e.getCurrency(),
                    e.getEntryType().equals("DEBIT") ? e.getAmount() : e.getAmount().negate(),
                    BigDecimal::add));
    assertThat(totals.values()).allSatisfy(sum -> assertThat(sum).isEqualByComparingTo("0"));
  }
}
