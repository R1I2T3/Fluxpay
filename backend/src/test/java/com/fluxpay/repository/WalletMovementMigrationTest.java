package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WalletMovementMigrationTest {
  @Test
  void forwardMigrationPreservesOldOperationsAndPermitsOnlySupportedActions() {
    var datasource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:movement_migration;MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(datasource);
    jdbc.execute(
        "CREATE TABLE wallet_operations(operation_type VARCHAR2(20) NOT NULL, CONSTRAINT chk_wallet_operation_type CHECK(operation_type IN ('RECEIVE_DEMO', 'CONVERT')))");
    jdbc.update("INSERT INTO wallet_operations VALUES ('RECEIVE_DEMO'), ('CONVERT')");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V606__wallet_transfer_bank_operations.sql"))
        .execute(datasource);
    for (String type : List.of("TRANSFER", "WITHDRAW", "BANK_LINK", "BANK_TOPUP"))
      jdbc.update("INSERT INTO wallet_operations VALUES (?)", type);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_operations", Integer.class))
        .isEqualTo(6);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO wallet_operations VALUES ('UNKNOWN')"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
