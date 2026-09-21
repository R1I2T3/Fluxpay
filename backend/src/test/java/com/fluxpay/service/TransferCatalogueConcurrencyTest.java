package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferProviderRepository;
import com.fluxpay.repository.TransferRouteRepository;
import com.fluxpay.service.TransferProviderService.UpdateProvider;
import com.fluxpay.service.TransferRouteService.UpdateRoute;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class TransferCatalogueConcurrencyTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
  private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ROUTE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final CountDownLatch routeCompatibilityEntered = new CountDownLatch(1);
  private final CountDownLatch releaseRouteCompatibility = new CountDownLatch(1);
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  private LocalContainerEntityManagerFactoryBean factory;
  private JpaTransactionManager manager;
  private TransactionTemplate tx;
  private TransferProviderRepository providers;
  private TransferRouteRepository routes;
  private TransferProviderService providerService;
  private TransferRouteService routeService;

  @BeforeEach
  void setUp() {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:catalogue-"
                + UUID.randomUUID()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000",
            "sa",
            ""));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(
        PersistenceManagedTypes.of(
            TransferProvider.class.getName(), TransferRoute.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    manager = new JpaTransactionManager(factory.getObject());
    tx = new TransactionTemplate(manager);
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    providers = repositories.getRepository(TransferProviderRepository.class);
    routes = repositories.getRepository(TransferRouteRepository.class);

    tx.executeWithoutResult(
        ignored -> {
          TransferProvider provider =
              providers.saveAndFlush(
                  TransferProvider.create(
                      PROVIDER_ID,
                      "HDFC_BANK",
                      "HDFC Bank",
                      RailType.BANK_NETWORK,
                      true,
                      false,
                      NOW));
          routes.saveAndFlush(
              TransferRouteTestFixtures.inactiveExternalRoute(ROUTE_ID, provider, NOW));
        });

    RailRegistry railRegistry = new RailRegistry(List.of(blockingBankRail()));
    RoutingUsageService usage = mock(RoutingUsageService.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    providerService =
        transactional(new TransferProviderService(providers, routes, usage, railRegistry, clock));
    routeService =
        transactional(new TransferRouteService(routes, providers, usage, railRegistry, clock));
  }

  @AfterEach
  void tearDown() {
    releaseRouteCompatibility.countDown();
    executor.shutdownNow();
    factory.destroy();
  }

  @Test
  void activeRouteUpdateSerializesWithProviderDeactivation() throws Exception {
    Future<TransferRoute> activation =
        executor.submit(() -> routeService.update(ROUTE_ID, activateRoute()));
    assertThat(routeCompatibilityEntered.await(2, TimeUnit.SECONDS)).isTrue();

    CountDownLatch deactivationStarted = new CountDownLatch(1);
    Future<TransferProvider> deactivation =
        executor.submit(
            () -> {
              deactivationStarted.countDown();
              return providerService.update(
                  PROVIDER_ID, new UpdateProvider("HDFC Bank", RailType.BANK_NETWORK, false, 0L));
            });
    assertThat(deactivationStarted.await(2, TimeUnit.SECONDS)).isTrue();

    Object earlyDeactivation;
    try {
      earlyDeactivation = deactivation.get(300, TimeUnit.MILLISECONDS);
    } catch (TimeoutException expected) {
      earlyDeactivation = expected;
    } catch (ExecutionException completedWithFailure) {
      earlyDeactivation = completedWithFailure;
    } finally {
      releaseRouteCompatibility.countDown();
    }

    activation.get(3, TimeUnit.SECONDS);
    Throwable deactivationFailure = catchThrowable(() -> deactivation.get(3, TimeUnit.SECONDS));
    CatalogueState state =
        tx.execute(
            ignored ->
                new CatalogueState(
                    providers.findById(PROVIDER_ID).orElseThrow().active(),
                    routes.findById(ROUTE_ID).orElseThrow().active()));

    assertThat(earlyDeactivation).isInstanceOf(TimeoutException.class);
    assertThat(deactivationFailure)
        .isInstanceOf(ExecutionException.class)
        .cause()
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("PROVIDER_HAS_ROUTES"));
    assertThat(state).isEqualTo(new CatalogueState(true, true));
  }

  private UpdateRoute activateRoute() {
    return new UpdateRoute(
        PROVIDER_ID,
        "HDFC INR Standard",
        DestinationType.EXTERNAL_ACCOUNT,
        "IN",
        "INR",
        new BigDecimal("5.0000"),
        new BigDecimal("0.500000"),
        60,
        new BigDecimal("99.00"),
        new BigDecimal("1.0000"),
        new BigDecimal("500000.0000"),
        true,
        0L);
  }

  private TransferRail blockingBankRail() {
    return new TransferRail() {
      @Override
      public RailType type() {
        return RailType.BANK_NETWORK;
      }

      @Override
      public Set<DestinationType> supportedDestinations() {
        routeCompatibilityEntered.countDown();
        try {
          if (!releaseRouteCompatibility.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting to release route compatibility check");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while holding route update open", e);
        }
        return Set.of(DestinationType.EXTERNAL_ACCOUNT);
      }

      @Override
      public TransferRailResult execute(TransferRailCommand command) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @SuppressWarnings("unchecked")
  private <T> T transactional(T service) {
    ProxyFactory proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }

  private record CatalogueState(boolean providerActive, boolean routeActive) {}
}
