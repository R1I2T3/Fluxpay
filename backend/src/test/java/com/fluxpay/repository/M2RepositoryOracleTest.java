package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.beans.WalletOperation;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "M2_ORACLE_TESTS", matches = "true")
@DataJpaTest(
    showSql = false,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = M2RepositoryOracleTest.JpaConfiguration.class)
class M2RepositoryOracleTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class JpaConfiguration {}

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) throws Exception {
    M2SchemaOracleTest.migrateIsolatedSchema();
    properties.add("spring.datasource.url", () -> System.getenv("ORACLE_JDBC_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("ORACLE_USERNAME"));
    properties.add("spring.datasource.password", () -> System.getenv("ORACLE_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
  }

  @Autowired WalletRepository wallets;
  @Autowired LedgerEntryRepository entries;
  @Autowired WalletOperationRepository operations;
  @Autowired TestEntityManager entityManager;
  @Autowired JdbcTemplate jdbc;

  private UUID fixtureUser() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,full_name) "
            + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','M2 fixture')",
        id.toString().replace("-", ""),
        id + "@m2.invalid");
    return id;
  }

  private Wallet wallet(UUID userId, String currency, WalletAccountRole role) {
    return wallets.saveAndFlush(new Wallet(userId, currency, role));
  }

  @Test
  void mapsUuidToRaw16AndComputesAvailableFunds() {
    UUID userId = fixtureUser();
    Wallet wallet = wallet(userId, "USD", WalletAccountRole.CUSTOMER);
    wallet.setBalance(new BigDecimal("100.0000"));
    wallet.setHeldBalance(new BigDecimal("25.0000"));
    entityManager.flush();
    UUID id = wallet.getId();
    Long version = wallet.getVersion();
    entityManager.clear();
    Wallet reloaded = wallets.findById(id).orElseThrow();
    assertEquals(userId, reloaded.getUserId());
    assertEquals(new BigDecimal("75.0000"), reloaded.getAvailableBalance());
    assertTrue(version > 0);
    assertEquals(
        id.toString().replace("-", "").toUpperCase(),
        jdbc.queryForObject(
            "SELECT RAWTOHEX(id) FROM wallets WHERE user_id=HEXTORAW(?)",
            String.class,
            userId.toString().replace("-", "")));
  }

  @Test
  void filtersCustomerWalletsByOwnerAndRole() {
    UUID owner = fixtureUser();
    Wallet own = wallet(owner, "USD", WalletAccountRole.CUSTOMER);
    wallet(owner, "USD", WalletAccountRole.FX_CLEARING);
    wallet(fixtureUser(), "USD", WalletAccountRole.CUSTOMER);
    List<Wallet> found =
        wallets.findByUserIdAndAccountRoleOrderByCurrencyAsc(owner, WalletAccountRole.CUSTOMER);
    assertEquals(List.of(own.getId()), found.stream().map(Wallet::getId).toList());
    assertEquals(
        own.getId(),
        wallets
            .findByUserIdAndCurrencyAndAccountRole(owner, "USD", WalletAccountRole.CUSTOMER)
            .orElseThrow()
            .getId());
  }

  @Test
  void enforcesUniqueOwnerCurrencyRole() {
    UUID owner = fixtureUser();
    wallet(owner, "USD", WalletAccountRole.CUSTOMER);
    assertThrows(
        DataIntegrityViolationException.class,
        () -> wallet(owner, "USD", WalletAccountRole.CUSTOMER));
  }

  @Test
  void rejectsCustomerOverdraftAtDatabaseBoundary() {
    Wallet wallet = wallet(fixtureUser(), "USD", WalletAccountRole.CUSTOMER);
    wallet.setBalance(new BigDecimal("-0.0001"));
    assertThrows(ConstraintViolationException.class, () -> entityManager.flush());
  }

  @Test
  void rejectsHoldsGreaterThanPostedBalance() {
    Wallet wallet = wallet(fixtureUser(), "USD", WalletAccountRole.CUSTOMER);
    wallet.setHeldBalance(new BigDecimal("1.0000"));
    assertThrows(ConstraintViolationException.class, () -> entityManager.flush());
  }

  @Test
  void permitsSignedClearingBalanceButRejectsSystemHolds() {
    Wallet wallet = wallet(fixtureUser(), "INR", WalletAccountRole.FX_CLEARING);
    wallet.setBalance(new BigDecimal("-8308.2500"));
    entityManager.flush();
    entityManager.clear();
    Wallet reloaded = wallets.findById(wallet.getId()).orElseThrow();
    assertEquals(new BigDecimal("-8308.2500"), reloaded.getBalance());
    reloaded.setHeldBalance(new BigDecimal("0.0001"));
    assertThrows(ConstraintViolationException.class, () -> entityManager.flush());
  }

  @Test
  void rejectsUnsupportedCurrency() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> wallet(fixtureUser(), "GBP", WalletAccountRole.CUSTOMER));
  }

  @Test
  void lockQueryAcquiresOracleRowLockAndReturnsCanonicalUuidOrder() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    // A committed fixture ensures blocking is caused by our lock query, not an uncommitted INSERT.
    try (Connection setup = M2SchemaOracleTest.connect()) {
      setup.setAutoCommit(false);
      try (var user =
          setup.prepareStatement(
              "INSERT INTO users(id,email,password_hash,full_name) "
                  + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','M2 lock fixture')")) {
        user.setString(1, owner.toString().replace("-", ""));
        user.setString(2, owner + "@m2.invalid");
        user.executeUpdate();
      }
      for (UUID id : List.of(first, second)) {
        try (var insert =
            setup.prepareStatement(
                "INSERT INTO wallets(id,user_id,currency,account_role) "
                    + "VALUES (HEXTORAW(?),HEXTORAW(?),?,'CUSTOMER')")) {
          insert.setString(1, id.toString().replace("-", ""));
          insert.setString(2, owner.toString().replace("-", ""));
          insert.setString(3, id.equals(first) ? "USD" : "INR");
          insert.executeUpdate();
        }
      }
      setup.commit();
    }
    List<UUID> expected =
        List.of(first, second).stream()
            .sorted(java.util.Comparator.comparing(UUID::toString))
            .toList();
    assertEquals(
        expected,
        wallets.findAllByIdForUpdate(List.of(second, first)).stream().map(Wallet::getId).toList());
    try (Connection competing = M2SchemaOracleTest.connect()) {
      competing.setAutoCommit(false);
      try (var statement =
          competing.prepareStatement(
              "SELECT id FROM wallets WHERE id=HEXTORAW(?) FOR UPDATE NOWAIT")) {
        statement.setString(1, first.toString().replace("-", ""));
        SQLException failure = assertThrows(SQLException.class, statement::executeQuery);
        assertEquals(54, failure.getErrorCode());
      } finally {
        competing.rollback();
      }
    }
    assertEquals(first, wallets.findByIdForUpdate(first).orElseThrow().getId());
  }

  @Test
  void paginatesOnlyRequestedWalletEntriesWithStableTimestampTieBreak() {
    UUID user = fixtureUser();
    Wallet wallet = wallet(user, "USD", WalletAccountRole.CUSTOMER);
    Wallet other = wallet(user, "INR", WalletAccountRole.CUSTOMER);
    Instant time = Instant.parse("2026-09-10T00:00:00Z");
    LedgerEntry first =
        entries.save(
            new LedgerEntry(
                wallet.getId(),
                "CREDIT",
                new BigDecimal("5.0000"),
                "USD",
                UUID.randomUUID().toString(),
                "JR-one",
                "demo",
                time));
    LedgerEntry second =
        entries.save(
            new LedgerEntry(
                wallet.getId(),
                "DEBIT",
                new BigDecimal("1.0000"),
                "USD",
                UUID.randomUUID().toString(),
                "JR-two",
                "convert",
                time));
    entries.save(
        new LedgerEntry(
            other.getId(),
            "CREDIT",
            new BigDecimal("1.0000"),
            "INR",
            UUID.randomUUID().toString(),
            null,
            null,
            time));
    entityManager.flush();
    entityManager.clear();
    var page =
        entries.findByWalletIdOrderByCreatedAtDescIdDesc(wallet.getId(), PageRequest.of(0, 1));
    assertEquals(2, page.getTotalElements());
    UUID expected =
        List.of(first.getId(), second.getId()).stream()
            .max(java.util.Comparator.comparing(UUID::toString))
            .orElseThrow();
    assertEquals(expected, page.getContent().get(0).getId());
    assertEquals(time, page.getContent().get(0).getCreatedAt());
    assertEquals(
        first.getId(),
        entries.findByIdempotencyKey(first.getIdempotencyKey()).orElseThrow().getId());
    assertEquals(
        new BigDecimal("5.0000"),
        entries.findByIdempotencyKey(first.getIdempotencyKey()).orElseThrow().getAmount());
  }

  @Test
  void rejectsDuplicateEntryIdempotencyKey() {
    Wallet wallet = wallet(fixtureUser(), "USD", WalletAccountRole.CUSTOMER);
    String key = UUID.randomUUID().toString();
    entries.save(
        new LedgerEntry(
            wallet.getId(), "CREDIT", BigDecimal.ONE, "USD", key, null, null, Instant.now()));
    entityManager.flush();
    entries.save(
        new LedgerEntry(
            wallet.getId(), "CREDIT", BigDecimal.ONE, "USD", key, null, null, Instant.now()));
    assertThrows(ConstraintViolationException.class, () -> entityManager.flush());
  }

  @Test
  void storesOperationPayloadAndCompletedResponse() {
    UUID user = fixtureUser();
    WalletOperation operation =
        operations.saveAndFlush(
            new WalletOperation(user, "CONVERT", "key", "{\"amount\":\"100.0000\"}", "JR-test"));
    operation.complete("{\"credited\":\"8308.2500\"}");
    entityManager.flush();
    entityManager.clear();
    WalletOperation found =
        operations.findByUserIdAndOperationTypeAndClientKey(user, "CONVERT", "key").orElseThrow();
    assertEquals("COMPLETED", found.getStatus());
    assertEquals("{\"credited\":\"8308.2500\"}", found.getResponseSnapshot());
    assertEquals("{\"amount\":\"100.0000\"}", found.getNormalizedRequest());
    assertEquals(operation.getId(), found.getId());
  }

  @Test
  void enforcesOperationKeyWithinUserAndOperationType() {
    UUID user = fixtureUser();
    operations.saveAndFlush(new WalletOperation(user, "CONVERT", "key", "{}", "JR-one"));
    operations.saveAndFlush(new WalletOperation(user, "RECEIVE_DEMO", "key", "{}", "JR-two"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            operations.saveAndFlush(new WalletOperation(user, "CONVERT", "key", "{}", "JR-three")));
  }

  @ParameterizedTest
  @CsvSource({"CREDIT, 0, USD", "DEBIT, -1, USD", "UNKNOWN, 1, USD", "CREDIT, 1, GBP"})
  void databaseRejectsInvalidLedgerValues(String type, String amount, String currency) {
    Wallet wallet = wallet(fixtureUser(), "USD", WalletAccountRole.CUSTOMER);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO ledger_entries(wallet_id,entry_type,amount,currency,idempotency_key) "
                    + "VALUES (HEXTORAW(?),?,?,?,?)",
                wallet.getId().toString().replace("-", ""),
                type,
                new BigDecimal(amount),
                currency,
                UUID.randomUUID().toString()));
  }

  @Test
  void databaseRejectsMissingWalletOwner() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> wallet(UUID.randomUUID(), "USD", WalletAccountRole.CUSTOMER));
  }

  @Test
  void databaseRejectsCompletedOperationWithoutResponse() {
    UUID user = fixtureUser();
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO wallet_operations(user_id,operation_type,client_key,normalized_request,"
                    + "journal_reference,status) VALUES (HEXTORAW(?),'CONVERT','key','{}','JR-test','COMPLETED')",
                user.toString().replace("-", "")));
  }

  @Test
  void operationCannotOverwriteItsCompletedResponse() {
    WalletOperation operation =
        new WalletOperation(fixtureUser(), "CONVERT", "key", "{}", "JR-test");
    assertThrows(IllegalArgumentException.class, () -> operation.complete(" "));
    operation.complete("{\"ok\":true}");
    assertThrows(IllegalStateException.class, () -> operation.complete("{\"ok\":false}"));
    assertEquals("{\"ok\":true}", operation.getResponseSnapshot());
  }
}
