package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class MigrationUpgradeTest {
  @Test
  void upgradesLegacyRowsWithoutLosingIdsRoutesOrAttemptOrder() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE users(id RAW(16) PRIMARY KEY)");
    jdbc.execute("CREATE TABLE wallets(id RAW(16) PRIMARY KEY)");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V301__payment_routing.sql"),
            new ClassPathResource("db/migration/V401__compliance_policy.sql"))
        .execute(source);
    String id = "00112233445566778899AABBCCDDEEFF";
    jdbc.execute("INSERT INTO users VALUES (HEXTORAW('" + id + "'))");
    jdbc.execute("INSERT INTO wallets VALUES (HEXTORAW('" + id + "'))");
    jdbc.execute(
        "INSERT INTO recipients(id, user_id, name, account_ref) VALUES (HEXTORAW('"
            + id
            + "'), HEXTORAW('"
            + id
            + "'), 'Recipient', 'account')");
    jdbc.execute(
        "INSERT INTO payments(id, sender_wallet_id, recipient_id, amount, currency) VALUES (HEXTORAW('"
            + id
            + "'), HEXTORAW('"
            + id
            + "'), HEXTORAW('"
            + id
            + "'), 100, 'USD')");
    jdbc.execute(
        "INSERT INTO payout_routes(code, name) VALUES ('STANDARD_BANK', 'Existing custom rail')");
    String[] statuses = {"PENDING", "SUBMITTED", "FAILED", "COMPLETED"};
    for (int i = 0; i < statuses.length; i++) {
      jdbc.update(
          "INSERT INTO payout_attempts(payment_id, route_code, status, created_at) "
              + "VALUES (HEXTORAW(?), 'SWIFT_STANDARD', ?, ?)",
          id,
          statuses[i],
          java.sql.Timestamp.valueOf("2026-01-0" + (i + 1) + " 00:00:00"));
    }
    String forward =
        new ClassPathResource("db/migration/V503__m4_routes_attempts_upgrade.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    // H2 lacks Oracle's FROM_TZ/DBTIMEZONE. Adapt only timestamp conversion for this data test;
    // validation on Oracle is still required to certify the full dialect and Flyway upgrade.
    forward =
        forward
            .replace(
                "FROM_TZ(created_at, DBTIMEZONE)", "CAST(created_at AS TIMESTAMP WITH TIME ZONE)")
            .replace(
                "FROM_TZ(a.created_at, DBTIMEZONE)",
                "CAST(a.created_at AS TIMESTAMP WITH TIME ZONE)");
    new ResourceDatabasePopulator(new ByteArrayResource(forward.getBytes(StandardCharsets.UTF_8)))
        .execute(source);

    assertThat(
            jdbc.queryForList(
                "SELECT status FROM payout_attempts ORDER BY attempt_number", String.class))
        .containsExactly("INITIATED", "PROCESSING", "FAILED", "COMPLETED");
    assertThat(jdbc.queryForList("SELECT DISTINCT payment_id FROM payout_attempts", String.class))
        .containsExactly("00112233-4455-6677-8899-aabbccddeeff");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payout_attempts a JOIN payout_attempts_legacy l ON a.id = l.id",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payout_attempts a JOIN payout_routes r ON a.payout_route_id = r.id WHERE r.route_code = 'SWIFT_STANDARD'",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT active FROM payout_routes WHERE route_code = 'STANDARD_BANK'",
                Integer.class))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM policies", Integer.class)).isEqualTo(5);
  }
}
