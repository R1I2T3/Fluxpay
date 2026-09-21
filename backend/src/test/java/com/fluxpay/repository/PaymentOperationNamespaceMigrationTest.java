package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentOperationNamespaceMigrationTest {
  @Test
  void upgradePreservesPublicKeysAndInternalReplayWhileSeparatingUniqueness() throws Exception {
    String url = "jdbc:h2:mem:namespace-" + UUID.randomUUID() + ";MODE=Oracle";
    try (var connection = DriverManager.getConnection(url, "sa", "");
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE payment_operations (id VARCHAR2(36) PRIMARY KEY, "
              + "user_id VARCHAR2(36) NOT NULL, operation_type VARCHAR2(20) NOT NULL, "
              + "client_key VARCHAR2(255) NOT NULL, response_data CLOB, "
              + "CONSTRAINT uq_payment_operation_key UNIQUE (user_id, client_key))");
      for (String kind : new String[] {"schedule", "retry", "refund"}) {
        statement.execute(
            "INSERT INTO payment_operations VALUES ('public-"
                + kind
                + "', 'customer', 'SUBMIT', 'auto:"
                + kind
                + ":payment:1', '{\"eventId\":\"original\"}')");
        statement.execute(
            "INSERT INTO payment_operations VALUES ('internal-"
                + kind
                + "', 'customer', 'AUTO_"
                + kind.toUpperCase(java.util.Locale.ROOT)
                + "', 'auto:"
                + kind
                + ":other:1', '{\"eventId\":\"internal\"}')");
      }
      var upgrade =
          org.flywaydb.core.Flyway.configure()
              .dataSource(url, "sa", "")
              .locations("classpath:db/migration")
              .baselineOnMigrate(true)
              .baselineVersion("607")
              .target("608")
              .load()
              .migrate();
      assertThat(upgrade.migrationsExecuted).isEqualTo(1);
      for (String kind : new String[] {"schedule", "retry", "refund"}) {
        try (var rows =
            statement.executeQuery(
                "SELECT identity_namespace, client_key, response_data "
                    + "FROM payment_operations WHERE id='public-"
                    + kind
                    + "'")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("PUBLIC");
          assertThat(rows.getString(2)).isEqualTo("auto:" + kind + ":payment:1");
          assertThat(rows.getString(3)).isEqualTo("{\"eventId\":\"original\"}");
        }
        try (var rows =
            statement.executeQuery(
                "SELECT identity_namespace, response_data "
                    + "FROM payment_operations WHERE id='internal-"
                    + kind
                    + "'")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("INTERNAL");
          assertThat(rows.getString(2)).isEqualTo("{\"eventId\":\"internal\"}");
        }
        String insert =
            "INSERT INTO payment_operations (id, user_id, operation_type, client_key, identity_namespace) VALUES ";
        String identity = "'customer', 'AUTO_RETRY', 'auto:" + kind + ":payment:1', ";
        statement.execute(insert + "('new-" + kind + "', " + identity + "'INTERNAL')");
        for (String namespace : new String[] {"PUBLIC", "INTERNAL", "INVALID"}) {
          assertThatThrownBy(
                  () ->
                      statement.execute(
                          insert + "('duplicate', " + identity + "'" + namespace + "')"))
              .isInstanceOf(SQLException.class);
        }
        assertThatThrownBy(() -> statement.execute(insert + "('null', " + identity + "NULL)"))
            .isInstanceOf(SQLException.class);
      }
    }
  }
}
