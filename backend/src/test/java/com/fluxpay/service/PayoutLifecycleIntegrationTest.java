package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.*;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

class PayoutLifecycleIntegrationTest {
  static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  final UUID user = UUID.randomUUID(), id = UUID.randomUUID(), wallet = UUID.randomUUID();
  final UUID clearing = UUID.randomUUID(),
      feeWallet = UUID.randomUUID(),
      quoteId = UUID.randomUUID();
  final java.util.concurrent.atomic.AtomicReference<Instant> now =
      new java.util.concurrent.atomic.AtomicReference<>(NOW);
  final Clock clock =
      new Clock() {
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
          return Clock.fixed(instant(), zone);
        }

        public Instant instant() {
          return now.get();
        }
      };
  final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  final AtomicInteger calls = new AtomicInteger();
  LocalContainerEntityManagerFactoryBean factory;
  JpaTransactionManager manager;
  TransactionTemplate tx;
  PaymentRepository payments;
  PaymentQuoteRepository quotes;
  TransferRouteRepository routes;
  TransferProviderRepository providers;
  PayoutAttemptRepository attempts;
  PaymentOperationRepository operations;
  OutboxEventRepository events;
  OutboxDeliveryRepository deliveries;
  PayoutExecutionService execution;
  RecoveryService recovery;
  PaymentOperationService operationService;
  WalletRepository wallets;
  LedgerEntryRepository entries;
  RefundJournalService refunds;
  PayoutOutboxService outbox;
  PayoutFinalizationService finalization;
  java.util.function.Function<TransferRailCommand, TransferRailResult> delivery;
  Payment payment;
  TransferRailCommand delivered;
  TransferRail rail;
  RailRegistry rails;

  @BeforeEach
  void setup() throws Exception {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:payout-" + id + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000",
            "sa",
            ""));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(
        PersistenceManagedTypes.of(
            Payment.class.getName(),
            Recipient.class.getName(),
            PaymentQuote.class.getName(),
            TransferProvider.class.getName(),
            TransferRoute.class.getName(),
            TransferRouteOutcome.class.getName(),
            PayoutAttempt.class.getName(),
            PaymentOperation.class.getName(),
            OutboxEvent.class.getName(),
            OutboxDelivery.class.getName(),
            Wallet.class.getName(),
            LedgerEntry.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    manager = new JpaTransactionManager(factory.getObject());
    tx = new TransactionTemplate(manager);
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    payments = repositories.getRepository(PaymentRepository.class);
    quotes = repositories.getRepository(PaymentQuoteRepository.class);
    routes = repositories.getRepository(TransferRouteRepository.class);
    providers = repositories.getRepository(TransferProviderRepository.class);
    attempts = repositories.getRepository(PayoutAttemptRepository.class);
    operations = repositories.getRepository(PaymentOperationRepository.class);
    events = repositories.getRepository(OutboxEventRepository.class);
    deliveries = repositories.getRepository(OutboxDeliveryRepository.class);
    wallets = repositories.getRepository(WalletRepository.class);
    entries = repositories.getRepository(LedgerEntryRepository.class);
    tx.executeWithoutResult(
        s -> {
          seedWallet(wallet, user, WalletAccountRole.CUSTOMER, "1000");
          seedWallet(clearing, UUID.randomUUID(), WalletAccountRole.PAYOUT_CLEARING, "0");
          seedWallet(feeWallet, UUID.randomUUID(), WalletAccountRole.FEE_REVENUE, "0");
        });
    var recipients = repositories.getRepository(RecipientRepository.class);
    var recipient =
        new Recipient(
            UUID.randomUUID(), user, "A", "acct", "Bank", "IN", "INR", RecipientStatus.ACTIVE, NOW);
    payment =
        new Payment(
            id,
            user,
            wallet,
            recipient,
            new BigDecimal("100.0000"),
            "USD",
            "INR",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{\"name\":\"A\",\"account\":\"acct\",\"bankName\":\"Bank\",\"country\":\"IN\",\"currency\":\"INR\"}",
            NOW);
    payment.quoted(payment.nextQuoteGeneration(), NOW);
    payment.selectAndProcess(quoteId, NOW);
    payment.recordPosting(
        mapper.writeValueAsString(
            new PaymentPostingSnapshot(
                wallet,
                clearing,
                feeWallet,
                "USD",
                new BigDecimal("100.0000"),
                new BigDecimal("95.0000"),
                new BigDecimal("5.0000"),
                "payment:" + id)),
        NOW);
    tx.executeWithoutResult(
        s -> {
          recipients.save(recipient);
          payments.save(payment);
          saveRoute("BANK", "Bank");
          quotes.save(
              new PaymentQuote(
                  quoteId,
                  id,
                  1,
                  "BANK",
                  new BigDecimal("80"),
                  BigDecimal.ZERO,
                  new BigDecimal("80"),
                  new BigDecimal("5"),
                  new BigDecimal("7600"),
                  1,
                  true,
                  NOW,
                  NOW.plusSeconds(300)));
        });
    delivery = cmd -> TransferRailResult.completed("ref-" + cmd.attemptNumber(), BigDecimal.ZERO);
    rail =
        new TransferRail() {
          public RailType type() {
            return RailType.BANK_NETWORK;
          }

          public java.util.Set<DestinationType> supportedDestinations() {
            return java.util.Set.of(DestinationType.EXTERNAL_ACCOUNT);
          }

          public TransferRailResult execute(TransferRailCommand cmd) {
            calls.incrementAndGet();
            delivered = cmd;
            return delivery.apply(cmd);
          }
        };
    operationService = proxy(new PaymentOperationService(operations, mapper, clock, manager));
    var context = new LedgerPostingContext();
    var writer = proxy(new PersistentLedgerWriter(wallets, entries, context, clock));
    var journalHeaders = mock(LedgerJournalRepository.class);
    var journalLocks = mock(LedgerJournalLockRepository.class);
    Map<String, LedgerJournal> storedJournals = new ConcurrentHashMap<>();
    when(journalLocks.findByIdForUpdate(anyInt()))
        .thenReturn(Optional.of(mock(LedgerJournalLock.class)));
    when(journalHeaders.findByJournalReference(anyString()))
        .thenAnswer(call -> Optional.ofNullable(storedJournals.get(call.getArgument(0))));
    when(journalHeaders.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              LedgerJournal journal = call.getArgument(0);
              storedJournals.put(journal.getJournalReference(), journal);
              return journal;
            });
    var journalService =
        proxy(
            new LedgerJournalService(
                writer, context, journalHeaders, journalLocks, entries, wallets, clock));
    outbox = proxy(spy(new PayoutOutboxService(events, deliveries, mapper, clock)));
    var reservationService =
        proxy(
            new PayoutReservationService(
                payments,
                new DbPaymentReader(payments, recipients, mapper),
                attempts,
                routes,
                new SelectedQuoteService(payments, quotes, clock, routes),
                clock,
                writer,
                outbox,
                rails()));
    TransferRouteOutcomeRepository outcomes =
        repositories.getRepository(TransferRouteOutcomeRepository.class);
    finalization =
        proxy(
            new PayoutFinalizationService(
                payments,
                attempts,
                operationService,
                outbox,
                clock,
                new RouteOutcomeRecorder(outcomes, clock)));
    execution =
        proxy(
            new PayoutExecutionService(
                operationService, reservationService, finalization, rails()));
    refunds = proxy(new RefundJournalService(journalService, writer));
    journalService.post(
        "payment:" + id,
        List.of(
            new LedgerJournalLine(
                wallet,
                "DEBIT",
                new BigDecimal("100"),
                "USD",
                "payment:" + id + ":customer:debit",
                "Original funding"),
            new LedgerJournalLine(
                clearing,
                "CREDIT",
                new BigDecimal("95"),
                "USD",
                "payment:" + id + ":clearing:credit",
                "Original funding"),
            new LedgerJournalLine(
                feeWallet,
                "CREDIT",
                new BigDecimal("5"),
                "USD",
                "payment:" + id + ":fee:credit",
                "Original funding")));
    recovery =
        proxy(
            new RecoveryService(
                payments,
                new DbPaymentReader(payments, recipients, mapper),
                attempts,
                refunds,
                outbox,
                clock));
  }

  RailRegistry rails() {
    if (rails == null) rails = new RailRegistry(List.of(rail));
    return rails;
  }

  TransferProvider routeProvider() {
    return providers
        .findByProviderCode("TEST_PROVIDER")
        .orElseGet(
            () ->
                providers.saveAndFlush(
                    TransferProvider.create(
                        UUID.randomUUID(),
                        "TEST_PROVIDER",
                        "Test Provider",
                        RailType.BANK_NETWORK,
                        true,
                        false,
                        NOW)));
  }

  void saveRoute(String code, String name) {
    saveRoute(code, name, "5.0000");
  }

  void saveRoute(String code, String name, String fee) {
    routes.save(
        TransferRoute.create(
            UUID.randomUUID(),
            routeProvider(),
            code,
            name,
            DestinationType.EXTERNAL_ACCOUNT,
            "IN",
            "INR",
            new BigDecimal(fee),
            new BigDecimal("0.000000"),
            1,
            new BigDecimal("99.00"),
            null,
            null,
            true,
            false,
            NOW));
  }

  @SuppressWarnings("unchecked")
  <T> T proxy(T service) {
    var proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }

  @AfterEach
  void close() {
    factory.destroy();
  }

  PayoutApi.OutcomeResponse submit() {
    return execution.perform(user, "submit", "SUBMIT", id, "BANK", null, "cid");
  }

  void seedWallet(UUID walletId, UUID owner, WalletAccountRole role, String balance) {
    var value = new Wallet(owner, "USD", role);
    org.springframework.test.util.ReflectionTestUtils.setField(value, "id", walletId);
    value.setBalance(new BigDecimal(balance));
    wallets.saveAndFlush(value);
  }

  RecoveryResult refund(String key) {
    return operationService
        .execute(
            user,
            key,
            "REFUND",
            id,
            Map.of(),
            RecoveryResult.class,
            () ->
                new PaymentOperationService.Result<>(
                    200, recovery.refundFunded(user, id, "cid"), id))
        .response();
  }

  @Test
  void providerRunsAfterReservationCommitsWithoutDatabaseTransaction() {
    delivery =
        cmd -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          var observer = Executors.newSingleThreadExecutor();
          try {
            assertThat(observer.submit(() -> attempts.findAll()).get(3, TimeUnit.SECONDS))
                .hasSize(1);
          } catch (Exception ex) {
            throw new AssertionError(ex);
          } finally {
            observer.shutdownNow();
          }
          return TransferRailResult.completed("paid", BigDecimal.ZERO);
        };
    submit();
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(events.findAll()).isNotEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {
        "DRAFT",
        "QUOTED",
        "UNDER_REVIEW",
        "REJECTED",
        "CANCELLED",
        "COMPLETED",
        "REFUNDED",
        "FAILED"
      })
  void rejectsEveryNonProcessingStateBeforeDelivery(PaymentStatus state) {
    tx.executeWithoutResult(
        s -> {
          var p = payments.findById(id).orElseThrow();
          org.springframework.test.util.ReflectionTestUtils.setField(p, "status", state);
        });
    assertThatThrownBy(this::submit).isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(0);
    assertThat(attempts.findAll()).isEmpty();
  }

  @Test
  void rejectsUnfundedProcessingPaymentBeforeDelivery() {
    tx.executeWithoutResult(
        s -> {
          var p = payments.findById(id).orElseThrow();
          org.springframework.test.util.ReflectionTestUtils.setField(p, "postingSnapshot", null);
          org.springframework.test.util.ReflectionTestUtils.setField(p, "postedAt", null);
        });
    assertThatThrownBy(this::submit).isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(0);
  }

  @Test
  void providerReceivesCommittedAttemptIdentityAndFrozenEconomics() {
    var response = submit();
    var command = mapper.valueToTree(delivered);
    assertThat(command.path("attemptId").asText())
        .isEqualTo(attempts.findAll().get(0).id().toString());
    assertThat(command.path("idempotencyKey").asText())
        .isEqualTo("payout:" + attempts.findAll().get(0).id());
    assertThat(delivered.customerFee()).isEqualByComparingTo("5.0000");
    assertThat(delivered.recipientAmount()).isEqualByComparingTo("7600.0000");
    assertThat(
            mapper
                .valueToTree(response)
                .path("selectedQuote")
                .path("recipientAmount")
                .decimalValue())
        .isEqualByComparingTo("7600.0000");
  }

  @Test
  void routePricingEditsCannotChangeAcceptedSubmissionEconomics() {
    tx.executeWithoutResult(
        s -> routes.findByRouteCode("BANK").orElseThrow().update("25", "10", 10, "90", true));
    var result = submit();
    assertThat(result.selectedQuote().feeAmount()).isEqualByComparingTo("5.0000");
    assertThat(result.selectedQuote().netSourceAmount()).isEqualByComparingTo("95.0000");
    assertThat(delivered.customerFee()).isEqualByComparingTo("5.0000");
    assertThat(delivered.offeredRate()).isEqualByComparingTo("80.000000");
    assertThat(delivered.recipientAmount()).isEqualByComparingTo("7600.0000");
  }

  QuoteService quoteService() {
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    var outcomes = repositories.getRepository(TransferRouteOutcomeRepository.class);
    var smart =
        new SmartRoutingService(
            routes,
            new RouteEligibilityService(new RailRegistry(List.of(bankRail()))),
            new RouteReliabilityService(outcomes),
            new RoutePricingService(new QuotePricingPolicy()),
            new RouteRecommender());
    return new QuoteService(
        payments,
        quotes,
        (source, target) -> {
          assertThat(source).isEqualTo("USD");
          assertThat(target).isEqualTo("INR");
          return new BigDecimal("82.000000");
        },
        clock,
        smart,
        operationService,
        new PaymentRecoveryEligibility(
            new DbPaymentReader(payments, repositoriesRecipients(), mapper),
            attempts,
            proxy(
                new PersistentLedgerWriter(wallets, entries, new LedgerPostingContext(), clock))));
  }

  static com.fluxpay.common.contracts.TransferRail bankRail() {
    return new com.fluxpay.common.contracts.TransferRail() {
      @Override
      public RailType type() {
        return RailType.BANK_NETWORK;
      }

      @Override
      public java.util.Set<DestinationType> supportedDestinations() {
        return java.util.Set.of(DestinationType.EXTERNAL_ACCOUNT);
      }

      @Override
      public com.fluxpay.dto.TransferRailResult execute(
          com.fluxpay.dto.TransferRailCommand command) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"RETRY", "SWITCH"})
  void expiredFailedPaymentUsesRealRecoveryQuotesAndExplicitSelectionWithoutAnotherDebit(
      String action) {
    delivery =
        cmd ->
            cmd.attemptNumber() == 1
                ? TransferRailResult.failed("declined", "Known final rejection", BigDecimal.ZERO)
                : TransferRailResult.completed("recovered", BigDecimal.ZERO);
    submit();
    tx.executeWithoutResult(s -> saveRoute("BANK2", "Bank2"));
    now.set(NOW.plusSeconds(901));
    var refreshed =
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> quoteService().createOrCurrent(user, id, "recovery-quotes"));
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(quoteService().createOrCurrent(user, id, "recovery-quotes")).isEqualTo(refreshed);
    String route = action.equals("RETRY") ? "BANK" : "BANK2";
    var candidate =
        refreshed.quotes().stream().filter(q -> q.route().equals(route)).findFirst().orElseThrow();
    assertThat(candidate.feeAmount()).isEqualTo("5.0000");
    assertThat(candidate.recipientAmount()).isEqualTo("7790.0000");
    var result =
        execution.perform(
            user,
            "recover",
            action,
            id,
            action.equals("SWITCH") ? route : null,
            candidate.id(),
            "cid");
    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.selectedQuote().quoteId()).isEqualTo(candidate.id());
    assertThat(delivered.recipientAmount()).isEqualByComparingTo("7790.0000");
    assertThat(wallets.findById(wallet).orElseThrow().getBalance())
        .isEqualByComparingTo("900.0000");
    assertThat(wallets.findById(clearing).orElseThrow().getBalance())
        .isEqualByComparingTo("95.0000");
    assertThat(wallets.findById(feeWallet).orElseThrow().getBalance())
        .isEqualByComparingTo("5.0000");
    assertThat(
            entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                wallet, org.springframework.data.domain.Pageable.unpaged()))
        .hasSize(1);
    assertThat(payments.findById(id).orElseThrow().postingSnapshot())
        .isEqualTo(payment.postingSnapshot());
    assertThat(
            execution.perform(
                user,
                "recover",
                action,
                id,
                action.equals("SWITCH") ? route : null,
                candidate.id(),
                "cid"))
        .isEqualTo(result);
    assertThat(calls).hasValue(2);
  }

  @Test
  void expiredAcceptedRetryKeepsFrozenEconomicsEvenAfterNewQuoteGeneration() {
    delivery = cmd -> TransferRailResult.failed("declined", "Known rejection", BigDecimal.ZERO);
    submit();
    now.set(NOW.plusSeconds(901));
    tx.executeWithoutResult(
        s -> routes.findByRouteCode("BANK").orElseThrow().update("25", "10", 1, "99", true));
    quoteService().createOrCurrent(user, id, "new-quotes");
    delivery = cmd -> TransferRailResult.completed("recovered", BigDecimal.ZERO);
    var result = execution.perform(user, "retry", "RETRY", id, null, null, "cid");
    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.selectedQuote().quoteId()).isEqualTo(quoteId);
    assertThat(result.selectedQuote().netSourceAmount()).isEqualByComparingTo("95.0000");
    assertThat(delivered.customerFee()).isEqualByComparingTo("5.0000");
    assertThat(delivered.offeredRate()).isEqualByComparingTo("80.000000");
    assertThat(delivered.recipientAmount()).isEqualByComparingTo("7600.0000");
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
    assertThat(calls).hasValue(2);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"RETRY", "SWITCH"})
  void freshRecoveryQuotesUseCurrentRouteFeeAndRejectChangedFundingAllocation(String action) {
    delivery = cmd -> TransferRailResult.failed("declined", "Known rejection", BigDecimal.ZERO);
    submit();
    tx.executeWithoutResult(
        s -> {
          routes.findByRouteCode("BANK").orElseThrow().update("6", "0", 1, "99", true);
          saveRoute("BANK2", "Bank2", "6.0000");
        });
    now.set(NOW.plusSeconds(901));
    var fresh = quoteService().createOrCurrent(user, id, "current-fees");
    String route = action.equals("RETRY") ? "BANK" : "BANK2";
    var candidate =
        fresh.quotes().stream().filter(q -> q.route().equals(route)).findFirst().orElseThrow();
    assertThat(candidate.feeAmount()).isEqualTo("6.0000");
    assertThat(candidate.recipientAmount()).isEqualTo("7708.0000");
    assertThatThrownBy(
            () ->
                execution.perform(
                    user,
                    "recover",
                    action,
                    id,
                    action.equals("SWITCH") ? route : null,
                    candidate.id(),
                    "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("REQUOTE_REQUIRED"));
    assertThat(operations.findAll()).hasSize(2);
    assertThat(attempts.findAll()).hasSize(1);
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
    assertThat(wallets.findById(clearing).orElseThrow().getBalance()).isEqualByComparingTo("95");
    assertThat(wallets.findById(feeWallet).orElseThrow().getBalance()).isEqualByComparingTo("5");
    assertThat(calls).hasValue(1);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"provider_timeout", "wire_error_17", "connection reset"})
  void uncertainOutcomeClassificationBlocksRecoveryRegardlessOfDiagnosticCode(String code) {
    delivery =
        cmd -> TransferRailResult.uncertain(code, "Delivery could have succeeded", BigDecimal.ZERO);
    assertThatThrownBy(this::submit)
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_PENDING_RECONCILIATION"));
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.PROCESSING);
    assertThatThrownBy(() -> refund("refund")).isInstanceOf(IllegalStateException.class);
    now.set(NOW.plusSeconds(901));
    assertThatThrownBy(() -> quoteService().createOrCurrent(user, id, "recovery-quotes"))
        .isInstanceOf(com.fluxpay.exception.BusinessException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void confirmedFinalFailureDoesNotInferUncertaintyFromDiagnosticWords() {
    delivery =
        cmd ->
            TransferRailResult.failed(
                "TIMEOUT_CONFIRMED_NO_DELIVERY",
                "Provider definitively rejected delivery",
                BigDecimal.ZERO);
    var failed = org.junit.jupiter.api.Assertions.assertDoesNotThrow(this::submit);
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "inactive", "same", "blank"})
  void invalidSwitchRoutesConsistentlyRequireRequote(String invalid) {
    delivery = cmd -> TransferRailResult.failed("declined", "Known rejection", BigDecimal.ZERO);
    submit();
    tx.executeWithoutResult(s -> saveRoute("BANK2", "Bank2"));
    var refreshed = quoteService().createOrCurrent(user, id, "recovery-quotes");
    var candidate =
        refreshed.quotes().stream()
            .filter(q -> q.route().equals("BANK2"))
            .findFirst()
            .orElseThrow();
    if (invalid.equals("inactive"))
      tx.executeWithoutResult(
          s -> routes.findByRouteCode("BANK2").orElseThrow().update("5", "0", 1, "99", false));
    String route =
        switch (invalid) {
          case "missing" -> "ABSENT";
          case "same" -> "BANK";
          case "blank" -> "";
          default -> "BANK2";
        };
    assertThatThrownBy(
            () -> execution.perform(user, "switch", "SWITCH", id, route, candidate.id(), "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("REQUOTE_REQUIRED"));
    assertThat(attempts.findAll()).hasSize(1);
    assertThat(operations.findAll()).hasSize(2);
    assertThat(calls).hasValue(1);
  }

  @Test
  void finalizationItselfCannotTurnUncertainDeliveryIntoARefundableFailure() throws Exception {
    var uncertain =
        TransferRailResult.uncertain("wire_error", "No final acknowledgment", BigDecimal.ZERO);
    delivery = cmd -> uncertain;
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    var reserved =
        mapper.readValue(
            operations.findAll().get(0).payoutReservation(),
            PayoutReservationService.Reserved.class);
    var operation = operations.findAll().get(0);
    assertThatThrownBy(() -> finalization.finish(reserved, operation.id(), uncertain, "cid"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(operations.findAll())
        .singleElement()
        .satisfies(op -> assertThat(op.status()).isEqualTo("IN_PROGRESS"));
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"refunded", "processing", "unfunded", "attempt-processing", "reversed"})
  void recoveryQuotesRejectAnyNonRecoverableFundingState(String invalid) {
    delivery = cmd -> TransferRailResult.failed("declined", "Known rejection", BigDecimal.ZERO);
    submit();
    if (invalid.equals("refunded")) refund("refund");
    tx.executeWithoutResult(
        s -> {
          var p = payments.findById(id).orElseThrow();
          if (invalid.equals("processing"))
            org.springframework.test.util.ReflectionTestUtils.setField(
                p, "status", PaymentStatus.PROCESSING);
          if (invalid.equals("unfunded"))
            org.springframework.test.util.ReflectionTestUtils.setField(p, "postedAt", null);
          if (invalid.equals("attempt-processing"))
            org.springframework.test.util.ReflectionTestUtils.setField(
                attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(id.toString()).orElseThrow(),
                "status",
                PayoutAttemptStatus.PROCESSING);
          if (invalid.equals("reversed"))
            refunds.refund(
                new DbPaymentReader(payments, repositoriesRecipients(), mapper).get(id.toString()));
        });
    now.set(NOW.plusSeconds(901));
    long prior = operations.count();
    assertThatThrownBy(() -> quoteService().createOrCurrent(user, id, "recovery-quotes"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("INVALID_PAYMENT_STATE"));
    assertThat(operations.count()).isEqualTo(prior);
    assertThat(calls).hasValue(1);
  }

  @Test
  void refreshedRecoveryQuotesSupersedePriorGenerationAndCannotChangeRetryRoute() {
    delivery = cmd -> TransferRailResult.failed("declined", "Known rejection", BigDecimal.ZERO);
    submit();
    tx.executeWithoutResult(s -> saveRoute("BANK2", "Bank2"));
    now.set(NOW.plusSeconds(901));
    var first = quoteService().createOrCurrent(user, id, "recovery-quotes-1");
    var firstOther =
        first.quotes().stream().filter(q -> q.route().equals("BANK2")).findFirst().orElseThrow();
    assertThatThrownBy(
            () -> execution.perform(user, "wrong-retry", "RETRY", id, null, firstOther.id(), "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("REQUOTE_REQUIRED"));
    var next = quoteService().createOrCurrent(user, id, "recovery-quotes-2");
    assertThatThrownBy(
            () ->
                execution.perform(
                    user, "stale-switch", "SWITCH", id, "BANK2", firstOther.id(), "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("REQUOTE_REQUIRED"));
    var currentOther =
        next.quotes().stream().filter(q -> q.route().equals("BANK2")).findFirst().orElseThrow();
    var result = execution.perform(user, "switch", "SWITCH", id, "BANK2", currentOther.id(), "cid");
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(calls).hasValue(2);
    assertThatThrownBy(
            () -> execution.perform(user, "switch", "SWITCH", id, "BANK2", firstOther.id(), "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void knownDeclineCompletesOperationAndExplicitRetryUsesNewAttempt() {
    delivery =
        cmd ->
            cmd.attemptNumber() == 1
                ? TransferRailResult.failed("DECLINED", "Known final rejection", BigDecimal.ZERO)
                : TransferRailResult.completed("paid", BigDecimal.ZERO);
    var first = submit();
    assertThat(first.status()).isEqualTo("FAILED");
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(submit()).isEqualTo(first);
    assertThat(calls).hasValue(1);
    var retry = execution.perform(user, "retry", "RETRY", id, null, null, "cid");
    assertThat(retry.status()).isEqualTo("COMPLETED");
    assertThat(calls).hasValue(2);
    assertThat(attempts.findAll()).hasSize(2);
    assertThat(payments.findById(id).orElseThrow().postingSnapshot())
        .isEqualTo(payment.postingSnapshot());
    assertThat(operations.findAll())
        .allSatisfy(op -> assertThat(op.status()).isEqualTo("COMPLETED"));
  }

  @Test
  void reconciliationReplaysPersistedCommandAndCompletesOriginalOperation() {
    delivery = cmd -> TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO);
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    var original = delivered;
    now.set(NOW.plusSeconds(3600));
    delivery = cmd -> TransferRailResult.completed("reconciled", BigDecimal.ZERO);
    var reconciler =
        new PayoutReconciler(operations, attempts, payments, mapper, rails(), finalization);
    var result = reconciler.reconcile(id, "reconcile-cid");
    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(delivered).isEqualTo(original);
    assertThat(attempts.findAll()).hasSize(1);
    assertThat(submit()).isEqualTo(result);
    assertThat(calls).hasValue(2);
    assertThat(events.findAll())
        .extracting(OutboxEvent::topic)
        .containsExactlyInAnyOrder(
            "payment.route.selected", "payout.submitted", "payout.completed");
  }

  @Test
  void failureSchedulesOneDurableRetryExactlyTwoMinutesLater() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var failure =
        events.findAll().stream()
            .filter(e -> e.topic().equals("payout.failed"))
            .findFirst()
            .orElseThrow();
    var consumer = recoveryConsumer();
    consumer.onEvent(failure.payload(), "payout.failed");
    consumer.onEvent(failure.payload(), "payout.failed");
    var commands = events.findAll().stream().filter(e -> e.topic().equals("payout.retry")).toList();
    assertThat(commands).hasSize(1);
    var command = commands.get(0);
    var envelope = new com.fluxpay.messaging.EventEnvelopeCodec(mapper).read(command.payload());
    assertThat(envelope.payload())
        .containsEntry("attemptCount", 1)
        .containsEntry("nextRun", "2026-09-13T10:02:00Z");
    assertThat(deliveries.findById(command.id()).orElseThrow().nextAttemptAt())
        .isEqualTo(Instant.parse("2026-09-13T10:02:00Z"));
    tx.executeWithoutResult(
        s -> {
          for (var d : deliveries.findAll()) {
            if (!d.eventId().equals(command.id())) {
              d.claim("test", NOW.plusSeconds(30));
              deliveries.saveAndFlush(d);
              deliveries.markSentIfClaimed(d.eventId(), "test", NOW);
            }
          }
        });
    var early =
        tx.execute(
            s ->
                deliveries.claimEligible(
                    NOW.plusSeconds(119), org.springframework.data.domain.Pageable.unpaged()));
    var due =
        tx.execute(
            s ->
                deliveries.claimEligible(
                    NOW.plusSeconds(120), org.springframework.data.domain.Pageable.unpaged()));
    assertThat(early).isEmpty();
    assertThat(due).extracting(OutboxDelivery::eventId).containsExactly(command.id());
    assertThat(calls).hasValue(1);
  }

  com.fluxpay.messaging.PayoutRetryConsumer recoveryConsumer() {
    return new com.fluxpay.messaging.PayoutRetryConsumer(
        operationService,
        operations,
        payments,
        attempts,
        execution,
        recovery,
        outbox,
        new com.fluxpay.messaging.EventEnvelopeCodec(mapper),
        clock);
  }

  @Test
  void delayedRecoveryDoesNotBlockManualOutcomeEvents() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var consumer = recoveryConsumer();
    var failure = recoveryEvent("payout.failed", "attempt", 1);
    consumer.onEvent(failure.payload(), failure.topic());
    var command = recoveryEvent("payout.retry", "attemptCount", 1);
    int commandSequence = deliveries.findById(command.id()).orElseThrow().aggregateSequence();
    delivery = cmd -> TransferRailResult.completed("manual-completed", BigDecimal.ZERO);
    execution.perform(user, "manual", "RETRY", id, null, null, "cid");
    tx.executeWithoutResult(
        s -> {
          for (var d : deliveries.findByPaymentIdOrderByAggregateSequenceAsc(id)) {
            if (d.aggregateSequence() < commandSequence) {
              d.claim("test", NOW.plusSeconds(30));
              deliveries.saveAndFlush(d);
              deliveries.markSentIfClaimed(d.eventId(), "test", NOW);
            }
          }
        });
    for (String topic : List.of("payment.route.selected", "payout.submitted", "payout.completed")) {
      var eligible =
          tx.execute(
              s ->
                  deliveries.claimEligible(
                      NOW, org.springframework.data.domain.Pageable.unpaged()));
      assertThat(eligible).hasSize(1);
      assertThat(events.findById(eligible.get(0).eventId()).orElseThrow().topic()).isEqualTo(topic);
      tx.executeWithoutResult(
          s -> {
            var d = deliveries.findById(eligible.get(0).eventId()).orElseThrow();
            d.claim("test", NOW.plusSeconds(30));
            deliveries.saveAndFlush(d);
            deliveries.markSentIfClaimed(d.eventId(), "test", NOW);
          });
    }
    assertThat(deliveries.findById(command.id()).orElseThrow().state()).isEqualTo("PENDING");
    now.set(NOW.plusSeconds(120));
    consumer.onEvent(command.payload(), command.topic());
    assertThat(calls).hasValue(2);
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
  }

  OutboxEvent recoveryEvent(String topic, String field, int value) {
    var codec = new com.fluxpay.messaging.EventEnvelopeCodec(mapper);
    return events.findAll().stream()
        .filter(e -> e.topic().equals(topic))
        .filter(e -> ((Number) codec.read(e.payload()).payload().get(field)).intValue() == value)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void fiveDelayedRetriesThenOneRefundDespiteDuplicateDeliveries() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    completeAutomaticRecovery();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"schedule", "retry", "refund"})
  void publicRecoveryLookingKeysCannotBlockAutomaticRecovery(String kind) {
    String key = "auto:" + kind + ":" + id + (kind.equals("refund") ? ":5" : ":1");
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    var initial = execution.perform(user, key, "SUBMIT", id, "BANK", null, "cid");
    var publicOperation = operations.findAll().get(0);
    assertThatCode(this::completeAutomaticRecovery).doesNotThrowAnyException();
    assertThat(execution.perform(user, key, "SUBMIT", id, "BANK", null, "cid")).isEqualTo(initial);
    assertThat(operations.findById(publicOperation.id()).orElseThrow().responseData())
        .isEqualTo(publicOperation.responseData());
    assertThat(operations.findAll().stream().filter(op -> key.equals(op.clientKey()))).hasSize(2);
    assertThatThrownBy(() -> execution.perform(user, key, "RETRY", id, null, null, "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    assertThat(calls).hasValue(6);
  }

  void completeAutomaticRecovery() {
    var consumer = recoveryConsumer();
    for (int retry = 1; retry <= 5; retry++) {
      var failure = recoveryEvent("payout.failed", "attempt", retry);
      consumer.onEvent(failure.payload(), failure.topic());
      consumer.onEvent(failure.payload(), failure.topic());
      var command = recoveryEvent("payout.retry", "attemptCount", retry);
      assertThat(deliveries.findById(command.id()).orElseThrow().nextAttemptAt())
          .isEqualTo(NOW.plusSeconds(120L * retry));
      now.set(NOW.plusSeconds(120L * retry));
      consumer.onEvent(command.payload(), command.topic());
      consumer.onEvent(command.payload(), command.topic());
      assertThat(calls.get()).isEqualTo(retry + 1);
      assertThat(attempts.findAll()).hasSize(retry + 1);
    }
    var failure = recoveryEvent("payout.failed", "attempt", 6);
    consumer.onEvent(failure.payload(), failure.topic());
    consumer.onEvent(failure.payload(), failure.topic());
    var refund = events.findAll().stream().filter(e -> e.topic().equals("payout.refund")).toList();
    assertThat(refund).hasSize(1);
    consumer.onEvent(refund.get(0).payload(), "payout.refund");
    consumer.onEvent(refund.get(0).payload(), "payout.refund");
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("1000");
    assertThat(wallets.findById(clearing).orElseThrow().getBalance()).isEqualByComparingTo("0");
    assertThat(wallets.findById(feeWallet).orElseThrow().getBalance()).isEqualByComparingTo("0");
    for (var account : List.of(wallet, clearing, feeWallet))
      assertThat(
              entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                  account, org.springframework.data.domain.Pageable.unpaged()))
          .hasSize(2);
    assertThat(events.findAll().stream().filter(e -> e.topic().equals("payout.retry"))).hasSize(5);
    assertThat(events.findAll().stream().filter(e -> e.topic().equals("payment.refunded")))
        .hasSize(1);
    assertThat(calls).hasValue(6);
  }

  @Test
  void uncertainAutomaticRetryStopsWithoutAnotherRetryOrRefund() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var consumer = recoveryConsumer();
    var failure = recoveryEvent("payout.failed", "attempt", 1);
    consumer.onEvent(failure.payload(), failure.topic());
    var command = recoveryEvent("payout.retry", "attemptCount", 1);
    now.set(NOW.plusSeconds(120));
    delivery = cmd -> TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO);
    consumer.onEvent(command.payload(), command.topic());
    consumer.onEvent(command.payload(), command.topic());
    consumer.onEvent(failure.payload(), failure.topic());
    assertThat(calls).hasValue(2);
    assertThat(attempts.findAll()).hasSize(2);
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(events.findAll().stream().filter(e -> e.topic().equals("payout.retry"))).hasSize(1);
    assertThat(events.findAll())
        .extracting(OutboxEvent::topic)
        .doesNotContain("payout.refund", "payment.refunded");
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
  }

  @Test
  void recoveryCommandCannotExecuteBeforeItsDurableDueTime() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var consumer = recoveryConsumer();
    var failure = recoveryEvent("payout.failed", "attempt", 1);
    consumer.onEvent(failure.payload(), failure.topic());
    var command = recoveryEvent("payout.retry", "attemptCount", 1);
    now.set(NOW.plusSeconds(119));
    assertThatThrownBy(() -> consumer.onEvent(command.payload(), command.topic()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(1);
    now.set(NOW.plusSeconds(120));
    consumer.onEvent(command.payload(), command.topic());
    assertThat(calls).hasValue(2);
  }

  @Test
  void reconciledAutomaticFailureContinuesWithTheNextRetryOrdinal() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var consumer = recoveryConsumer();
    var failure = recoveryEvent("payout.failed", "attempt", 1);
    consumer.onEvent(failure.payload(), failure.topic());
    var firstRetry = recoveryEvent("payout.retry", "attemptCount", 1);
    now.set(NOW.plusSeconds(120));
    delivery = cmd -> TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO);
    consumer.onEvent(firstRetry.payload(), firstRetry.topic());
    var uncertainCommand = delivered;
    delivery =
        cmd -> TransferRailResult.failed("DECLINED", "Definitive rejection", BigDecimal.ZERO);
    var reconciler =
        new PayoutReconciler(operations, attempts, payments, mapper, rails(), finalization);
    assertThat(reconciler.reconcile(id, "reconcile").status()).isEqualTo("FAILED");
    assertThat(delivered).isEqualTo(uncertainCommand);
    assertThat(attempts.findAll()).hasSize(2);
    var reconciledFailure = recoveryEvent("payout.failed", "attempt", 2);
    consumer.onEvent(reconciledFailure.payload(), reconciledFailure.topic());
    var secondRetry = recoveryEvent("payout.retry", "attemptCount", 2);
    assertThat(deliveries.findById(secondRetry.id()).orElseThrow().nextAttemptAt())
        .isEqualTo(NOW.plusSeconds(240));
    consumer.onEvent(firstRetry.payload(), firstRetry.topic());
    assertThat(calls).hasValue(3);
    now.set(NOW.plusSeconds(240));
    delivery = cmd -> TransferRailResult.completed("recovered", BigDecimal.ZERO);
    consumer.onEvent(secondRetry.payload(), secondRetry.topic());
    assertThat(calls).hasValue(4);
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(events.findAll())
        .extracting(OutboxEvent::topic)
        .doesNotContain("payout.refund", "payment.refunded");
  }

  @Test
  void staleRetryCannotRetryANewerManualFailure() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var consumer = recoveryConsumer();
    var failure = recoveryEvent("payout.failed", "attempt", 1);
    consumer.onEvent(failure.payload(), failure.topic());
    var command = recoveryEvent("payout.retry", "attemptCount", 1);
    execution.perform(user, "manual-retry", "RETRY", id, null, null, "cid");
    now.set(NOW.plusSeconds(120));
    consumer.onEvent(command.payload(), command.topic());
    assertThat(calls).hasValue(2);
    assertThat(attempts.findAll()).hasSize(2);
    assertThat(operations.findAll().stream().filter(op -> op.operationType().equals("AUTO_RETRY")))
        .isEmpty();
    var latestFailure = recoveryEvent("payout.failed", "attempt", 2);
    consumer.onEvent(latestFailure.payload(), latestFailure.topic());
    var latestCommand =
        events.findAll().stream()
            .filter(e -> e.topic().equals("payout.retry") && !e.id().equals(command.id()))
            .findFirst()
            .orElseThrow();
    consumer.onEvent(latestCommand.payload(), latestCommand.topic());
    assertThat(calls).hasValue(3);
    assertThatCode(() -> consumer.onEvent(command.payload(), command.topic()))
        .doesNotThrowAnyException();
    assertThat(calls).hasValue(3);
  }

  @Test
  void staleRefundDoesNotConsumeTheTerminalRefundIdentity() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO);
    submit();
    var consumer = recoveryConsumer();
    for (int retry = 1; retry <= 5; retry++) {
      var failure = recoveryEvent("payout.failed", "attempt", retry);
      consumer.onEvent(failure.payload(), failure.topic());
      now.set(NOW.plusSeconds(120L * retry));
      var command = recoveryEvent("payout.retry", "attemptCount", retry);
      consumer.onEvent(command.payload(), command.topic());
    }
    var fifthFailure = recoveryEvent("payout.failed", "attempt", 6);
    consumer.onEvent(fifthFailure.payload(), fifthFailure.topic());
    var oldRefund = recoveryEvent("payout.refund", "failedAttempt", 6);
    execution.perform(user, "manual-after-five", "RETRY", id, null, null, "cid");
    consumer.onEvent(oldRefund.payload(), oldRefund.topic());
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
    var latestFailure = recoveryEvent("payout.failed", "attempt", 7);
    consumer.onEvent(latestFailure.payload(), latestFailure.topic());
    var currentRefund = recoveryEvent("payout.refund", "failedAttempt", 7);
    assertThatCode(() -> consumer.onEvent(currentRefund.payload(), currentRefund.topic()))
        .doesNotThrowAnyException();
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("1000");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "invalid", "wrong-payment"})
  void invalidDurableReservationNeverContactsProvider(String kind) {
    delivery = cmd -> TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO);
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    tx.executeWithoutResult(
        s -> {
          var operation = operations.findAll().get(0);
          String snapshot =
              kind.equals("missing")
                  ? null
                  : kind.equals("invalid")
                      ? "{}"
                      : operation
                          .payoutReservation()
                          .replace(id.toString(), UUID.randomUUID().toString());
          org.springframework.test.util.ReflectionTestUtils.setField(
              operation, "payoutReservation", snapshot);
          operations.saveAndFlush(operation);
        });
    var reconciler =
        new PayoutReconciler(operations, attempts, payments, mapper, rails(), finalization);
    assertThatThrownBy(() -> reconciler.reconcile(id, "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_RESERVATION_UNAVAILABLE"));
    assertThat(calls).hasValue(1);
  }

  @Test
  void duplicateDefinitiveReconciliationFinalizesOnlyOnce() throws Exception {
    delivery = cmd -> TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO);
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    var op = operations.findAll().get(0);
    var reserved =
        mapper.readValue(op.payoutReservation(), PayoutReservationService.Reserved.class);
    var first =
        finalization.finish(
            reserved,
            op.id(),
            TransferRailResult.failed("DECLINED", "Rejected", BigDecimal.ZERO),
            "cid");
    assertThatCode(
            () -> {
              var repeated =
                  finalization.finish(
                      reserved,
                      op.id(),
                      TransferRailResult.completed("late-conflicting-response", BigDecimal.ZERO),
                      "cid");
              assertThat(repeated).isEqualTo(first);
            })
        .doesNotThrowAnyException();
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(events.findAll())
        .extracting(OutboxEvent::topic)
        .containsExactlyInAnyOrder("payment.route.selected", "payout.submitted", "payout.failed");
  }

  @Test
  void repeatedUncertainReconciliationKeepsMoneyAndOperationPending() {
    delivery = cmd -> TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO);
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    var original = delivered;
    var reconciler =
        new PayoutReconciler(operations, attempts, payments, mapper, rails(), finalization);
    assertThatThrownBy(() -> reconciler.reconcile(id, "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_PENDING_RECONCILIATION"));
    assertThat(delivered).isEqualTo(original);
    assertThat(operations.findAll())
        .singleElement()
        .satisfies(op -> assertThat(op.status()).isEqualTo("IN_PROGRESS"));
    assertThat(attempts.findAll()).hasSize(1);
    assertThat(events.findAll())
        .extracting(OutboxEvent::topic)
        .containsExactlyInAnyOrder("payment.route.selected", "payout.submitted");
    assertThatThrownBy(() -> refund("refund")).isInstanceOf(IllegalStateException.class);
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
  }

  @Test
  void timeoutResultRemainsPendingAndBlocksRetry() {
    delivery =
        cmd ->
            TransferRailResult.uncertain("PROVIDER_TIMEOUT", "Unknown delivery", BigDecimal.ZERO);
    assertThatThrownBy(this::submit)
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_PENDING_RECONCILIATION"));
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(attempts.findAll())
        .singleElement()
        .satisfies(a -> assertThat(a.status()).isEqualTo(PayoutAttemptStatus.PROCESSING));
    assertThatThrownBy(this::submit)
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("OPERATION_IN_PROGRESS"));
    assertThatThrownBy(() -> execution.perform(user, "retry", "RETRY", id, null, null, "cid"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(1);
    assertThat(operations.findAll())
        .singleElement()
        .satisfies(op -> assertThat(op.status()).isEqualTo("IN_PROGRESS"));
  }

  @Test
  void duplicateAndDifferentKeySubmissionsCannotDeliverTwiceWhileProviderWaits() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    delivery =
        cmd -> {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new AssertionError("provider wait timed out");
          } catch (InterruptedException ex) {
            throw new AssertionError(ex);
          }
          return TransferRailResult.completed("paid", BigDecimal.ZERO);
        };
    try {
      var first = workers.submit(this::submit);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(this::submit)
          .isInstanceOfSatisfying(
              com.fluxpay.exception.BusinessException.class,
              ex -> assertThat(ex.code()).isEqualTo("OPERATION_IN_PROGRESS"));
      assertThatThrownBy(
              () ->
                  workers
                      .submit(
                          () -> execution.perform(user, "other", "SUBMIT", id, "BANK", null, "cid"))
                      .get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(calls).hasValue(1);
      release.countDown();
      var result = first.get(3, TimeUnit.SECONDS);
      assertThat(submit()).isEqualTo(result);
      assertThat(attempts.findAll()).hasSize(1);
      assertThat(operations.findAll()).hasSize(1);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void refundUpdatesPaymentAndReversesExactOriginalFundingOnce() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    var first = refund("refund");
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(wallets.findById(wallet).orElseThrow().getBalance())
        .isEqualByComparingTo("1000.0000");
    assertThat(wallets.findById(clearing).orElseThrow().getBalance())
        .isEqualByComparingTo("0.0000");
    assertThat(wallets.findById(feeWallet).orElseThrow().getBalance())
        .isEqualByComparingTo("0.0000");
    assertThat(refund("refund")).isEqualTo(first);
    assertThat(refund("refund-again").idempotentReplay()).isTrue();
    assertThat(
            entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                wallet, org.springframework.data.domain.Pageable.unpaged()))
        .hasSize(2);
    assertThat(
            entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                clearing, org.springframework.data.domain.Pageable.unpaged()))
        .hasSize(2);
    assertThat(
            entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                feeWallet, org.springframework.data.domain.Pageable.unpaged()))
        .hasSize(2);
    assertThat(events.findAll()).hasSize(4);
    assertThatThrownBy(() -> execution.perform(user, "retry", "RETRY", id, null, null, "cid"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void switchRequiresReplacementQuoteWithSameFrozenFunding() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    UUID replacement = addReplacement("BANK2", "5", id, 1, NOW.plusSeconds(300));
    assertThatCode(
            () -> execution.perform(user, "switch", "SWITCH", id, "BANK2", replacement, "cid"))
        .doesNotThrowAnyException();
    assertThat(payments.findById(id).orElseThrow().selectedQuoteId()).isEqualTo(replacement);
    assertThat(delivered.recipientAmount()).isEqualByComparingTo("7790.0000");
    assertThat(delivered.customerFee()).isEqualByComparingTo("5.0000");
    assertThat(wallets.findById(wallet).orElseThrow().getBalance())
        .isEqualByComparingTo("900.0000");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"missing", "fee", "payment", "generation", "expiry", "route"})
  void rejectsInvalidReplacementQuoteWithoutAttemptOrOperation(String invalid) {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    UUID replacement =
        addReplacement(
            invalid.equals("route") ? "BANK3" : "BANK2",
            invalid.equals("fee") ? "6" : "5",
            invalid.equals("payment") ? UUID.randomUUID() : id,
            invalid.equals("generation") ? 2 : 1,
            invalid.equals("expiry") ? NOW : NOW.plusSeconds(300));
    assertThatThrownBy(
            () ->
                execution.perform(
                    user,
                    "switch",
                    "SWITCH",
                    id,
                    "BANK2",
                    invalid.equals("missing") ? null : replacement,
                    "cid"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("REQUOTE_REQUIRED"));
    assertThat(attempts.findAll()).hasSize(1);
    assertThat(operations.findAll()).hasSize(1);
    assertThat(calls).hasValue(1);
  }

  UUID addReplacement(String route, String fee, UUID ownerPayment, int generation, Instant expiry) {
    UUID replacement = UUID.randomUUID();
    tx.executeWithoutResult(
        s -> {
          if (routes.findByRouteCode("BANK2").isEmpty()) saveRoute("BANK2", "Bank2");
          quotes.save(
              new PaymentQuote(
                  replacement,
                  ownerPayment,
                  generation,
                  route,
                  new BigDecimal("82"),
                  BigDecimal.ZERO,
                  new BigDecimal("82"),
                  new BigDecimal(fee),
                  new BigDecimal("7790"),
                  1,
                  true,
                  NOW,
                  expiry));
        });
    return replacement;
  }

  @Test
  void retryCannotSpendAnAlreadyReversedPostingEvenIfPaymentStateIsStale() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    tx.executeWithoutResult(
        s ->
            refunds.refund(
                new DbPaymentReader(payments, repositoriesRecipients(), mapper)
                    .get(id.toString())));
    assertThatThrownBy(() -> execution.perform(user, "retry", "RETRY", id, null, null, "cid"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(1);
  }

  RecipientRepository repositoriesRecipients() {
    return new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()))
        .getRepository(RecipientRepository.class);
  }

  @Test
  void selectedQuoteCannotAlterOriginalFeeAllocation() {
    tx.executeWithoutResult(
        s -> {
          var quote = quotes.findById(quoteId).orElseThrow();
          org.springframework.test.util.ReflectionTestUtils.setField(
              quote, "feeAmount", new BigDecimal("6"));
        });
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    assertThat(calls).hasValue(0);
    assertThat(operations.findAll()).isEmpty();
  }

  @Test
  void finalizationFailureRollsBackPaymentAttemptOperationAndOutboxTogether() {
    var target =
        org.springframework.test.util.AopTestUtils.<PayoutOutboxService>getUltimateTargetObject(
            outbox);
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("outbox storage failure");
            })
        .when(target)
        .enqueue(any(), eq(com.fluxpay.messaging.EventTopics.PAYOUT_COMPLETED), any(), any());
    assertThatThrownBy(this::submit).hasMessageContaining("outbox storage failure");
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(attempts.findAll())
        .singleElement()
        .satisfies(a -> assertThat(a.status()).isEqualTo(PayoutAttemptStatus.PROCESSING));
    assertThat(operations.findAll())
        .singleElement()
        .satisfies(op -> assertThat(op.status()).isEqualTo("IN_PROGRESS"));
    assertThat(events.findAll()).hasSize(2);
    assertThat(deliveries.findAll()).hasSize(2);
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void refundOutboxFailureRollsBackEveryJournalLegAndOperation() {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    var target =
        org.springframework.test.util.AopTestUtils.<PayoutOutboxService>getUltimateTargetObject(
            outbox);
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("refund storage failure");
            })
        .when(target)
        .enqueue(any(), eq(com.fluxpay.messaging.EventTopics.PAYMENT_REFUNDED), any(), any());
    assertThatThrownBy(() -> refund("refund")).hasMessageContaining("refund storage failure");
    assertThat(payments.findById(id).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
    assertThat(wallets.findById(clearing).orElseThrow().getBalance()).isEqualByComparingTo("95");
    assertThat(wallets.findById(feeWallet).orElseThrow().getBalance()).isEqualByComparingTo("5");
    assertThat(operations.findAll()).hasSize(1);
    assertThat(events.findAll()).hasSize(3);
  }

  @Test
  void concurrentRefundsWithDifferentKeysPostOneReversal() throws Exception {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    var workers = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var a =
          workers.submit(
              () -> {
                start.await();
                return refund("a");
              });
      var b =
          workers.submit(
              () -> {
                start.await();
                return refund("b");
              });
      start.countDown();
      assertThat(
              List.of(
                  a.get(5, TimeUnit.SECONDS).idempotentReplay(),
                  b.get(5, TimeUnit.SECONDS).idempotentReplay()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("1000");
      assertThat(events.findAll()).hasSize(4);
      assertThat(
              entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                  wallet, org.springframework.data.domain.Pageable.unpaged()))
          .hasSize(2);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void preservesOrderedRouteSelectedSubmittedAndTerminalOutboxIntents() {
    submit();
    assertThat(events.findAll())
        .extracting(OutboxEvent::topic)
        .containsExactlyInAnyOrder(
            "payment.route.selected", "payout.submitted", "payout.completed");
    assertThat(deliveries.findAll())
        .extracting(OutboxDelivery::aggregateSequence)
        .containsExactlyInAnyOrder(1, 2, 3);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"inactive", "expired", "unavailable"})
  void localPreDeliveryFailureLeavesNoDurableReservation(String cause) {
    tx.executeWithoutResult(
        s -> {
          if (cause.equals("inactive"))
            routes.findByRouteCode("BANK").orElseThrow().update("5", "0", 1, "99", false);
          if (cause.equals("expired"))
            org.springframework.test.util.ReflectionTestUtils.setField(
                quotes.findById(quoteId).orElseThrow(), "expiresAt", NOW);
          if (cause.equals("unavailable")) {
            saveRoute("NONE", "None");
            org.springframework.test.util.ReflectionTestUtils.setField(
                quotes.findById(quoteId).orElseThrow(), "route", "NONE");
            var none = routes.findByRouteCode("NONE").orElseThrow();
            var catalogProvider = none.provider();
            catalogProvider.update(catalogProvider.name(), catalogProvider.railType(), false, NOW);
          }
        });
    assertThatThrownBy(
            () ->
                execution.perform(
                    user,
                    "submit",
                    "SUBMIT",
                    id,
                    cause.equals("unavailable") ? "NONE" : "BANK",
                    null,
                    "cid"))
        .isInstanceOf(RuntimeException.class);
    assertThat(operations.findAll()).isEmpty();
    assertThat(attempts.findAll()).isEmpty();
    assertThat(calls).hasValue(0);
  }

  @Test
  void ambiguousThrownProviderFailureBlocksAllRecoveryAndKeepsReservation() {
    delivery =
        cmd -> {
          throw new IllegalStateException("connection closed before response");
        };
    assertThatThrownBy(this::submit).isInstanceOf(com.fluxpay.exception.BusinessException.class);
    assertThatThrownBy(() -> refund("refund")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> execution.perform(user, "switch", "SWITCH", id, "BANK2", null, "cid"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(1);
    assertThat(operations.findAll()).hasSize(1);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"RETRY", "SWITCH"})
  void reservedRecoveryRejectsRefundWhileProviderRunsWithoutLocks(String action) throws Exception {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    var replacement = addReplacement("BANK2", "5", id, 1, NOW.plusSeconds(300));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(2);
    delivery =
        cmd -> {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new AssertionError("provider wait timed out");
          } catch (InterruptedException ex) {
            throw new AssertionError(ex);
          }
          return TransferRailResult.completed("paid", BigDecimal.ZERO);
        };
    try {
      var payout =
          workers.submit(
              () ->
                  execution.perform(
                      user,
                      "recover",
                      action,
                      id,
                      action.equals("SWITCH") ? "BANK2" : null,
                      action.equals("SWITCH") ? replacement : null,
                      "cid"));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> workers.submit(() -> refund("refund")).get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      release.countDown();
      assertThat(payout.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
      assertThat(wallets.findById(wallet).orElseThrow().getBalance()).isEqualByComparingTo("900");
      assertThat(
              entries.findByWalletIdOrderByCreatedAtDescIdDesc(
                  wallet, org.springframework.data.domain.Pageable.unpaged()))
          .hasSize(1);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"RETRY", "SWITCH"})
  void refundSerializesRecoveryUntilTheRefundCommits(String action) throws Exception {
    delivery = cmd -> TransferRailResult.failed("DECLINED", "Known rejection", BigDecimal.ZERO);
    submit();
    var replacement = addReplacement("BANK2", "5", id, 1, NOW.plusSeconds(300));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var target =
        org.springframework.test.util.AopTestUtils.<PayoutOutboxService>getUltimateTargetObject(
            outbox);
    doAnswer(
            call -> {
              var value = call.callRealMethod();
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("refund wait timed out");
              return value;
            })
        .when(target)
        .enqueue(any(), eq(com.fluxpay.messaging.EventTopics.PAYMENT_REFUNDED), any(), any());
    var workers = Executors.newFixedThreadPool(2);
    try {
      var first = workers.submit(() -> refund("refund"));
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      var started = new CountDownLatch(1);
      var second =
          workers.submit(
              () -> {
                started.countDown();
                return execution.perform(
                    user,
                    "recover",
                    action,
                    id,
                    action.equals("SWITCH") ? "BANK2" : null,
                    action.equals("SWITCH") ? replacement : null,
                    "cid");
              });
      assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      release.countDown();
      first.get(3, TimeUnit.SECONDS);
      assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      assertThat(calls).hasValue(1);
      assertThat(attempts.findAll()).hasSize(1);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }
}
