package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.config.M2DemoFundingConfig;
import com.fluxpay.config.M2FxConfig;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
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
      "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
      "fluxpay.demo-funding-enabled=true",
      "fluxpay.fx-mode=mock",
      "fluxpay.fx-provider-url=https://fx.invalid/latest"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = WalletConversionPostingOracleTest.JpaConfiguration.class)
@Import({
  M2DemoFundingConfig.class,
  M2FxConfig.class,
  WalletConversionService.class,
  WalletPostingService.class,
  LedgerJournalService.class,
  PersistentLedgerWriter.class,
  LedgerPostingContext.class
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WalletConversionPostingOracleTest {
  private static final UUID SYSTEM_USER_ID = UUID.randomUUID();
  private static final Instant FETCHED_AT = Instant.parse("2026-09-11T01:02:03Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class JpaConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) throws Exception {
    migrateIsolatedSchema();
    properties.add("spring.datasource.url", () -> System.getenv("ORACLE_JDBC_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("ORACLE_USERNAME"));
    properties.add("spring.datasource.password", () -> System.getenv("ORACLE_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
    properties.add("fluxpay.demo-system-user-id", SYSTEM_USER_ID::toString);
    properties.add("fluxpay.fx-system-user-id", SYSTEM_USER_ID::toString);
  }

  @Autowired WalletConversionService conversion;
  @Autowired WalletRepository wallets;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @MockBean FxQuoteService quotes;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void oneHundredUsdPostsTheLiteralFiveLineBalancedJournal() {
    UUID userId = committedUser("fx-example");
    Wallet source = committedCustomer(userId, "USD", "500.0000");
    provisionSystemWallets("USD", "INR", true);
    when(quotes.snapshot("USD", "INR")).thenReturn(snapshot("USD", "INR", "83.50"));
    String key = "fx-example-" + UUID.randomUUID();

    WalletConvertResponse result =
        conversion.convert(userId, new WalletConvertRequest("USD", "INR", "100.0000"), key);

    assertEquals("0.5000", result.fee());
    assertEquals("99.5000", result.netAmount());
    assertEquals("8308.2500", result.creditedAmount());
    assertEquals(money("400.0000"), databaseBalance(source.getId()));
    assertEquals(money("8308.2500"), databaseBalance(UUID.fromString(result.targetWalletId())));
    assertEquals(5, ledgerCount(result.journalReference()));
    assertEquals(money("0.0000"), journalImbalance(result.journalReference(), "USD"));
    assertEquals(money("0.0000"), journalImbalance(result.journalReference(), "INR"));
    assertEquals(1, completedOperationCount(userId, key));
  }

  @ParameterizedTest
  @MethodSource("directedPairs")
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void everySupportedDirectedPairBalancesIndependently(String from, String to) {
    UUID userId = committedUser("fx-pair-" + from + "-" + to);
    committedCustomer(userId, from, "20.0000");
    provisionSystemWallets(from, to, true);
    when(quotes.snapshot(from, to)).thenReturn(snapshot(from, to, "2"));

    WalletConvertResponse result =
        conversion.convert(
            userId, new WalletConvertRequest(from, to, "10.0000"), "fx-pair-" + UUID.randomUUID());

    assertEquals("0.0500", result.fee());
    assertEquals("19.9000", result.creditedAmount());
    assertEquals(money("0.0000"), journalImbalance(result.journalReference(), from));
    assertEquals(money("0.0000"), journalImbalance(result.journalReference(), to));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void exactReplayReturnsSnapshotWithoutAnotherBalanceOrLedgerChange() {
    UUID userId = committedUser("fx-replay");
    Wallet source = committedCustomer(userId, "USD", "50.0000");
    provisionSystemWallets("USD", "EUR", true);
    when(quotes.snapshot("USD", "EUR")).thenReturn(snapshot("USD", "EUR", "0.92"));
    String key = "fx-replay-" + UUID.randomUUID();
    WalletConvertResponse first =
        conversion.convert(userId, new WalletConvertRequest("USD", "EUR", "10"), key);
    BigDecimal targetAfterFirst = databaseBalance(UUID.fromString(first.targetWalletId()));

    WalletConvertResponse replay =
        conversion.convert(userId, new WalletConvertRequest(" usd ", "eur", "10.0000"), key);

    assertEquals(first, replay);
    assertEquals(money("40.0000"), databaseBalance(source.getId()));
    assertEquals(targetAfterFirst, databaseBalance(UUID.fromString(first.targetWalletId())));
    assertEquals(5, ledgerCount(first.journalReference()));
  }

  @Test
  @Order(1)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void missingFeeWalletRollsBackTargetOperationLedgerAndBalances() {
    UUID userId = committedUser("fx-rollback");
    Wallet source = committedCustomer(userId, "USD", "50.0000");
    provisionSystemWallets("USD", "INR", false);
    when(quotes.snapshot("USD", "INR")).thenReturn(snapshot("USD", "INR", "83.50"));
    String key = "fx-rollback-" + UUID.randomUUID();
    int ledgerBefore = allLedgerCount();

    assertThrows(
        FxSystemWalletNotFoundException.class,
        () -> conversion.convert(userId, new WalletConvertRequest("USD", "INR", "10"), key));

    assertEquals(money("50.0000"), databaseBalance(source.getId()));
    assertEquals(0, customerWalletCount(userId, "INR"));
    assertEquals(0, operationCount(userId, key));
    assertEquals(ledgerBefore, allLedgerCount());
  }

  @Test
  @Order(2)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void roundedZeroFeeOmitsTheFeeLineAndDoesNotRequireAFeeWallet() {
    UUID userId = committedUser("fx-zero-fee");
    committedCustomer(userId, "USD", "1.0000");
    provisionSystemWallets("USD", "INR", false);
    when(quotes.snapshot("USD", "INR")).thenReturn(snapshot("USD", "INR", "1"));

    WalletConvertResponse result =
        conversion.convert(
            userId,
            new WalletConvertRequest("USD", "INR", "0.0001"),
            "fx-zero-fee-" + UUID.randomUUID());

    assertEquals("0.0000", result.fee());
    assertEquals("0.0001", result.creditedAmount());
    assertEquals(4, ledgerCount(result.journalReference()));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void insufficientAvailableFundsRollsBackEveryPosting() {
    UUID userId = committedUser("fx-insufficient");
    Wallet source = committedCustomer(userId, "EUR", "1.0000");
    provisionSystemWallets("EUR", "INR", true);
    when(quotes.snapshot("EUR", "INR")).thenReturn(snapshot("EUR", "INR", "90"));
    String key = "fx-insufficient-" + UUID.randomUUID();

    assertThrows(
        InsufficientWalletFundsException.class,
        () -> conversion.convert(userId, new WalletConvertRequest("EUR", "INR", "2"), key));

    assertEquals(money("1.0000"), databaseBalance(source.getId()));
    assertEquals(0, customerWalletCount(userId, "INR"));
    assertEquals(0, operationCount(userId, key));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentIdenticalConversionAppliesExactlyOnce() throws Exception {
    UUID userId = committedUser("fx-concurrent");
    Wallet source = committedCustomer(userId, "USD", "100.0000");
    provisionSystemWallets("USD", "INR", true);
    when(quotes.snapshot("USD", "INR")).thenReturn(snapshot("USD", "INR", "2"));
    String key = "fx-concurrent-" + UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<WalletConvertResponse> first = executor.submit(() -> convertAfter(start, userId, key));
      Future<WalletConvertResponse> second =
          executor.submit(() -> convertAfter(start, userId, key));
      start.countDown();

      WalletConvertResponse one = first.get();
      WalletConvertResponse two = second.get();
      assertEquals(one, two);
      assertEquals(money("90.0000"), databaseBalance(source.getId()));
      assertEquals(money("19.9000"), databaseBalance(UUID.fromString(one.targetWalletId())));
      assertEquals(5, ledgerCount(one.journalReference()));
      assertEquals(1, completedOperationCount(userId, key));
    } finally {
      executor.shutdownNow();
    }
  }

  private WalletConvertResponse convertAfter(CountDownLatch start, UUID userId, String key)
      throws Exception {
    start.await();
    return conversion.convert(userId, new WalletConvertRequest("USD", "INR", "10"), key);
  }

  private void provisionSystemWallets(String from, String to, boolean includeFee) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              ensureSystemUser();
              systemWallet(from, WalletAccountRole.FX_CLEARING);
              systemWallet(to, WalletAccountRole.FX_CLEARING);
              if (includeFee) {
                systemWallet(from, WalletAccountRole.FEE_REVENUE);
              }
            });
  }

  private Wallet systemWallet(String currency, WalletAccountRole role) {
    return wallets
        .findByUserIdAndCurrencyAndAccountRole(SYSTEM_USER_ID, currency, role)
        .orElseGet(() -> wallets.saveAndFlush(new Wallet(SYSTEM_USER_ID, currency, role)));
  }

  private Wallet committedCustomer(UUID userId, String currency, String balance) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              Wallet wallet = new Wallet(userId, currency, WalletAccountRole.CUSTOMER);
              wallet.setBalance(money(balance));
              return wallets.saveAndFlush(wallet);
            });
  }

  private UUID committedUser(String label) {
    UUID userId = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> insertUser(userId, label));
    return userId;
  }

  private void ensureSystemUser() {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id=HEXTORAW(?)", Integer.class, raw(SYSTEM_USER_ID));
    if (count == 0) {
      insertUser(SYSTEM_USER_ID, "fx-system");
    }
  }

  private void insertUser(UUID userId, String label) {
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,full_name) "
            + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','M2 FX fixture')",
        raw(userId),
        label + "-" + userId + "@m2.invalid");
  }

  private BigDecimal databaseBalance(UUID walletId) {
    return jdbc.queryForObject(
            "SELECT balance FROM wallets WHERE id=HEXTORAW(?)", BigDecimal.class, raw(walletId))
        .setScale(4);
  }

  private BigDecimal journalImbalance(String journalReference, String currency) {
    return jdbc.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END),0) "
                + "FROM ledger_entries WHERE journal_reference=? AND currency=?",
            BigDecimal.class,
            journalReference,
            currency)
        .setScale(4);
  }

  private int ledgerCount(String journalReference) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ledger_entries WHERE journal_reference=?",
        Integer.class,
        journalReference);
  }

  private int allLedgerCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
  }

  private int completedOperationCount(UUID userId, String key) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM wallet_operations WHERE user_id=HEXTORAW(?) "
            + "AND operation_type='CONVERT' AND client_key=? AND status='COMPLETED'",
        Integer.class,
        raw(userId),
        key);
  }

  private int operationCount(UUID userId, String key) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM wallet_operations WHERE user_id=HEXTORAW(?) AND client_key=?",
        Integer.class,
        raw(userId),
        key);
  }

  private int customerWalletCount(UUID userId, String currency) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM wallets WHERE user_id=HEXTORAW(?) "
            + "AND currency=? AND account_role='CUSTOMER'",
        Integer.class,
        raw(userId),
        currency);
  }

  private static FxSnapshot snapshot(String from, String to, String rate) {
    return new FxSnapshot(from, to, new BigDecimal(rate), FETCHED_AT, false, true);
  }

  private static Stream<Arguments> directedPairs() {
    return Stream.of(
        Arguments.of("USD", "EUR"),
        Arguments.of("USD", "INR"),
        Arguments.of("EUR", "USD"),
        Arguments.of("EUR", "INR"),
        Arguments.of("INR", "USD"),
        Arguments.of("INR", "EUR"));
  }

  private static String raw(UUID value) {
    return value.toString().replace("-", "");
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
