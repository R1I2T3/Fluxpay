package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in: never migrate or reset the application's development schema. */
@EnabledIfEnvironmentVariable(named = "M2_ORACLE_TESTS", matches = "true")
class M2SchemaOracleTest {
  static Connection connect() throws Exception {
    String username = System.getenv("ORACLE_USERNAME");
    if (!"FLUXPAY_M2_TEST".equalsIgnoreCase(username)) {
      throw new IllegalStateException("Oracle tests require the dedicated FLUXPAY_M2_TEST schema");
    }
    return DriverManager.getConnection(
        System.getenv("ORACLE_JDBC_URL"), username, System.getenv("ORACLE_PASSWORD"));
  }

  @BeforeAll
  static void migrateIsolatedSchema() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery("SELECT USER FROM dual")) {
        result.next();
        assertEquals("FLUXPAY_M2_TEST", result.getString(1));
      }
    }
    Flyway.configure()
        .dataSource(
            System.getenv("ORACLE_JDBC_URL"),
            System.getenv("ORACLE_USERNAME"),
            System.getenv("ORACLE_PASSWORD"))
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        // This dedicated schema may already be at V501; V202 is a reviewed additive migration.
        .outOfOrder(true)
        .load()
        .migrate();
  }

  @Test
  void addsWalletRoleHoldsAndVersionToExistingWalletTable() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM user_tab_columns WHERE table_name = 'WALLETS' "
                    + "AND column_name IN ('ACCOUNT_ROLE', 'HELD_BALANCE', 'VERSION')")) {
      result.next();
      assertEquals(3, result.getInt(1));
    }
  }

  @Test
  void createsOperationTrackingTable() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM user_tables WHERE table_name = 'WALLET_OPERATIONS'")) {
      result.next();
      assertEquals(1, result.getInt(1));
    }
  }
}
