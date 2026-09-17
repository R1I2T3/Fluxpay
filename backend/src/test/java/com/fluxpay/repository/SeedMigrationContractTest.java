package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SeedMigrationContractTest {
  @Test
  void seedsCustomerWalletsWithAllRequiredWalletColumns() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V603__m5_seed_data.sql")) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

      assertThat(sql)
          .contains(
              "INSERT INTO wallets (id, user_id, currency, account_role, balance, created_at)");
      assertThat(sql).contains("'CUSTOMER'");
    }
  }

  @Test
  void seedsPaymentsUsingLifecycleStatusesAllowedByThePaymentsTable() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V603__m5_seed_data.sql")) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

      assertThat(sql).doesNotContain("'SCREENING'");
      assertThat(sql).contains("'UNDER_REVIEW'");
    }
  }
}
