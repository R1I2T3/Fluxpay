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

  @Test
  void seedsTransferProvidersWithInsertOnlyMerges() throws IOException {
    String sql = resource("V603__m5_seed_data.sql");

    assertThat(sql)
        .contains("MERGE INTO transfer_providers")
        .contains("WHEN NOT MATCHED THEN INSERT")
        .doesNotContain("WHEN MATCHED")
        .contains("'FLUXPAY'")
        .contains("'BANK_ALPHA'")
        .contains("'REAL_TIME'")
        .contains("'PARTNER'")
        .contains("'INTERNAL_LEDGER'")
        .contains("'BANK_NETWORK'")
        .contains("'REAL_TIME_NETWORK'")
        .contains("'PARTNER_NETWORK'");
  }

  @Test
  void seedsTransferRoutesWithInsertOnlyMerges() throws IOException {
    String sql = resource("V603__m5_seed_data.sql");

    assertThat(sql)
        .contains("MERGE INTO transfer_routes")
        .contains("WHEN NOT MATCHED THEN INSERT")
        .contains("'FLUXPAY_INTERNAL'")
        .contains("'BANK_STANDARD'")
        .contains("'BANK_EXPRESS'")
        .contains("'REALTIME_INR'")
        .contains("'PARTNER_INR'")
        .contains("'INTERNAL_WALLET'")
        .contains("'EXTERNAL_ACCOUNT'")
        .contains("'IN'")
        .contains("'INR'");
  }

  @Test
  void seedMigrationHasNoLegacyRouteLiterals() throws IOException {
    String sql = resource("V603__m5_seed_data.sql");

    assertThat(sql)
        .doesNotContain("STANDARD_BANK")
        .doesNotContain("INSTANT_PAYOUT")
        .doesNotContain("LOCAL_PARTNER")
        .doesNotContain("payout_routes")
        .doesNotContain("PayoutRoute");
  }

  @Test
  void seedScriptManagesCatalogueWithInsertOnlyMergesAndNoLegacyRoutes() throws IOException {
    String script = seedScript();

    assertThat(script)
        .contains("MERGE INTO transfer_providers")
        .contains("MERGE INTO transfer_routes")
        .contains("WHEN NOT MATCHED THEN INSERT")
        .doesNotContain("WHEN MATCHED THEN")
        .contains("FLUXPAY")
        .contains("BANK_ALPHA")
        .contains("REAL_TIME")
        .contains("PARTNER")
        .contains("FLUXPAY_INTERNAL")
        .contains("BANK_STANDARD")
        .doesNotContain("STANDARD_BANK")
        .doesNotContain("INSTANT_PAYOUT")
        .doesNotContain("LOCAL_PARTNER")
        .doesNotContain("payout_routes")
        .doesNotContain("PayoutRoute");
  }

  private String resource(String name) throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
      assertThat(input).as(name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
  }

  private String seedScript() throws IOException {
    java.nio.file.Path script =
        java.nio.file.Path.of(System.getProperty("user.dir"), "..", "scripts", "seed-local.py")
            .normalize();
    assertThat(java.nio.file.Files.isRegularFile(script)).as(script.toString()).isTrue();
    return java.nio.file.Files.readString(script, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
  }
}
