package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** A second isolated schema stays at V201 so legacy preflight checks are repeatable. */
@EnabledIfEnvironmentVariable(named = "M2_ORACLE_TESTS", matches = "true")
class M2LegacyMigrationOracleTest {
  private Connection connection;
  private static String preflight;

  private static Connection connect() throws Exception {
    if (!"FLUXPAY_M2_TEST".equalsIgnoreCase(System.getenv("ORACLE_USERNAME"))) {
      throw new IllegalStateException("Explicit isolated Oracle test configuration is required");
    }
    return DriverManager.getConnection(
        System.getenv("ORACLE_JDBC_URL"),
        "FLUXPAY_M2_LEGACY_TEST",
        System.getenv("ORACLE_PASSWORD"));
  }

  @BeforeAll
  static void prepareLegacySchema() throws Exception {
    try (Connection check = connect();
        var statement = check.createStatement();
        var rows = statement.executeQuery("SELECT USER FROM dual")) {
      rows.next();
      assertEquals("FLUXPAY_M2_LEGACY_TEST", rows.getString(1));
    }
    Flyway.configure()
        .dataSource(
            System.getenv("ORACLE_JDBC_URL"),
            "FLUXPAY_M2_LEGACY_TEST",
            System.getenv("ORACLE_PASSWORD"))
        .locations("classpath:db/migration")
        .target("201")
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .load()
        .migrate();
    try (Connection setup = connect();
        var statement = setup.createStatement()) {
      statement.execute(
          "DECLARE n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM user_tab_columns "
              + "WHERE table_name='WALLETS' AND column_name='ACCOUNT_ROLE'; IF n=0 THEN "
              + "EXECUTE IMMEDIATE 'ALTER TABLE wallets ADD (account_role VARCHAR2(20))'; END IF; END;");
    }
    try (var source =
        M2LegacyMigrationOracleTest.class.getResourceAsStream(
            "/db/migration/V202__m2_wallet_ledger_extensions.sql")) {
      assertNotNull(source);
      // Execute the actual migration's pre-DDL validation block, not a copied test version.
      preflight =
          new String(source.readAllBytes(), StandardCharsets.UTF_8).split("(?m)^/\\s*$", 2)[0];
    }
  }

  @BeforeEach
  void begin() throws Exception {
    connection = connect();
    connection.setAutoCommit(false);
  }

  @AfterEach
  void rollbackFixtures() throws Exception {
    if (connection != null) {
      try {
        connection.rollback();
      } finally {
        connection.close();
      }
    }
  }

  private void update(String sql, Object... values) throws Exception {
    try (var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    }
  }

  private UUID user() throws Exception {
    UUID id = UUID.randomUUID();
    update(
        "INSERT INTO users(id,email,password_hash,full_name) "
            + "VALUES (HEXTORAW(?),?,'!M2_TEST_NO_LOGIN!','Legacy fixture')",
        hex(id),
        id + "@m2.invalid");
    return id;
  }

  private UUID wallet(UUID owner, String role, String currency, String balance) throws Exception {
    UUID id = UUID.randomUUID();
    update(
        "INSERT INTO wallets(id,user_id,currency,balance,account_role) "
            + "VALUES (HEXTORAW(?),HEXTORAW(?),?,?,?)",
        hex(id),
        hex(owner),
        currency,
        new BigDecimal(balance),
        role);
    return id;
  }

  private static String hex(UUID id) {
    return id.toString().replace("-", "");
  }

  private void validateLegacyData() throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(preflight);
    }
  }

  @Test
  void refusesToGuessUnclassifiedWalletRole() throws Exception {
    wallet(user(), null, "USD", "10");
    assertEquals(20202, assertThrows(SQLException.class, this::validateLegacyData).getErrorCode());
  }

  @Test
  void rejectsNegativeCustomerBalance() throws Exception {
    wallet(user(), "CUSTOMER", "USD", "-10");
    assertEquals(20203, assertThrows(SQLException.class, this::validateLegacyData).getErrorCode());
  }

  @Test
  void permitsExplicitlyClassifiedNegativeClearingBalance() throws Exception {
    wallet(user(), "FX_CLEARING", "INR", "-8308.25");
    assertDoesNotThrow(this::validateLegacyData);
  }

  @Test
  void rejectsDuplicateFutureWalletKeys() throws Exception {
    UUID owner = user();
    wallet(owner, "CUSTOMER", "USD", "10");
    wallet(owner, "CUSTOMER", "USD", "20");
    assertEquals(20204, assertThrows(SQLException.class, this::validateLegacyData).getErrorCode());
  }

  @Test
  void rejectsUnsupportedLegacyCurrency() throws Exception {
    wallet(user(), "CUSTOMER", "GBP", "10");
    assertEquals(20205, assertThrows(SQLException.class, this::validateLegacyData).getErrorCode());
  }

  @Test
  void rejectsInvalidLegacyLedgerWithoutChangingHistory() throws Exception {
    UUID id = wallet(user(), "CUSTOMER", "USD", "10");
    update(
        "INSERT INTO ledger_entries(wallet_id,entry_type,amount,currency,idempotency_key) "
            + "VALUES (HEXTORAW(?),'CREDIT',-1,'USD',?)",
        hex(id),
        UUID.randomUUID().toString());
    assertEquals(20207, assertThrows(SQLException.class, this::validateLegacyData).getErrorCode());
    try (var statement =
        connection.prepareStatement(
            "SELECT amount FROM ledger_entries WHERE wallet_id=HEXTORAW(?)")) {
      statement.setString(1, hex(id));
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
        assertEquals(0, new BigDecimal("-1").compareTo(rows.getBigDecimal(1)));
      }
    }
  }
}
