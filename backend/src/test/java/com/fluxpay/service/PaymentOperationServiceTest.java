package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentOperationServiceTest {
  static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID PAYMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void atomicMutationReplaysOneStoredResult() {
    try (var db = new OperationDatabase()) {
      var service = service(db);
      var mutations = new java.util.concurrent.atomic.AtomicInteger();
      java.util.function.Supplier<PaymentOperationService.Result<EventResponse>> work =
          () ->
              new PaymentOperationService.Result<>(
                  200, new EventResponse("event-" + mutations.incrementAndGet()), PAYMENT);
      var first =
          service.execute(
              USER, "atomic", "CANCEL", PAYMENT, java.util.Map.of(), EventResponse.class, work);
      var replay =
          service.execute(
              USER, "atomic", "CANCEL", PAYMENT, java.util.Map.of(), EventResponse.class, work);
      assertThat(first.response().eventId()).isEqualTo("event-1");
      assertThat(replay).isEqualTo(first);
      assertThat(mutations).hasValue(1);
      assertThat(db.payments.findAll())
          .singleElement()
          .satisfies(op -> assertThat(op.status()).isEqualTo("COMPLETED"));
    }
  }

  @Test
  void failedResponseSerializationRollsBackAtomicReservation() {
    try (var db = new OperationDatabase()) {
      var service = service(db);
      assertThatThrownBy(
              () ->
                  service.execute(
                      USER,
                      "broken",
                      "CANCEL",
                      PAYMENT,
                      java.util.Map.of(),
                      BrokenResponse.class,
                      () ->
                          new PaymentOperationService.Result<>(200, new BrokenResponse(), PAYMENT)))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_STORE_FAILED"));
      assertThat(db.payments.findAll()).isEmpty();
    }
  }

  static class BrokenResponse {
    public String getValue() {
      throw new IllegalStateException("serialization failure");
    }
  }

  @Test
  void scalarResponseCannotCompleteAnOperation() {
    try (var db = new OperationDatabase()) {
      var service = service(db);
      var reserved =
          service.reserve(
              USER, "scalar", "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class);
      assertThatThrownBy(
              () -> service.complete(reserved.id(), "event-id-is-not-a-response-object", 200))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_STORE_FAILED"));
      assertThat(db.payments.findAll())
          .singleElement()
          .satisfies(op -> assertThat(op.status()).isEqualTo("IN_PROGRESS"));
    }
  }

  @Test
  void keyCannotChangeAction() {
    try (var db = new OperationDatabase()) {
      var service = service(db);
      service.reserve(
          USER,
          "action",
          "SUBMIT",
          PAYMENT,
          java.util.Map.of("route", "BANK"),
          EventResponse.class);
      assertThatThrownBy(
              () ->
                  service.reserve(
                      USER,
                      "action",
                      "RETRY",
                      PAYMENT,
                      java.util.Map.of("route", "BANK"),
                      EventResponse.class))
          .isInstanceOfSatisfying(
              BusinessException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
      assertThat(db.payments.findAll()).hasSize(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(strings = {" ", "\t"})
  void invalidKeysCannotReserve(String key) {
    try (var db = new OperationDatabase()) {
      assertThatThrownBy(
              () ->
                  service(db)
                      .reserve(
                          USER, key, "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));
      assertThat(db.payments.findAll()).isEmpty();
    }
  }

  @Test
  void reorderedNestedObjectAndEquivalentNumbersReplayCanonicalJson() {
    try (var db = new OperationDatabase()) {
      var service = service(db);
      var request = new java.util.LinkedHashMap<String, Object>();
      request.put("route", "BANK");
      request.put("amount", new BigDecimal("100.00"));
      var first =
          service.reserve(USER, "canonical", "SUBMIT", PAYMENT, request, EventResponse.class);
      service.complete(first.id(), new EventResponse("event-1"), 200);
      var reversed = new java.util.LinkedHashMap<String, Object>();
      reversed.put("amount", new BigDecimal("100.0000"));
      reversed.put("route", "BANK");
      var replay =
          service.reserve(USER, "canonical", "SUBMIT", PAYMENT, reversed, EventResponse.class);
      assertThat(replay.response().eventId()).isEqualTo("event-1");
      assertThat(db.payments.findAll())
          .singleElement()
          .satisfies(
              op ->
                  assertThat(op.normalizedRequest())
                      .isEqualTo(
                          "{\"action\":\"SUBMIT\",\"paymentId\":\"22222222-2222-2222-2222-222222222222\",\"request\":{\"amount\":100,\"route\":\"BANK\"}}"));
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void concurrentUniqueKeyLoserReadsWinnerOnlyAfterFailedEntityManagerCloses(boolean completed)
      throws Exception {
    try (var db = new OperationDatabase()) {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      var failedSession = new ThreadLocal<jakarta.persistence.EntityManager>();
      var freshReads = new java.util.concurrent.atomic.AtomicInteger();
      var proxy = new org.springframework.aop.framework.ProxyFactory(db.payments);
      proxy.addAdvice(
          (org.aopalliance.intercept.MethodInterceptor)
              call -> {
                var current =
                    org.springframework.orm.jpa.EntityManagerFactoryUtils
                        .getTransactionalEntityManager(db.factory.getObject());
                if (call.getMethod().getName().startsWith("findByUserId")
                    && failedSession.get() != null) {
                  assertThat(failedSession.get().isOpen()).isFalse();
                  assertThat(current).isNotSameAs(failedSession.get());
                  freshReads.incrementAndGet();
                }
                Object found;
                try {
                  found = call.proceed();
                } catch (org.springframework.dao.DataIntegrityViolationException race) {
                  failedSession.set(current);
                  throw race;
                }
                if (call.getMethod().getName().startsWith("findByUserId")
                    && ((java.util.Optional<?>) found).isEmpty())
                  barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return found;
              });
      var repository = (com.fluxpay.repository.PaymentOperationRepository) proxy.getProxy();
      var service =
          new PaymentOperationService(
              repository,
              new com.fasterxml.jackson.databind.ObjectMapper(),
              Clock.systemUTC(),
              db.transactions);
      var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        java.util.concurrent.Callable<String> request =
            () -> {
              try {
                if (completed)
                  return service
                      .execute(
                          USER,
                          "race",
                          "CANCEL",
                          PAYMENT,
                          java.util.Map.of(),
                          EventResponse.class,
                          () ->
                              new PaymentOperationService.Result<>(
                                  200, new EventResponse("winner"), PAYMENT))
                      .response()
                      .eventId();
                service.reserve(
                    USER, "race", "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class);
                return "reserved";
              } catch (BusinessException e) {
                return e.code();
              }
            };
        var a = workers.submit(request);
        var b = workers.submit(request);
        assertThat(
                java.util.List.of(
                    a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder(
                completed ? "winner" : "reserved", completed ? "winner" : "OPERATION_IN_PROGRESS");
        assertThat(db.payments.findAll()).hasSize(1);
        assertThat(freshReads).hasValue(1);
      } finally {
        workers.shutdownNow();
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void winnerAfterInitialMissTakesPrecedenceOverChangedLocalValidation(boolean completed)
      throws Exception {
    try (var db = new OperationDatabase()) {
      var missed = new java.util.concurrent.CountDownLatch(1);
      var resume = new java.util.concurrent.CountDownLatch(1);
      var eligible = new java.util.concurrent.atomic.AtomicBoolean(true);
      var losing =
          new java.util.concurrent.atomic.AtomicReference<jakarta.persistence.EntityManager>();
      var reread =
          new java.util.concurrent.atomic.AtomicReference<jakarta.persistence.EntityManager>();
      var proxy = new org.springframework.aop.framework.ProxyFactory(db.payments);
      proxy.addAdvice(
          (org.aopalliance.intercept.MethodInterceptor)
              call -> {
                var current =
                    org.springframework.orm.jpa.EntityManagerFactoryUtils
                        .getTransactionalEntityManager(db.factory.getObject());
                if (call.getMethod().getName().startsWith("findByUserId") && losing.get() != null)
                  reread.set(current);
                Object found;
                try {
                  found = call.proceed();
                } catch (org.springframework.dao.DataIntegrityViolationException race) {
                  losing.set(current);
                  throw race;
                }
                if (call.getMethod().getName().startsWith("findByUserId")
                    && ((java.util.Optional<?>) found).isEmpty()) {
                  missed.countDown();
                  assertThat(resume.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
                return found;
              });
      var duplicateService =
          new PaymentOperationService(
              (com.fluxpay.repository.PaymentOperationRepository) proxy.getProxy(),
              new com.fasterxml.jackson.databind.ObjectMapper(),
              Clock.systemUTC(),
              db.transactions);
      var worker =
          java.util.concurrent.Executors.newSingleThreadExecutor(
              task -> {
                var thread = new Thread(task, "operation-validation-race");
                thread.setDaemon(true);
                return thread;
              });
      try {
        var duplicate =
            worker.submit(
                () -> {
                  try {
                    return duplicateService
                        .reserve(
                            USER,
                            "validation-race",
                            "SUBMIT",
                            PAYMENT,
                            java.util.Map.of("route", "BANK"),
                            EventResponse.class,
                            () -> {
                              if (!eligible.get())
                                throw new IllegalStateException("LOCAL_VALIDATION_FAILED");
                            })
                        .response()
                        .eventId();
                  } catch (BusinessException failure) {
                    return failure.code();
                  } catch (IllegalStateException failure) {
                    return failure.getMessage();
                  }
                });
        assertThat(missed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var winnerService = service(db);
        var winner =
            winnerService.reserve(
                USER,
                "validation-race",
                "SUBMIT",
                PAYMENT,
                java.util.Map.of("route", "BANK"),
                EventResponse.class);
        if (completed) winnerService.complete(winner.id(), new EventResponse("winner-event"), 200);
        eligible.set(false);
        resume.countDown();
        assertThat(duplicate.get(10, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(completed ? "winner-event" : "OPERATION_IN_PROGRESS");
        assertThat(losing.get()).isNotNull();
        assertThat(losing.get().isOpen()).isFalse();
        assertThat(reread.get()).isNotNull().isNotSameAs(losing.get());
        assertThat(db.payments.findAll())
            .singleElement()
            .satisfies(
                op -> {
                  assertThat(op.id()).isEqualTo(winner.id());
                  assertThat(op.status()).isEqualTo(completed ? "COMPLETED" : "IN_PROGRESS");
                  assertThat(op.responseData())
                      .isEqualTo(completed ? "{\"eventId\":\"winner-event\"}" : null);
                });
      } finally {
        resume.countDown();
        worker.shutdownNow();
        assertThat(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void maxKeyLengthIs255AndChangedAmountCannotReplay() {
    try (var db = new OperationDatabase()) {
      var service = service(db);
      var first =
          service.reserve(
              USER,
              "k".repeat(255),
              "SUBMIT",
              PAYMENT,
              java.util.Map.of("amount", "100.0000", "route", "BANK"),
              EventResponse.class);
      service.complete(first.id(), new EventResponse("winner"), 200);
      assertThatThrownBy(
              () ->
                  service.reserve(
                      USER,
                      "k".repeat(256),
                      "SUBMIT",
                      PAYMENT,
                      java.util.Map.of(),
                      EventResponse.class))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));
      assertThatThrownBy(
              () ->
                  service.reserve(
                      USER,
                      "k".repeat(255),
                      "SUBMIT",
                      PAYMENT,
                      java.util.Map.of("amount", "101.0000", "route", "BANK"),
                      EventResponse.class))
          .isInstanceOfSatisfying(
              BusinessException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
      assertThat(db.payments.findAll()).hasSize(1);
    }
  }

  static PaymentOperationService service(OperationDatabase db) {
    return new PaymentOperationService(
        db.payments,
        new com.fasterxml.jackson.databind.ObjectMapper(),
        Clock.systemUTC(),
        db.transactions);
  }

  @Test
  void pendingDuplicateHasStableConflictAndDoesNotCompleteReservation() {
    try (var db = new OperationDatabase()) {
      var gate = service(db);
      gate.reserve(USER, "pending", "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class);
      assertThatThrownBy(
              () ->
                  gate.reserve(
                      USER, "pending", "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.code()).isEqualTo("OPERATION_IN_PROGRESS"));
      assertThat(db.payments.findAll())
          .singleElement()
          .satisfies(
              op -> {
                assertThat(op.status()).isEqualTo("IN_PROGRESS");
                assertThat(op.responseData()).isNull();
                assertThat(op.outcomeStatus()).isNull();
              });
    }
  }

  @Test
  void completedResponseIsStructuredJson() throws Exception {
    try (var db = new OperationDatabase()) {
      var gate = service(db);
      var pending =
          gate.reserve(USER, "json", "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class);
      gate.complete(pending.id(), new EventResponse("event-1"), 200);
      var operation = db.payments.findAll().get(0);
      assertThat(operation.responseData()).isEqualTo("{\"eventId\":\"event-1\"}");
      assertThat(
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .readTree(operation.normalizedRequest())
                  .isObject())
          .isTrue();
    }
  }

  @Test
  void sameUserKeyCannotReplayAnotherPayment() {
    try (var db = new OperationDatabase()) {
      var gate = service(db);
      var payment = snapshot(PAYMENT);
      var pending =
          gate.reserve(
              USER, "same-key", "SUBMIT", PAYMENT, java.util.Map.of(), EventResponse.class);
      gate.complete(pending.id(), new EventResponse("event-1"), 200);
      assertThatThrownBy(
              () ->
                  gate.reserve(
                      USER,
                      "same-key",
                      "SUBMIT",
                      UUID.randomUUID(),
                      java.util.Map.of(),
                      EventResponse.class))
          .isInstanceOfSatisfying(
              BusinessException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
  }

  public record EventResponse(String eventId) {}

  static PaymentSnapshot snapshot(UUID id) {
    return new PaymentSnapshot(
        id.toString(),
        USER,
        UUID.randomUUID(),
        UUID.randomUUID(),
        new BigDecimal("100.0000"),
        "USD",
        "INR",
        PaymentStatus.ROUTED);
  }
}
