package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.OutboxDelivery;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS_ACTIVE", matches = "true")
@DataJpaTest(
    showSql = false,
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OutboxClaimOracleTest.JpaConfiguration.class)
class OutboxClaimOracleTest {
  private static final Instant NOW = Instant.parse("2026-09-15T05:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories(basePackageClasses = OutboxDeliveryRepository.class)
  static class JpaConfiguration {}

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) throws Exception {
    FreshBaselineOracleTest.migrateIsolatedSchema();
    properties.add("spring.datasource.url", () -> System.getenv("ORACLE_TEST_JDBC_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("ORACLE_TEST_USERNAME"));
    properties.add("spring.datasource.password", () -> System.getenv("ORACLE_TEST_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
  }

  @Autowired OutboxDeliveryRepository deliveries;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManager entityManager;

  @Test
  void concurrentOracleClaimsAreBoundedNonblockingDisjointAndOrdered() throws Exception {
    Fixture fixture = seedFixture();
    var firstHasLocks = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> claim("worker-1", firstHasLocks, releaseFirst));
      assertThat(firstHasLocks.await(5, TimeUnit.SECONDS)).isTrue();
      var second = pool.submit(() -> claim("worker-2", null, null));

      List<UUID> secondIds;
      try {
        secondIds = second.get(2, TimeUnit.SECONDS);
      } finally {
        releaseFirst.countDown();
      }
      List<UUID> firstIds = first.get(5, TimeUnit.SECONDS);

      assertThat(firstIds).hasSize(2);
      assertThat(secondIds).hasSize(2);
      assertThat(new HashSet<>(firstIds)).doesNotContainAnyElementsOf(secondIds);
      assertThat(firstIds).doesNotContain(fixture.secondSequenceEvent());
      assertThat(secondIds).doesNotContain(fixture.secondSequenceEvent());
    } finally {
      releaseFirst.countDown();
      pool.shutdownNow();
      cleanupFixture(fixture);
    }
  }

  private List<UUID> claim(String token, CountDownLatch claimed, CountDownLatch release)
      throws Exception {
    var transaction = new TransactionTemplate(transactionManager);
    return transaction.execute(
        ignored -> {
          var selected = deliveries.claimEligible(NOW, PageRequest.of(0, 2));
          selected.forEach(delivery -> delivery.claim(token, NOW.plus(Duration.ofMinutes(1))));
          deliveries.flush();
          if (claimed != null) {
            claimed.countDown();
          }
          if (release != null) {
            try {
              if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out while holding first Oracle claim");
              }
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError("Oracle claim test interrupted", exception);
            }
          }
          return selected.stream().map(OutboxDelivery::eventId).toList();
        });
  }

  private Fixture seedFixture() throws Exception {
    UUID user = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    List<UUID> payments =
        List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    List<UUID> events = new ArrayList<>();
    try (Connection connection = FreshBaselineOracleTest.connect()) {
      connection.setAutoCommit(false);
      execute(
          connection,
          "INSERT INTO users(id,email,password_hash,full_name) "
              + "VALUES (HEXTORAW(?),?,'!ORACLE_TEST_NO_LOGIN!','Outbox claim fixture')",
          hex(user),
          user + "@oracle-test.invalid");
      execute(
          connection,
          "INSERT INTO wallets(id,user_id,currency,account_role) "
              + "VALUES (HEXTORAW(?),HEXTORAW(?),'USD','CUSTOMER')",
          hex(wallet),
          hex(user));
      execute(
          connection,
          "INSERT INTO recipients(id,user_id,name,account_ref,country,currency,status,profile_complete) "
              + "VALUES (HEXTORAW(?),HEXTORAW(?),'Claim recipient',?,'IN','INR','ACTIVE',1)",
          hex(recipient),
          hex(user),
          "acct-" + recipient);
      for (UUID payment : payments) {
        execute(
            connection,
            "INSERT INTO payments(id,sender_wallet_id,recipient_id,amount,currency,status,sender_id) "
                + "VALUES (HEXTORAW(?),HEXTORAW(?),HEXTORAW(?),100,'USD','DRAFT',HEXTORAW(?))",
            hex(payment),
            hex(wallet),
            hex(recipient),
            hex(user));
        events.add(insertDelivery(connection, payment, 1));
      }
      UUID secondSequenceEvent = insertDelivery(connection, payments.get(0), 2);
      events.add(secondSequenceEvent);
      connection.commit();
      return new Fixture(user, wallet, recipient, payments, events, secondSequenceEvent);
    }
  }

  private UUID insertDelivery(Connection connection, UUID payment, int sequence) throws Exception {
    UUID event = UUID.randomUUID();
    execute(
        connection,
        "INSERT INTO outbox_events(id,topic,payload,created_at) "
            + "VALUES (HEXTORAW(?),'payment.initiated','{}',?)",
        hex(event),
        java.sql.Timestamp.from(NOW));
    execute(
        connection,
        "INSERT INTO outbox_delivery(event_id,payment_id,aggregate_sequence,state,next_attempt_at) "
            + "VALUES (HEXTORAW(?),HEXTORAW(?),?,'PENDING',?)",
        hex(event),
        hex(payment),
        sequence,
        java.sql.Timestamp.from(NOW));
    return event;
  }

  private void cleanupFixture(Fixture fixture) throws Exception {
    entityManager.clear();
    try (Connection connection = FreshBaselineOracleTest.connect()) {
      connection.setAutoCommit(false);
      for (UUID event : fixture.events()) {
        execute(connection, "DELETE FROM outbox_delivery WHERE event_id=HEXTORAW(?)", hex(event));
        execute(connection, "DELETE FROM outbox_events WHERE id=HEXTORAW(?)", hex(event));
      }
      for (UUID payment : fixture.payments()) {
        execute(connection, "DELETE FROM payments WHERE id=HEXTORAW(?)", hex(payment));
      }
      execute(connection, "DELETE FROM recipients WHERE id=HEXTORAW(?)", hex(fixture.recipient()));
      execute(connection, "DELETE FROM wallets WHERE id=HEXTORAW(?)", hex(fixture.wallet()));
      execute(connection, "DELETE FROM users WHERE id=HEXTORAW(?)", hex(fixture.user()));
      connection.commit();
    }
  }

  private static void execute(Connection connection, String sql, Object... values)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) {
        statement.setObject(i + 1, values[i]);
      }
      statement.executeUpdate();
    }
  }

  private static String hex(UUID id) {
    return id.toString().replace("-", "");
  }

  private record Fixture(
      UUID user,
      UUID wallet,
      UUID recipient,
      List<UUID> payments,
      List<UUID> events,
      UUID secondSequenceEvent) {}
}
