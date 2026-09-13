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
                    () ->
                        transaction.execute(
                            ignored -> {
                              try {
                                barrier.await(5, TimeUnit.SECONDS);
                              } catch (Exception e) {
                                throw new IllegalStateException(e);
                              }
                              var operation =
                                  new WalletOperation(
                                      USER,
                                      "CONVERT",
                                      "wallet-race",
                                      "{\"amount\":\"100.0000\"}",
                                      "journal");
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
                      () -> {
                        throw new AssertionError("A pending replay must not fetch FX or post");
                      }))
          .isInstanceOf(OperationRetryException.class);
    }
  }
}
