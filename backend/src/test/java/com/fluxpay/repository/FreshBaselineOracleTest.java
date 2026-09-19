package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Fresh V001..V005 baseline gate. Runs only against the isolated FLUXPAY_TEST schema; it never
 * migrates or resets the application development schema.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS_ACTIVE", matches = "true")
class FreshBaselineOracleTest {
  static Connection connect() throws Exception {
    String username = System.getenv("ORACLE_TEST_USERNAME");
    if (!"FLUXPAY_TEST".equalsIgnoreCase(username)) {
      throw new IllegalStateException("Oracle tests require the dedicated FLUXPAY_TEST schema");
    }
    return DriverManager.getConnection(
        System.getenv("ORACLE_TEST_JDBC_URL"), username, System.getenv("ORACLE_TEST_PASSWORD"));
  }

  static void migrateIsolatedSchema() throws Exception {
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      try (ResultSet result = statement.executeQuery("SELECT USER FROM dual")) {
        result.next();
        assertEquals("FLUXPAY_TEST", result.getString(1));
      }
    }
    Flyway.configure()
        .dataSource(
            System.getenv("ORACLE_TEST_JDBC_URL"),
            System.getenv("ORACLE_TEST_USERNAME"),
            System.getenv("ORACLE_TEST_PASSWORD"))
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .outOfOrder(true)
        .load()
        .migrate();
  }

  @Test
  void freshMigrationCreatesBusinessTables() throws Exception {
    migrateIsolatedSchema();
    String[] tables = {
      "USERS",
      "USER_ROLES",
      "REFRESH_TOKENS",
      "KYC_CASES",
      "KYC_DOCUMENTS",
      "WALLETS",
      "LEDGER_ENTRIES",
      "WALLET_OPERATIONS",
      "TRANSFER_PROVIDERS",
      "TRANSFER_ROUTES",
      "TRANSFER_ROUTE_OUTCOMES",
      "RECIPIENTS",
      "PAYMENTS",
      "PAYMENT_QUOTES",
      "PAYOUT_ATTEMPTS",
      "PAYMENT_OPERATIONS",
      "REVIEW_DECISIONS",
      "OUTBOX_EVENTS",
      "OUTBOX_DELIVERY",
      "PAYMENT_EVENTS",
      "SCREENING_CASES",
      "POLICIES",
      "POLICY_DECISIONS",
      "DOCUMENT_EMBEDDINGS"
    };
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      for (String table : tables) {
        try (ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM user_tables WHERE table_name = '" + table + "'")) {
          result.next();
          assertEquals(1, result.getInt(1), table);
        }
      }
      try (ResultSet result =
          statement.executeQuery(
              "SELECT COUNT(*) FROM user_tables WHERE table_name = 'PAYOUT_ROUTES'")) {
        result.next();
        assertEquals(0, result.getInt(1), "PAYOUT_ROUTES");
      }
    }
  }

  @Test
  void freshMigrationIncludesSyntheticSanctionsRecipient() throws Exception {
    migrateIsolatedSchema();
    try (Connection connection = connect();
        var statement =
            connection.prepareStatement(
                "SELECT name, status, profile_complete, currency FROM recipients "
                    + "WHERE id=HEXTORAW('00000000000000000000000000005C04')");
        ResultSet result = statement.executeQuery()) {
      assertEquals(true, result.next());
      assertEquals("SANCTIONED_ACME", result.getString("name"));
      assertEquals("ACTIVE", result.getString("status"));
      assertEquals(1, result.getInt("profile_complete"));
      assertEquals("USD", result.getString("currency"));
    }
  }

  @Test
  void repeatStartupAppliesNoNewMigrations() throws Exception {
    migrateIsolatedSchema();
    var second =
        Flyway.configure()
            .dataSource(
                System.getenv("ORACLE_TEST_JDBC_URL"),
                System.getenv("ORACLE_TEST_USERNAME"),
                System.getenv("ORACLE_TEST_PASSWORD"))
            .locations("classpath:db/migration")
            .cleanDisabled(true)
            .baselineOnMigrate(false)
            .outOfOrder(true)
            .load()
            .migrate();
    assertEquals(0, second.migrationsExecuted);
  }

  @Test
  void baselineCarriesNoMemberPrefixedTables() throws Exception {
    migrateIsolatedSchema();
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT COUNT(*) FROM user_tables WHERE table_name LIKE 'M1\\_%' ESCAPE '\\'"
                    + " OR table_name LIKE 'M2\\_%' ESCAPE '\\'"
                    + " OR table_name LIKE 'M3\\_%' ESCAPE '\\'"
                    + " OR table_name LIKE 'M4\\_%' ESCAPE '\\'")) {
      result.next();
      assertEquals(0, result.getInt(1));
    }
  }

  @Test
  void paymentOperationStatesAreEnforced() throws Exception {
    migrateIsolatedSchema();
    String user = UUID.randomUUID().toString().replace("-", "");
    String email = "payment-op-" + UUID.randomUUID() + "@oracle-test.invalid";
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users(id,email,password_hash,full_name) VALUES (HEXTORAW('"
              + user
              + "'),'"
              + email
              + "','!ORACLE_TEST_NO_LOGIN!','Fresh fixture')");
    }
    JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connect(), false));
    String pendingKey = "pending-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payment_operations(id,user_id,operation_type,client_key,normalized_request,"
            + "status,created_at) VALUES (HEXTORAW(?),HEXTORAW(?),'CANCEL',?,'{}','IN_PROGRESS',"
            + "SYSTIMESTAMP)",
        UUID.randomUUID().toString().replace("-", ""),
        user,
        pendingKey);
    assertEquals(
        Integer.valueOf(1),
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM payment_operations WHERE client_key=? AND status='IN_PROGRESS'"
                + " AND outcome_status IS NULL AND response_data IS NULL",
            Integer.class,
            pendingKey));

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO payment_operations(id,user_id,operation_type,client_key,"
                    + "normalized_request,status,created_at) VALUES (HEXTORAW(?),HEXTORAW(?),"
                    + "'CANCEL',?,'{}','COMPLETED',SYSTIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""),
                user,
                "completed-without-fields-" + UUID.randomUUID()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO payment_operations(id,user_id,operation_type,client_key,"
                    + "normalized_request,outcome_status,status,response_data,created_at) "
                    + "VALUES (HEXTORAW(?),HEXTORAW(?),'CANCEL',?,'{}',99,'COMPLETED','{}',"
                    + "SYSTIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""),
                user,
                "bad-outcome-" + UUID.randomUUID()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO payment_operations(id,user_id,operation_type,client_key,"
                    + "normalized_request,status,response_data,created_at) VALUES (HEXTORAW(?),"
                    + "HEXTORAW(?),'CANCEL',?,'not-json','IN_PROGRESS',NULL,SYSTIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""),
                user,
                "invalid-request-" + UUID.randomUUID()));

    String duplicateKey = "duplicate-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO payment_operations(id,user_id,operation_type,client_key,normalized_request,"
            + "status,created_at) VALUES (HEXTORAW(?),HEXTORAW(?),'CANCEL',?,'{}','IN_PROGRESS',"
            + "SYSTIMESTAMP)",
        UUID.randomUUID().toString().replace("-", ""),
        user,
        duplicateKey);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO payment_operations(id,user_id,operation_type,client_key,"
                    + "normalized_request,status,created_at) VALUES (HEXTORAW(?),HEXTORAW(?),"
                    + "'SUBMIT',?,'{}','IN_PROGRESS',SYSTIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""),
                user,
                duplicateKey));
  }

  @Test
  void quotePersistenceEnforcesRealRouteCodeReference() throws Exception {
    migrateIsolatedSchema();
    JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connect(), false));
    String user = UUID.randomUUID().toString().replace("-", "");
    String payment = UUID.randomUUID().toString().replace("-", "");
    String wallet = UUID.randomUUID().toString().replace("-", "");
    String recipient = UUID.randomUUID().toString().replace("-", "");
    String provider = UUID.randomUUID().toString().replace("-", "");
    String route = UUID.randomUUID().toString().replace("-", "");
    String routeCode = "RT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,full_name) VALUES (HEXTORAW(?),"
            + "?,'!ORACLE_TEST_NO_LOGIN!','Quote fixture')",
        user,
        "quote-" + UUID.randomUUID() + "@oracle-test.invalid");
    jdbc.update(
        "INSERT INTO wallets(id,user_id,currency,account_role) VALUES (HEXTORAW(?),HEXTORAW(?),"
            + "'USD','CUSTOMER')",
        wallet,
        user);
    jdbc.update(
        "INSERT INTO recipients(id,user_id,name,account_ref) VALUES (HEXTORAW(?),HEXTORAW(?),"
            + "'Recipient','ACC')",
        recipient,
        user);
    jdbc.update(
        "INSERT INTO payments(id,sender_wallet_id,recipient_id,amount,currency) "
            + "VALUES (HEXTORAW(?),HEXTORAW(?),HEXTORAW(?),100,'USD')",
        payment,
        wallet,
        recipient);
    jdbc.update(
        "INSERT INTO transfer_providers(id,provider_code,provider_name,rail_type,active,"
            + "system_protected,created_at,updated_at) VALUES (HEXTORAW(?),'QUOTE_PROVIDER',"
            + "'Provider','BANK_NETWORK',1,0,SYSTIMESTAMP,SYSTIMESTAMP)",
        provider);
    jdbc.update(
        "INSERT INTO transfer_routes(id,provider_id,route_code,route_name,destination_type,"
            + "destination_country,payout_currency,base_fee,fx_spread_percentage,estimated_minutes,"
            + "configured_success_rate,active,system_protected,created_at,updated_at) VALUES "
            + "(HEXTORAW(?),HEXTORAW(?),?,'Route','EXTERNAL_ACCOUNT','US','USD',0,0,60,99,1,0,"
            + "SYSTIMESTAMP,SYSTIMESTAMP)",
        route,
        provider,
        routeCode);
    jdbc.update(
        "INSERT INTO payment_quotes(id,payment_id,generation,route,market_rate,spread_percent,"
            + "offered_rate,fee_amount,recipient_amount,estimated_minutes,recommended,"
            + "policy_version,created_at,expires_at) VALUES (HEXTORAW(?),HEXTORAW(?),1,?,83.5,0.5,"
            + "83.1,0.5,8308,240,1,'source-fee-v1',SYSTIMESTAMP,SYSTIMESTAMP)",
        UUID.randomUUID().toString().replace("-", ""),
        payment,
        routeCode);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO payment_quotes(id,payment_id,generation,route,market_rate,"
                    + "spread_percent,offered_rate,fee_amount,recipient_amount,estimated_minutes,"
                    + "recommended,policy_version,created_at,expires_at) VALUES (HEXTORAW(?),"
                    + "HEXTORAW(?),1,'MISSING_ROUTE',83.5,0.5,83.1,0.5,8308,240,1,"
                    + "'source-fee-v1',SYSTIMESTAMP,SYSTIMESTAMP)",
                UUID.randomUUID().toString().replace("-", ""),
                payment));
  }

  @Test
  void outboxPersistsJsonEventsAndOrderedDeliveries() throws Exception {
    migrateIsolatedSchema();
    String eventId = UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO outbox_events(id,topic,payload) VALUES (HEXTORAW('"
              + eventId
              + "'),'payment.initiated','{\"schemaVersion\":1,\"aggregateSequence\":1}')");
      assertThrows(
          java.sql.SQLException.class,
          () ->
              statement.execute(
                  "INSERT INTO outbox_events(id,topic,payload) VALUES (HEXTORAW('"
                      + UUID.randomUUID().toString().replace("-", "")
                      + "'),'payment.initiated','not-json')"));
    }
    assertEquals(
        Integer.valueOf(1),
        new JdbcTemplate(new SingleConnectionDataSource(connect(), false))
            .queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE id=HEXTORAW(?)" + " AND payload IS JSON",
                Integer.class,
                eventId));
  }
}
