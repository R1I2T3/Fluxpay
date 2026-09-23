package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.beans.TransferRouteOutcome;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RouteOutcome;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferProviderRepository;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/** Real-JPA idempotency for the terminal outcome projection, including the grouped counts query. */
class RouteOutcomeRecorderTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  private LocalContainerEntityManagerFactoryBean factory;
  private TransactionTemplate tx;
  private TransferRouteOutcomeRepository outcomes;
  private RouteOutcomeRecorder recorder;
  private RouteReliabilityService reliability;
  private TransferRoute route;

  @BeforeEach
  void setUp() {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:outcomes-" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1",
            "sa",
            ""));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(
        PersistenceManagedTypes.of(
            TransferProvider.class.getName(),
            TransferRoute.class.getName(),
            TransferRouteOutcome.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    var providers = repositories.getRepository(TransferProviderRepository.class);
    var routes = repositories.getRepository(TransferRouteRepository.class);
    outcomes = repositories.getRepository(TransferRouteOutcomeRepository.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    recorder = new RouteOutcomeRecorder(outcomes, clock);
    reliability = new RouteReliabilityService(outcomes);
    tx.executeWithoutResult(
        ignored -> {
          TransferProvider provider =
              providers.saveAndFlush(
                  TransferProvider.create(
                      UUID.randomUUID(),
                      "HDFC_BANK",
                      "HDFC Bank",
                      RailType.BANK_NETWORK,
                      true,
                      false,
                      NOW));
          route =
              routes.saveAndFlush(
                  TransferRoute.create(
                      UUID.randomUUID(),
                      provider,
                      "HDFC_INR_STANDARD",
                      "HDFC INR Standard",
                      DestinationType.EXTERNAL_ACCOUNT,
                      "IN",
                      "INR",
                      new BigDecimal("5.0000"),
                      new BigDecimal("0.500000"),
                      60,
                      new BigDecimal("80.00"),
                      null,
                      null,
                      true,
                      false,
                      NOW));
        });
  }

  @AfterEach
  void tearDown() {
    factory.destroy();
  }

  @Test
  void recordsTerminalOutcome() {
    TransferRouteOutcome recorded =
        tx.execute(
            ignored -> recorder.record(route.getId(), "execution-1", RouteOutcome.COMPLETED));

    assertThat(recorded.routeId()).isEqualTo(route.getId());
    assertThat(recorded.executionReference()).isEqualTo("execution-1");
    assertThat(recorded.outcome()).isEqualTo(RouteOutcome.COMPLETED);
    assertThat(recorded.occurredAt()).isEqualTo(NOW);
    List<TransferRouteOutcome> stored = tx.execute(ignored -> outcomes.findAll());
    assertThat(stored).hasSize(1);
  }

  @Test
  void replayOfIdenticalReferenceReturnsExistingRow() {
    TransferRouteOutcome first =
        tx.execute(
            ignored -> recorder.record(route.getId(), "execution-1", RouteOutcome.COMPLETED));
    TransferRouteOutcome replay =
        tx.execute(
            ignored -> recorder.record(route.getId(), "execution-1", RouteOutcome.COMPLETED));

    assertThat(replay.id()).isEqualTo(first.id());
    List<TransferRouteOutcome> stored = tx.execute(ignored -> outcomes.findAll());
    assertThat(stored).hasSize(1);
  }

  @Test
  void conflictingDuplicateReferenceIsRejected() {
    tx.execute(ignored -> recorder.record(route.getId(), "execution-1", RouteOutcome.COMPLETED));

    assertThatThrownBy(
            () ->
                tx.execute(
                    ignored -> recorder.record(route.getId(), "execution-1", RouteOutcome.FAILED)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("DUPLICATE_EXECUTION_REFERENCE");
              assertThat(error.status().value()).isEqualTo(409);
            });
    assertThatThrownBy(
            () ->
                tx.execute(
                    ignored ->
                        recorder.record(UUID.randomUUID(), "execution-1", RouteOutcome.COMPLETED)))
        .isInstanceOf(BusinessException.class);
    List<TransferRouteOutcome> stored = tx.execute(ignored -> outcomes.findAll());
    assertThat(stored).hasSize(1);
  }

  @Test
  void groupedCountsFeedLearnedReliability() {
    tx.executeWithoutResult(
        ignored -> {
          recorder.record(route.getId(), "execution-1", RouteOutcome.COMPLETED);
          recorder.record(route.getId(), "execution-2", RouteOutcome.COMPLETED);
          recorder.record(route.getId(), "execution-3", RouteOutcome.FAILED);
        });

    Map<UUID, RouteReliabilityService.RouteReliability> result =
        tx.execute(ignored -> reliability.effectiveFor(List.of(route)));

    // 80% * 20 virtual attempts + 2 completed + 1 failed = 18/23 = 78.260870%.
    assertThat(result.get(route.getId()).effectiveReliability()).isEqualByComparingTo("78.260870");
    assertThat(result.get(route.getId()).completedCount()).isEqualTo(2);
    assertThat(result.get(route.getId()).failedCount()).isEqualTo(1);
  }
}
