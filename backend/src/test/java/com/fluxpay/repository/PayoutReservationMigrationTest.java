package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutReservationMigrationTest {
  @Test
  void upgradePreservesOldOperationsAndValidatesNewSnapshots() throws Exception {
    String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=Oracle";
    try (var connection = DriverManager.getConnection(url, "sa", "")) {
      var statement = connection.createStatement();
      statement.execute("CREATE TABLE payment_operations (id VARCHAR2(36) PRIMARY KEY)");
      statement.execute("INSERT INTO payment_operations (id) VALUES ('old')");
      var upgrade =
          org.flywaydb.core.Flyway.configure()
              .dataSource(url, "sa", "")
              .locations("classpath:db/migration")
              .baselineOnMigrate(true)
              .baselineVersion("605")
              .load()
              .migrate();
      assertThat(upgrade.migrationsExecuted)
          .as("Snapshot upgrade must run after installed V605")
          .isEqualTo(1);
      try (var rows =
          statement.executeQuery(
              "SELECT payout_reservation FROM payment_operations WHERE id='old'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isNull();
      }
      assertThatCode(
              () ->
                  statement.execute(
                      "INSERT INTO payment_operations VALUES ('new', '{\"attemptId\":\"persisted\"}')"))
          .doesNotThrowAnyException();
      assertThatThrownBy(
              () ->
                  statement.execute(
                      "INSERT INTO payment_operations VALUES ('invalid', 'not-json')"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }
}
