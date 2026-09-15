package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class M5MigrationLayoutTest {
  private static final Path MIGRATIONS =
      Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Test
  void resolvesTheCompleteMigrationChainInDependencyOrderWithoutDuplicateVersions() {
    // Resolve the source directory: target/classes can retain files from before a rename.
    // info() checks the actual migration inventory without running Oracle DDL on H2.
    var flyway =
        Flyway.configure()
            .dataSource(databaseUrl(), "sa", "")
            .locations("filesystem:" + MIGRATIONS)
            .load();

    MigrationInfo[] migrations = assertDoesNotThrow(() -> flyway.info().all());

    assertThat(migrations)
        .extracting(MigrationInfo::getScript)
        .containsExactly(
            "V001__00_common_extensions.sql",
            "V101__auth_users.sql",
            "V201__wallet_ledger.sql",
            "V202__m2_wallet_ledger_extensions.sql",
            "V301__payment_routing.sql",
            "V401__compliance_policy.sql",
            "V501__event_vector.sql",
            "V502__m1_auth_kyc_extensions.sql",
            "V503__m4_routes_attempts_upgrade.sql",
            "V504__m4_payment_events.sql",
            "V601__m3_payment_extensions.sql",
            "V602__m3_payment_quotes.sql",
            "V603__m3_operations_outbox.sql",
            "V701__m5_compliance_cases.sql",
            "V702__m5_policy_documents_chunks.sql",
            "V703__m5_seed_data.sql",
            "V704__m5_rich_policy_corpus.sql",
            "V705__m5_authoritative_screening.sql",
            "V706__m5_vector_generations.sql",
            "V707__m3_m5_review_binding.sql",
            "V708__m5_current_policy_revisions.sql");
  }

  @ParameterizedTest(name = "wallet fixture {0} supplies its required customer role")
  @MethodSource("walletSeedStatements")
  void insertsCustomerWalletFixturesIntoTheRequiredRoleSchema(int fixture, String walletInsert)
      throws Exception {
    try (var connection = DriverManager.getConnection(databaseUrl(), "sa", "");
        var statement = connection.createStatement()) {
      // The wallet shape and constraints established by V201 and V202, without Oracle PL/SQL.
      statement.execute("CREATE TABLE users (id RAW(16) PRIMARY KEY)");
      statement.execute(
          """
          INSERT INTO users (id) VALUES
            (HEXTORAW('00000000000000000000000000005A01')),
            (HEXTORAW('00000000000000000000000000005A02')),
            (HEXTORAW('00000000000000000000000000005A03'))
          """);
      statement.execute(
          """
          CREATE TABLE wallets (
            id RAW(16) PRIMARY KEY,
            user_id RAW(16) NOT NULL REFERENCES users(id),
            currency VARCHAR2(3) NOT NULL CHECK (currency IN ('USD', 'EUR', 'INR')),
            balance NUMBER(19,4) DEFAULT 0 NOT NULL,
            created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
            account_role VARCHAR2(20) NOT NULL CHECK
              (account_role IN ('CUSTOMER', 'FX_CLEARING', 'DEMO_CLEARING',
                               'PAYOUT_CLEARING', 'FEE_REVENUE')),
            held_balance NUMBER(19,4) DEFAULT 0 NOT NULL,
            version NUMBER(10) DEFAULT 0 NOT NULL CHECK (version >= 0),
            CHECK ((account_role = 'CUSTOMER' AND balance >= held_balance AND held_balance >= 0)
                   OR (account_role <> 'CUSTOMER' AND held_balance = 0)),
            UNIQUE (user_id, currency, account_role)
          )
          """);

      assertDoesNotThrow(() -> statement.executeUpdate(walletInsert));

      try (var rows = statement.executeQuery("SELECT account_role FROM wallets")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("account_role")).isEqualTo("CUSTOMER");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  private static Stream<Arguments> walletSeedStatements() throws IOException {
    Path seed;
    try (var paths = Files.list(MIGRATIONS)) {
      // Locate by suffix so the role regression is exercised both before and after renumbering.
      var seeds =
          paths.filter(path -> path.getFileName().toString().endsWith("__m5_seed_data.sql"))
              .toList();
      assertThat(seeds).hasSize(1);
      seed = seeds.get(0);
    }
    var inserts =
        Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+wallets\\s*\\([^;]+;")
            .matcher(Files.readString(seed))
            .results()
            .map(MatchResult::group)
            .toList();
    assertThat(inserts).hasSize(3);
    return IntStream.range(0, inserts.size())
        .mapToObj(index -> Arguments.of(index + 1, inserts.get(index)));
  }

  private static String databaseUrl() {
    return "jdbc:h2:mem:m5_migration_" + UUID.randomUUID() + ";MODE=Oracle";
  }
}
