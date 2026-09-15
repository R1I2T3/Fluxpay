package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "M2_ORACLE_TESTS", matches = "true")
@DataJpaTest(
    showSql = false,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = LedgerJournalOracleTest.JpaConfiguration.class)
@Import({LedgerJournalService.class, PersistentLedgerWriter.class, LedgerPostingContext.class})
class LedgerJournalOracleTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class JpaConfiguration {}

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) throws Exception {
    migrateIsolatedSchema();
    properties.add("spring.datasource.url", () -> System.getenv("ORACLE_JDBC_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("ORACLE_USERNAME"));
    properties.add("spring.datasource.password", () -> System.getenv("ORACLE_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
  }

  @Autowired LedgerJournalService journals;
  @Autowired LedgerWriter writer;
  @Autowired LedgerPostingContext context;
  @Autowired WalletRepository wallets;
  @Autowired LedgerEntryRepository entries;
  @Autowired TestEntityManager entityManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  void postsACompleteBalancedJournalWithOneReference() {
    Wallet customer = wallet("USD", WalletAccountRole.CUSTOMER, "20.0000");
    Wallet clearing = wallet("USD", WalletAccountRole.FX_CLEARING, "0.0000");

    journals.post(
        "JRN-ORACLE-1",
        List.of(
            line(customer, "DEBIT", "5.0000", "USD", "JRN-ORACLE-1:customer", "Customer debit"),
            line(clearing, "CREDIT", "5.0000", "USD", "JRN-ORACLE-1:clearing", "Clearing credit")));
    entityManager.flush();

    assertEquals(money("15.0000"), customer.getBalance());
    assertEquals(money("5.0000"), clearing.getBalance());
    assertEquals("JRN-ORACLE-1", entry("JRN-ORACLE-1:customer").getJournalReference());
    assertEquals("Customer debit", entry("JRN-ORACLE-1:customer").getNarration());
  }

  @Test
  void replayingAWholeJournalAppliesEveryLineOnlyOnce() {
    Wallet customer = wallet("EUR", WalletAccountRole.CUSTOMER, "30.0000");
    Wallet clearing = wallet("EUR", WalletAccountRole.FX_CLEARING, "0.0000");
    List<LedgerJournalLine> lines =
        List.of(
            line(customer, "DEBIT", "7.0000", "EUR", "JRN-REPLAY:customer", "Customer debit"),
            line(clearing, "CREDIT", "7.0000", "EUR", "JRN-REPLAY:clearing", "Clearing credit"));

    journals.post("JRN-REPLAY", lines);
    journals.post("JRN-REPLAY", lines);
    entityManager.flush();

    assertEquals(money("23.0000"), customer.getBalance());
    assertEquals(money("7.0000"), clearing.getBalance());
    assertEquals(2, ledgerCount("JRN-REPLAY"));
  }

  @Test
  void refundShapedCompensatingJournalRestoresFundsOnlyOnce() {
    Wallet payoutClearing = wallet("INR", WalletAccountRole.PAYOUT_CLEARING, "0.0000");
    Wallet customer = wallet("INR", WalletAccountRole.CUSTOMER, "0.0000");
    List<LedgerJournalLine> refund =
        List.of(
            line(payoutClearing, "DEBIT", "50.0000", "INR", "JRN-REFUND:clearing", "Refund source"),
            line(customer, "CREDIT", "50.0000", "INR", "JRN-REFUND:customer", "Customer refund"));

    journals.post("JRN-REFUND", refund);
    journals.post("JRN-REFUND", refund);
    entityManager.flush();

    assertEquals(money("-50.0000"), payoutClearing.getBalance());
    assertEquals(money("50.0000"), customer.getBalance());
    assertEquals(2, ledgerCount("JRN-REFUND"));
  }

  @Test
  void rejectsAJournalThatMixesAReplayedLineWithANewLine() {
    Wallet customer = wallet("USD", WalletAccountRole.CUSTOMER, "20.0000");
    Wallet clearing = wallet("USD", WalletAccountRole.FX_CLEARING, "0.0000");
    String existingKey = "JRN-PARTIAL:customer";
    try (LedgerPostingContext.Scope ignored =
        context.bind(
            java.util.Map.of(
                existingKey,
                new LedgerPostingContext.EntryMetadata("JRN-PARTIAL", "Customer debit")))) {
      writer.append(customer.getId(), "DEBIT", money("5.0000"), "USD", existingKey);
    }

    assertThrows(
        IllegalStateException.class,
        () ->
            journals.post(
                "JRN-PARTIAL",
                List.of(
                    line(customer, "DEBIT", "5.0000", "USD", existingKey, "Customer debit"),
                    line(
                        clearing,
                        "CREDIT",
                        "5.0000",
                        "USD",
                        "JRN-PARTIAL:clearing",
                        "Clearing credit"))));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void failureAfterOnePostingRollsBackEveryBalanceAndEntry() {
    Wallet first = committedWallet("USD", WalletAccountRole.CUSTOMER, "20.0000");
    Wallet second = committedWallet("EUR", WalletAccountRole.CUSTOMER, "20.0000");
    Wallet valid = canonicalFirst(first, second);
    Wallet wrongCurrency = valid.getId().equals(first.getId()) ? second : first;
    String journalCurrency = valid.getCurrency();
    String journal = "JRN-ROLLBACK-" + UUID.randomUUID();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            journals.post(
                journal,
                List.of(
                    line(
                        valid,
                        "DEBIT",
                        "5.0000",
                        journalCurrency,
                        journal + ":first",
                        "First line"),
                    line(
                        wrongCurrency,
                        "CREDIT",
                        "5.0000",
                        journalCurrency,
                        journal + ":second",
                        "Forced mismatch"))));

    assertEquals(money("20.0000"), databaseBalance(valid.getId()));
    assertEquals(money("20.0000"), databaseBalance(wrongCurrency.getId()));
    assertEquals(0, ledgerCount(journal));
    assertNull(context.current(journal + ":first"));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentCustomerDebitsCannotOverdrawAvailableFunds() throws Exception {
    Wallet customer = committedWallet("USD", WalletAccountRole.CUSTOMER, "100.0000");
    Wallet clearing = committedWallet("USD", WalletAccountRole.FX_CLEARING, "0.0000");
    CountDownLatch start = new CountDownLatch(1);
    String run = UUID.randomUUID().toString();
    String firstJournal = "JRN-SPEND-A-" + run;
    String secondJournal = "JRN-SPEND-B-" + run;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(() -> postConcurrentSpend(start, firstJournal, customer, clearing));
      Future<Boolean> second =
          executor.submit(() -> postConcurrentSpend(start, secondJournal, customer, clearing));
      start.countDown();

      int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
      assertEquals(1, successes);
      assertEquals(money("20.0000"), databaseBalance(customer.getId()));
      assertEquals(money("80.0000"), databaseBalance(clearing.getId()));
      assertEquals(2, ledgerCount(firstJournal) + ledgerCount(secondJournal));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentIdenticalJournalIsAppliedExactlyOnce() throws Exception {
    Wallet customer = committedWallet("EUR", WalletAccountRole.CUSTOMER, "100.0000");
    Wallet clearing = committedWallet("EUR", WalletAccountRole.FX_CLEARING, "0.0000");
    String journal = "JRN-SAME-" + UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(() -> postConcurrentSpend(start, journal, customer, clearing, "EUR"));
      Future<Boolean> second =
          executor.submit(() -> postConcurrentSpend(start, journal, customer, clearing, "EUR"));
      start.countDown();

      assertTrue(first.get());
      assertTrue(second.get());
      assertEquals(money("20.0000"), databaseBalance(customer.getId()));
      assertEquals(money("80.0000"), databaseBalance(clearing.getId()));
      assertEquals(2, ledgerCount(journal));
    } finally {
      executor.shutdownNow();
    }
  }

  private boolean postConcurrentSpend(
      CountDownLatch start, String journal, Wallet customer, Wallet clearing) throws Exception {
    return postConcurrentSpend(start, journal, customer, clearing, "USD");
  }

  private boolean postConcurrentSpend(
      CountDownLatch start, String journal, Wallet customer, Wallet clearing, String currency)
      throws Exception {
    start.await();
    try {
      journals.post(
          journal,
          List.of(
              line(customer, "DEBIT", "80.0000", currency, journal + ":customer", "Spend"),
              line(clearing, "CREDIT", "80.0000", currency, journal + ":clearing", "Clearing")));
      return true;
    } catch (InsufficientWalletFundsException expected) {
      return false;
    }
  }

  private Wallet committedWallet(String currency, WalletAccountRole role, String balance) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> wallet(currency, role, balance));
  }

  private Wallet wallet(String currency, WalletAccountRole role, String balance) {
    UUID userId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,full_name) "
            + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','M2 journal fixture')",
        userId.toString().replace("-", ""),
        userId + "@m2.invalid");
    Wallet wallet = new Wallet(userId, currency, role);
    wallet.setBalance(money(balance));
    return wallets.saveAndFlush(wallet);
  }

  private static Wallet canonicalFirst(Wallet one, Wallet two) {
    return one.getId().toString().compareTo(two.getId().toString()) < 0 ? one : two;
  }

  private BigDecimal databaseBalance(UUID walletId) {
    return jdbc.queryForObject(
            "SELECT balance FROM wallets WHERE id=HEXTORAW(?)",
            BigDecimal.class,
            walletId.toString().replace("-", ""))
        .setScale(4);
  }

  private int ledgerCount(String journalReference) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ledger_entries WHERE journal_reference=?",
        Integer.class,
        journalReference);
  }

  private LedgerEntry entry(String key) {
    return entries.findByIdempotencyKey(key).orElseThrow();
  }

  private static LedgerJournalLine line(
      Wallet wallet, String type, String amount, String currency, String key, String narration) {
    return new LedgerJournalLine(wallet.getId(), type, money(amount), currency, key, narration);
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  private static void migrateIsolatedSchema() throws Exception {
    String username = System.getenv("ORACLE_USERNAME");
    if (!"FLUXPAY_M2_TEST".equalsIgnoreCase(username)) {
      throw new IllegalStateException("Oracle tests require the dedicated FLUXPAY_M2_TEST schema");
    }
    try (Connection connection =
            DriverManager.getConnection(
                System.getenv("ORACLE_JDBC_URL"), username, System.getenv("ORACLE_PASSWORD"));
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT USER FROM dual")) {
      result.next();
      assertEquals("FLUXPAY_M2_TEST", result.getString(1));
    }
    Flyway.configure()
        .dataSource(System.getenv("ORACLE_JDBC_URL"), username, System.getenv("ORACLE_PASSWORD"))
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .outOfOrder(true)
        .load()
        .migrate();
  }
}
