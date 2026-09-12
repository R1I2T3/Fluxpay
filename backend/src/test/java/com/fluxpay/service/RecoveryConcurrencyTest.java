package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.common.event.EventPublisher;
import com.fluxpay.config.InMemoryPaymentReader;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.repository.PaymentEventStore;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

class RecoveryConcurrencyTest {
  private LocalContainerEntityManagerFactoryBean factory;
  private TransactionTemplate tx;
  private RecoveryService recovery;
  private RefundJournalService journal;
  private PayoutProvider standardProvider;
  private PayoutProvider instantProvider;
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void setup() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
            "sa",
            "");
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(PersistenceManagedTypes.of(PayoutAttempt.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    var emf = factory.getObject();
    tx = new TransactionTemplate(new JpaTransactionManager(emf));
    var attempts =
        new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
            .getRepository(PayoutAttemptRepository.class);
    var routes = mock(PayoutRouteRepository.class);
    var standard = route("STANDARD_BANK");
    var instant = route("INSTANT_PAYOUT");
    when(routes.findById(standard.getId())).thenReturn(Optional.of(standard));
    when(routes.findByCode("INSTANT_PAYOUT")).thenReturn(Optional.of(instant));
    var failed =
        PayoutAttempt.initiated(UUID.randomUUID(), "P-001", 1, standard.getId(), Instant.now());
    failed.markProcessing();
    failed.markFailed("DECLINED", "known failure");
    tx.executeWithoutResult(status -> attempts.saveAndFlush(failed));
    var reader = new InMemoryPaymentReader();
    journal = spy(new RefundJournalService(new ConcurrencyLedger()));
    standardProvider = provider("STANDARD_BANK");
    instantProvider = provider("INSTANT_PAYOUT");
    var events = mock(EventPublisher.class);
    var execution =
        new PayoutExecutionService(
            reader,
            routes,
            attempts,
            events,
            List.of(standardProvider, instantProvider),
            Clock.systemUTC());
    recovery =
        new RecoveryService(
            reader,
            routes,
            attempts,
            execution,
            journal,
            mock(PaymentEventStore.class),
            events,
            Clock.systemUTC());
  }

  @AfterEach
  void cleanup() throws InterruptedException {
    release.countDown();
    workers.shutdownNow();
    workers.awaitTermination(10, TimeUnit.SECONDS);
    factory.destroy();
  }

  @ParameterizedTest
  @ValueSource(strings = {"RETRY", "SWITCH"})
  void refundBlocksPayoutUntilRefundTransactionCommits(String action) throws Exception {
    doAnswer(
            call -> {
              pause();
              return call.callRealMethod();
            })
        .when(journal)
        .refund(any());
    var first = workers.submit(() -> tx.execute(status -> recovery.refund("P-001", "refund")));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    var secondStarted = new CountDownLatch(1);
    var second =
        workers.submit(
            () -> {
              secondStarted.countDown();
              return tx.execute(status -> payout(action));
            });
    assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
    release.countDown();
    first.get(5, TimeUnit.SECONDS);
    assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
        .hasRootCauseMessage("payment P-001 already refunded");
    verify(standardProvider, never()).submit(any());
    verify(instantProvider, never()).submit(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"RETRY", "SWITCH"})
  void payoutBlocksRefundUntilCompletedAttemptCommits(String action) throws Exception {
    var provider = action.equals("RETRY") ? standardProvider : instantProvider;
    when(provider.submit(any()))
        .thenAnswer(
            call -> {
              pause();
              return PayoutResult.ok("paid", BigDecimal.ONE);
            });
    var first = workers.submit(() -> tx.execute(status -> payout(action)));
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    var secondStarted = new CountDownLatch(1);
    var second =
        workers.submit(
            () -> {
              secondStarted.countDown();
              return tx.execute(status -> recovery.refund("P-001", "refund"));
            });
    assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
        .isInstanceOf(TimeoutException.class);
    release.countDown();
    first.get(5, TimeUnit.SECONDS);
    assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
        .hasRootCauseMessage("latest payout attempt is not failed");
    verify(journal, never()).refund(any());
  }

  private Object payout(String action) {
    return action.equals("RETRY")
        ? recovery.retry("P-001", "payout")
        : recovery.switchRoute("P-001", "INSTANT_PAYOUT", "payout");
  }

  private void pause() throws InterruptedException {
    entered.countDown();
    if (!release.await(10, TimeUnit.SECONDS))
      throw new AssertionError("concurrent request timed out");
  }

  private static PayoutRoute route(String code) {
    return PayoutRoute.seed(UUID.randomUUID(), code, code, code, "STANDARD", "5", "1", 10, "99");
  }

  private static PayoutProvider provider(String code) {
    var provider = mock(PayoutProvider.class);
    when(provider.code()).thenReturn(code);
    return provider;
  }

  /**
   * Minimal in-memory {@link LedgerWriter} so {@link RefundJournalService#isAlreadyRefunded} sees
   * real refund keys. A Mockito mock returns {@code false} from {@code contains}, so a concurrent
   * payout retry would receive a {@code null} provider result instead of the expected
   * already-refunded guard.
   */
  private static final class ConcurrencyLedger implements LedgerWriter {
    private final java.util.Set<String> keys =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override
    public void append(
        UUID walletId,
        String entryType,
        BigDecimal amount,
        String currency,
        String idempotencyKey) {
      Objects.requireNonNull(walletId, "walletId must not be null");
      Objects.requireNonNull(entryType, "entryType must not be null");
      Objects.requireNonNull(amount, "amount must not be null");
      Objects.requireNonNull(currency, "currency must not be null");
      Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
      keys.add(idempotencyKey);
    }

    @Override
    public boolean contains(String idempotencyKey) {
      return keys.contains(idempotencyKey);
    }
  }
}
