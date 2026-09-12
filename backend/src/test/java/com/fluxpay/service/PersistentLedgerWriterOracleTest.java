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
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@EnabledIfEnvironmentVariable(named = "M2_ORACLE_TESTS", matches = "true")
@DataJpaTest(
    showSql = false,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PersistentLedgerWriterOracleTest.JpaConfiguration.class)
@Import({PersistentLedgerWriter.class, LedgerPostingContext.class})
class PersistentLedgerWriterOracleTest {
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

  @Autowired LedgerWriter writer;
  @Autowired LedgerPostingContext context;
  @Autowired WalletRepository wallets;
  @Autowired LedgerEntryRepository entries;
  @Autowired TestEntityManager entityManager;
  @Autowired JdbcTemplate jdbc;

  @Test
  void creditAddsToBalanceAndPersistsAnImmutableEntry() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "0.0000", "0.0000");

    writer.append(wallet.getId(), "CREDIT", money("10.2500"), "USD", "writer:credit");
    entityManager.flush();
    entityManager.clear();

    assertEquals(money("10.2500"), wallets.findById(wallet.getId()).orElseThrow().getBalance());
    LedgerEntry entry = entries.findByIdempotencyKey("writer:credit").orElseThrow();
    assertEquals("CREDIT", entry.getEntryType());
    assertEquals(money("10.2500"), entry.getAmount());
    assertNull(entry.getJournalReference());
  }

  @Test
  void debitUsesAvailableBalanceRatherThanTotalBalance() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "20.0000", "5.0000");

    writer.append(wallet.getId(), "DEBIT", money("15.0000"), "USD", "writer:debit");
    entityManager.flush();
    entityManager.clear();

    Wallet updated = wallets.findById(wallet.getId()).orElseThrow();
    assertEquals(money("5.0000"), updated.getBalance());
    assertEquals(money("5.0000"), updated.getHeldBalance());
    assertEquals(money("0.0000"), updated.getAvailableBalance());
  }

  @Test
  void rejectsCustomerDebitAboveAvailableBalanceWithoutWritingAnEntry() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "20.0000", "5.0000");

    assertThrows(
        InsufficientWalletFundsException.class,
        () -> writer.append(wallet.getId(), "DEBIT", money("15.0001"), "USD", "writer:too-much"));

    assertTrue(entries.findByIdempotencyKey("writer:too-much").isEmpty());
    assertEquals(money("20.0000"), wallet.getBalance());
  }

  @Test
  void systemWalletMayHaveASignedBalance() {
    Wallet wallet = wallet("EUR", WalletAccountRole.FX_CLEARING, "0.0000", "0.0000");

    writer.append(wallet.getId(), "DEBIT", money("7.0000"), "EUR", "writer:system-debit");
    entityManager.flush();

    assertEquals(money("-7.0000"), wallet.getBalance());
  }

  @Test
  void rejectsCurrencyThatDoesNotMatchTheWallet() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "20.0000", "0.0000");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                writer.append(wallet.getId(), "DEBIT", money("1.0000"), "INR", "writer:wrong-ccy"));

    assertTrue(error.getMessage().contains("currency"));
    assertTrue(entries.findByIdempotencyKey("writer:wrong-ccy").isEmpty());
  }

  @Test
  void exactReplayChangesNeitherBalanceNorLedgerCount() {
    Wallet wallet = wallet("INR", WalletAccountRole.CUSTOMER, "0.0000", "0.0000");

    writer.append(wallet.getId(), "CREDIT", money("100.0000"), "INR", "writer:replay");
    entityManager.flush();
    UUID firstEntry = entries.findByIdempotencyKey("writer:replay").orElseThrow().getId();

    writer.append(wallet.getId(), "CREDIT", money("100.0000"), "INR", "writer:replay");
    entityManager.flush();

    assertEquals(money("100.0000"), wallet.getBalance());
    assertEquals(firstEntry, entries.findByIdempotencyKey("writer:replay").orElseThrow().getId());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE idempotency_key='writer:replay'",
            Integer.class));
  }

  @Test
  void reusedKeyWithDifferentPayloadIsAConflict() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "0.0000", "0.0000");
    writer.append(wallet.getId(), "CREDIT", money("10.0000"), "USD", "writer:conflict");
    entityManager.flush();

    assertThrows(
        LedgerIdempotencyConflictException.class,
        () -> writer.append(wallet.getId(), "CREDIT", money("11.0000"), "USD", "writer:conflict"));

    assertEquals(money("10.0000"), wallet.getBalance());
  }

  @Test
  void copiesBoundJournalMetadataToTheEntry() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "0.0000", "0.0000");
    String key = "writer:metadata";

    try (LedgerPostingContext.Scope ignored =
        context.bind(
            Map.of(key, new LedgerPostingContext.EntryMetadata("JRN-META", "Demo credit")))) {
      writer.append(wallet.getId(), "CREDIT", money("2.0000"), "USD", key);
    }
    entityManager.flush();

    LedgerEntry entry = entries.findByIdempotencyKey(key).orElseThrow();
    assertEquals("JRN-META", entry.getJournalReference());
    assertEquals("Demo credit", entry.getNarration());
  }

  @Test
  void rejectsInvalidDirectWriterValues() {
    Wallet wallet = wallet("USD", WalletAccountRole.CUSTOMER, "5.0000", "0.0000");

    assertThrows(
        IllegalArgumentException.class,
        () -> writer.append(wallet.getId(), "PAY", money("1.0000"), "USD", "writer:type"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            writer.append(
                wallet.getId(), "DEBIT", new BigDecimal("1.00001"), "USD", "writer:scale"));
    assertThrows(
        IllegalArgumentException.class,
        () -> writer.append(wallet.getId(), "DEBIT", BigDecimal.ZERO, "USD", "writer:zero"));
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void appendRequiresAnExistingTransaction() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> writer.append(UUID.randomUUID(), "CREDIT", money("1.0000"), "USD", "writer:no-tx"));
  }

  private Wallet wallet(String currency, WalletAccountRole role, String balance, String held) {
    Wallet wallet = new Wallet(fixtureUser(), currency, role);
    wallet.setBalance(money(balance));
    wallet.setHeldBalance(money(held));
    return wallets.saveAndFlush(wallet);
  }

  private UUID fixtureUser() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,full_name) "
            + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','M2 writer fixture')",
        id.toString().replace("-", ""),
        id + "@m2.invalid");
    return id;
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }
}
