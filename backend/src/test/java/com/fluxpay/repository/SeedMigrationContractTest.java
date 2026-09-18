package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SeedMigrationContractTest {
  @Test
  void seedsCustomerWalletsWithAllRequiredWalletColumns() throws IOException {
    String sql = resource("V603__m5_seed_data.sql");

    assertThat(sql)
        .contains("INSERT INTO wallets (id, user_id, currency, account_role, balance, created_at)");
    assertThat(sql).contains("'CUSTOMER'");
  }

  @Test
  void seedsPaymentsUsingLifecycleStatusesAllowedByThePaymentsTable() throws IOException {
    String sql = resource("V603__m5_seed_data.sql");

    assertThat(sql).doesNotContain("'SCREENING'");
    assertThat(sql).contains("'UNDER_REVIEW'");
  }

  @Test
  void seedsAnActiveSyntheticSanctionsRecipientWithoutEnablingSimulation() throws IOException {
    String sql = resource("V607__simulation_seed.sql");

    assertThat(sql)
        .contains("INSERT INTO recipients")
        .contains("00000000000000000000000000005C04")
        .contains("00000000000000000000000000005A01")
        .contains("'SANCTIONED_ACME'")
        .contains("'ACTIVE'")
        .contains("'USD'");
    assertThat(sql)
        .doesNotContain("simulated-compliance-enabled=true")
        .doesNotContain("simulated-payouts-enabled=true");
  }

  private String resource(String name) throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
      assertThat(input).as(name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
  }
}
