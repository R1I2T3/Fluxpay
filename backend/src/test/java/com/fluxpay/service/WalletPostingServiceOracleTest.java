package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.config.M2DemoFundingConfig;
import com.fluxpay.config.M2FxConfig;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
      "fluxpay.demo-system-user-id=00000000-0000-0000-0000-00000000d004",
      "fluxpay.fx-system-user-id=00000000-0000-0000-0000-00000000d004",
      "fluxpay.fx-mode=mock",
      "fluxpay.fx-provider-url=https://fx.invalid/latest"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = WalletPostingServiceOracleTest.JpaConfiguration.class)
@Import({
  M2DemoFundingConfig.class,
  M2FxConfig.class,
  DemoFundingService.class,
  WalletPostingService.class,
  LedgerJournalService.class,
  PersistentLedgerWriter.class,
  LedgerPostingContext.class
})
class WalletPostingServiceOracleTest {
  private static final UUID SYSTEM_USER_ID =
      UUID.fromString("00000000-0000-0000-0000-00000000d004");

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
  }

  @Autowired DemoFundingService funding;
  @Autowired WalletRepository wallets;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void fundingCreatesCustomerWalletAndOneBalancedCompletedOperation() {
    UUID customerId = committedUser("fund-success");
    Wallet clearing = committedClearing("USD");
    BigDecimal clearingBefore = databaseBalance(clearing.getId());
    String key = "fund-success-" + UUID.randomUUID();

    WalletResponse response =
        funding.receiveDemo(customerId, new WalletReceiveRequest("USD", "25.0000"), key);

    assertEquals("USD", response.currency());
    assertEquals("25.0000", response.balance());
    assertEquals("0.0000", response.heldBalance());
    assertEquals("25.0000", response.availableBalance());
    assertEquals(money("25.0000"), databaseBalance(UUID.fromString(response.walletId())));
    assertEquals(clearingBefore.subtract(money("25.0000")), databaseBalance(clearing.getId()));
    assertEquals(2, ledgerCount(response.journalReference()));
    assertEquals(1, completedOperationCount(customerId, key));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void exactReplayReturnsSnapshotWithoutApplyingAnotherDelta() {
    UUID customerId = committedUser("fund-replay");
    Wallet clearing = committedClearing("USD");
    String key = "fund-replay-" + UUID.randomUUID();
    WalletResponse first =
        funding.receiveDemo(customerId, new WalletReceiveRequest("USD", "8.0000"), key);
    BigDecimal clearingAfterFirst = databaseBalance(clearing.getId());

    WalletResponse replay =
        funding.receiveDemo(customerId, new WalletReceiveRequest(" usd ", "8"), key);

    assertEquals(first, replay);
    assertEquals(money("8.0000"), databaseBalance(UUID.fromString(first.walletId())));
    assertEquals(clearingAfterFirst, databaseBalance(clearing.getId()));
    assertEquals(2, ledgerCount(first.journalReference()));
    assertEquals(1, completedOperationCount(customerId, key));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void changedPayloadWithTheSameKeyIsRejectedWithoutAnotherDelta() {
    UUID customerId = committedUser("fund-conflict");
    committedClearing("USD");
    String key = "fund-conflict-" + UUID.randomUUID();
    WalletResponse first =
        funding.receiveDemo(customerId, new WalletReceiveRequest("USD", "11.0000"), key);

    assertThrows(
        LedgerIdempotencyConflictException.class,
        () -> funding.receiveDemo(customerId, new WalletReceiveRequest("USD", "12.0000"), key));

    assertEquals(money("11.0000"), databaseBalance(UUID.fromString(first.walletId())));
    assertEquals(2, ledgerCount(first.journalReference()));
    assertEquals(1, completedOperationCount(customerId, key));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void missingClearingWalletRollsBackCustomerWalletAndOperation() {
    UUID customerId = committedUser("fund-rollback");
    String key = "fund-rollback-" + UUID.randomUUID();

    assertThrows(
        DemoClearingWalletNotFoundException.class,
        () -> funding.receiveDemo(customerId, new WalletReceiveRequest("INR", "7.0000"), key));

    assertEquals(0, customerWalletCount(customerId, "INR"));
    assertEquals(0, operationCount(customerId, key));
    assertEquals(0, ledgerKeyCount(key));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void concurrentIdenticalRequestsApplyExactlyOnce() throws Exception {
    UUID customerId = committedUser("fund-concurrent");
    Wallet clearing = committedClearing("USD");
    BigDecimal clearingBefore = databaseBalance(clearing.getId());
    String key = "fund-concurrent-" + UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<WalletResponse> first = executor.submit(() -> fundAfter(start, customerId, key));
      Future<WalletResponse> second = executor.submit(() -> fundAfter(start, customerId, key));
      start.countDown();

      WalletResponse one = first.get();
      WalletResponse two = second.get();
      assertEquals(one, two);
      assertEquals(money("13.0000"), databaseBalance(UUID.fromString(one.walletId())));
      assertEquals(clearingBefore.subtract(money("13.0000")), databaseBalance(clearing.getId()));
      assertEquals(2, ledgerCount(one.journalReference()));
      assertEquals(1, completedOperationCount(customerId, key));
    } finally {
      executor.shutdownNow();
    }
  }

  private WalletResponse fundAfter(CountDownLatch start, UUID customerId, String key)
      throws Exception {
    start.await();
    return funding.receiveDemo(customerId, new WalletReceiveRequest("USD", "13.0000"), key);
  }

  private UUID committedUser(String label) {
    UUID userId = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> insertUser(userId, label));
    return userId;
  }

  private Wallet committedClearing(String currency) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              ensureSystemUser();
              return wallets
                  .findByUserIdAndCurrencyAndAccountRole(
                      SYSTEM_USER_ID, currency, WalletAccountRole.DEMO_CLEARING)
                  .orElseGet(
                      () ->
                          wallets.saveAndFlush(
                              new Wallet(
                                  SYSTEM_USER_ID, currency, WalletAccountRole.DEMO_CLEARING)));
            });
  }

  private void ensureSystemUser() {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id=HEXTORAW(?)", Integer.class, raw(SYSTEM_USER_ID));
    if (count == 0) {
      insertUser(SYSTEM_USER_ID, "demo-system");
    }
  }

  private void insertUser(UUID userId, String label) {
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,full_name) "
            + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','M2 demo funding fixture')",
        raw(userId),
        label + "-" + userId + "@m2.invalid");
  }

  private BigDecimal databaseBalance(UUID walletId) {
    return jdbc.queryForObject(
            "SELECT balance FROM wallets WHERE id=HEXTORAW(?)", BigDecimal.class, raw(walletId))
        .setScale(4);
  }

  private int ledgerCount(String journalReference) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ledger_entries WHERE journal_reference=?",
        Integer.class,
        journalReference);
  }

  private int completedOperationCount(UUID userId, String key) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM wallet_operations "
            + "WHERE user_id=HEXTORAW(?) AND operation_type='RECEIVE_DEMO' "
            + "AND client_key=? AND status='COMPLETED' AND response_snapshot IS NOT NULL",
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

  private int ledgerKeyCount(String key) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key LIKE ?",
        Integer.class,
        "%" + key + "%");
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
