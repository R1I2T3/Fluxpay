package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.exception.OperationRetryException;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class WalletOperationServiceTest {
  static final UUID USER = PaymentOperationServiceTest.USER;

  @Test
  void postingStoresTheCanonicalRequestProvidedByCoordinator() {
    try (var db = new OperationDatabase()) {
      var service = new WalletOperationService(db.wallets, new ObjectMapper(), db.transactions);
      service.execute(
          USER,
          "CONVERT",
          "storage",
          "{\"z\":100.00,\"a\":{\"y\":1e2,\"b\":2}}",
          PaymentOperationServiceTest.EventResponse.class,
          canonical ->
              new TransactionTemplate(db.transactions)
                  .execute(
                      ignored -> {
                        var operation =
                            new WalletOperation(USER, "CONVERT", "storage", canonical, "journal");
                        operation.complete("{\"eventId\":\"stored\"}");
                        db.wallets.saveAndFlush(operation);
                        return new PaymentOperationServiceTest.EventResponse("stored");
                      }));
      var stored =
          new TransactionTemplate(db.transactions)
              .execute(
                  ignored ->
                      db.wallets
                          .findByUserIdAndOperationTypeAndClientKey(USER, "CONVERT", "storage")
                          .orElseThrow());
      assertThat(stored.getNormalizedRequest()).isEqualTo("{\"a\":{\"b\":2,\"y\":100},\"z\":100}");
    }
  }

  @Test
  void reorderedObjectsAndEquivalentNumbersReplay() {
    try (var db = new OperationDatabase()) {
      var tx = new TransactionTemplate(db.transactions);
      tx.executeWithoutResult(
          ignored -> {
            var operation =
                new WalletOperation(
                    USER,
                    "CONVERT",
                    "canonical",
                    "{\"nested\":{\"route\":\"BANK\",\"amount\":100.00}}",
                    "journal");
            operation.complete("{\"eventId\":\"stored\"}");
            db.wallets.saveAndFlush(operation);
          });
      var service = new WalletOperationService(db.wallets, new ObjectMapper(), db.transactions);
      var replay =
          service.execute(
              USER,
              "CONVERT",
              "canonical",
              "{\"nested\":{\"amount\":1e2,\"route\":\"BANK\"}}",
              PaymentOperationServiceTest.EventResponse.class,
              canonical -> {
                throw new AssertionError("Canonical replay must bypass FX and posting");
              });
      assertThat(replay.eventId()).isEqualTo("stored");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"broken", "[]", "null", "42", "\"string\"", "{} trailing"})
  void invalidOrScalarRequestsNeverReachPosting(String json) {
    try (var db = new OperationDatabase()) {
      var service = new WalletOperationService(db.wallets, new ObjectMapper(), db.transactions);
      assertThatThrownBy(
              () ->
                  service.execute(
                      USER,
                      "CONVERT",
                      "invalid",
                      json,
                      PaymentOperationServiceTest.EventResponse.class,
                      canonical -> new PaymentOperationServiceTest.EventResponse("must-not-run")))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void duplicateWalletMutationsCommitOneOperationAndReplayTheWinner() throws Exception {
    try (var db = new OperationDatabase()) {
      var service = new WalletOperationService(db.wallets, new ObjectMapper(), db.transactions);
      var workers = Executors.newFixedThreadPool(2);
      var barrier = new CyclicBarrier(2);
      var transaction = new TransactionTemplate(db.transactions);
      try {
        Callable<PaymentOperationServiceTest.EventResponse> call =
            () ->
                service.execute(
                    USER,
                    "CONVERT",
                    "wallet-race",
                    "{\"amount\":\"100.0000\"}",
                    PaymentOperationServiceTest.EventResponse.class,
                    canonical ->
                        transaction.execute(
                            ignored -> {
                              try {
                                barrier.await(5, TimeUnit.SECONDS);
                              } catch (Exception e) {
                                throw new IllegalStateException(e);
                              }
                              var operation =
                                  new WalletOperation(
                                      USER, "CONVERT", "wallet-race", canonical, "journal");
                              db.wallets.saveAndFlush(operation);
                              operation.complete("{\"eventId\":\"wallet-result\"}");
                              db.wallets.saveAndFlush(operation);
                              return new PaymentOperationServiceTest.EventResponse("wallet-result");
                            }));
        var a = workers.submit(call);
        var b = workers.submit(call);
        assertThat(a.get(10, TimeUnit.SECONDS).eventId()).isEqualTo("wallet-result");
        assertThat(b.get(10, TimeUnit.SECONDS).eventId()).isEqualTo("wallet-result");
        long count =
            transaction.execute(
                ignored ->
                    org.springframework.orm.jpa.EntityManagerFactoryUtils
                        .getTransactionalEntityManager(db.factory.getObject())
                        .createQuery("select count(o) from WalletOperation o", Long.class)
                        .getSingleResult());
        assertThat(count).isEqualTo(1);
      } finally {
        workers.shutdownNow();
      }
    }
  }

  @Test
  void pendingWalletReplayNeverExecutesMutation() {
    try (var db = new OperationDatabase()) {
      new TransactionTemplate(db.transactions)
          .executeWithoutResult(
              ignored ->
                  db.wallets.saveAndFlush(
                      new WalletOperation(USER, "CONVERT", "pending", "{}", "journal")));
      var service = new WalletOperationService(db.wallets, new ObjectMapper(), db.transactions);
      assertThatThrownBy(
              () ->
                  service.execute(
                      USER,
                      "CONVERT",
                      "pending",
                      "{}",
                      PaymentOperationServiceTest.EventResponse.class,
                      canonical -> {
                        throw new AssertionError("A pending replay must not fetch FX or post");
                      }))
          .isInstanceOf(OperationRetryException.class);
    }
  }
}
