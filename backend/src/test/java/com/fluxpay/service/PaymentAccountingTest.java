package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import com.fluxpay.adapter.persistence.PersistentWalletAdapter;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.config.SystemAccountConfig;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PaymentPostingSnapshot;
import com.fluxpay.exception.SystemAccountUnavailableException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentAccountingTest {
  final AccountingDatabase db = new AccountingDatabase();
  final UUID paymentId = UUID.randomUUID();
  final SystemAccountService accounts =
      new SystemAccountService(db.wallets, new SystemAccountConfig(db.system.toString()));

  @Test
  void confirmationWritesOneJournalForGross100Net95Fee5AndLocksInCanonicalOrder() {
    confirm("5.0000");
    assertThat(db.balance(db.customer)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("95.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("5.0000");
    assertThat(db.count()).isEqualTo(3);
    assertThat(db.jdbc.queryForList("select distinct journal from entries", String.class))
        .containsExactly("payment:" + paymentId);
    assertThat(db.locks.subList(0, 3)).containsExactly(db.clearing, db.fee, db.customer);
  }

  @Test
  void paymentAndRefundWithCollidingJournalBucketsUseOneLockOrder() throws Exception {
    UUID refundPaymentId = paymentIdWithCollidingRefundBucket(paymentId);
    var systemAccounts =
        new SystemAccountService(db.wallets, new SystemAccountConfig(db.system.toString()));
    var delegate = db.transactional(new PersistentWalletAdapter(db.wallets, systemAccounts));
    CountDownLatch paymentProgress = new CountDownLatch(1);
    CountDownLatch refundSubmitted = new CountDownLatch(1);
    CountDownLatch refundBucketLocked = new CountDownLatch(1);
    AtomicBoolean paymentReferenceLocked = new AtomicBoolean();
    com.fluxpay.common.contracts.WalletPort coordinatedWallets =
        new com.fluxpay.common.contracts.WalletPort() {
          @Override
          public Optional<com.fluxpay.dto.WalletSnapshot> findOwned(UUID userId, UUID walletId) {
            return delegate.findOwned(userId, walletId);
          }

          @Override
          public com.fluxpay.dto.PostingAccounts lockPostingAccounts(
              UUID userId, UUID walletId, String currency, BigDecimal gross) {
            var result = delegate.lockPostingAccounts(userId, walletId, currency, gross);
            if (!paymentReferenceLocked.get()) {
              paymentProgress.countDown();
              await(refundBucketLocked);
            }
            return result;
          }
        };
    db.afterJournalLock =
        ignored -> {
          if (Thread.currentThread().getName().equals("payment-worker")) {
            paymentReferenceLocked.set(true);
            paymentProgress.countDown();
            await(refundSubmitted);
          } else if (Thread.currentThread().getName().equals("refund-worker")) {
            refundBucketLocked.countDown();
          }
        };
    var payment =
        db.transactional(new PaymentPostingService(coordinatedWallets, db.journals, Clock.systemUTC()));
    var refund = db.transactional(new RefundJournalService(db.journals, db.writer));
    var workers = Executors.newFixedThreadPool(2);
    try {
      var paymentResult =
          workers.submit(
              () -> {
                Thread.currentThread().setName("payment-worker");
                return payment.postApprovedPayment(
                    paymentId,
                    db.user,
                    db.customer,
                    "USD",
                    new BigDecimal("10.0000"),
                    BigDecimal.ZERO,
                    Instant.now().plusSeconds(600));
              });
      assertThat(paymentProgress.await(5, TimeUnit.SECONDS)).isTrue();
      var refundResult =
          workers.submit(
              () -> {
                Thread.currentThread().setName("refund-worker");
                refund.refund(snapshot(refundPaymentId, "0.0000", "10.0000"));
                return true;
              });
      refundSubmitted.countDown();

      assertThat(paymentResult.get(10, TimeUnit.SECONDS)).isNotNull();
      assertThat(refundResult.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
      assertThat(db.count()).isEqualTo(4);
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void zeroFeeConfirmationOmitsTheZeroValuedEntry() {
    confirm("0.0000");
    assertThat(db.count()).isEqualTo(2);
    assertThat(db.balance(db.customer)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("100.0000");
  }

  @Test
  void insufficientFundsPreservePaymentErrorAndRollBackEarlierCredits() {
    db.jdbc.update("update wallets set balance=99 where id=?", db.customer);
    assertThatThrownBy(() -> confirm("5.0000"))
        .isInstanceOfSatisfying(
            com.fluxpay.exception.BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("WALLET_UNAVAILABLE");
              assertThat(error.status().value()).isEqualTo(422);
            });
    assertThat(db.count()).isZero();
    assertThat(db.balance(db.customer)).isEqualByComparingTo("99.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
  }

  @Test
  void feeRefundReversesEachOriginalWalletAndReplayChangesNothing() {
    db.jdbc.update("update wallets set balance=0 where id=?", db.customer);
    db.jdbc.update("update wallets set balance=95 where id=?", db.clearing);
    db.jdbc.update("update wallets set balance=5 where id=?", db.fee);
    var refund = db.transactional(new RefundJournalService(db.journals, db.writer));
    refund.refund(snapshot("5.0000"));
    refund.refund(snapshot("5.0000"));
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
    assertThat(db.count()).isEqualTo(3);
    assertThat(refund.isAlreadyRefunded(snapshot("5.0000"))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "same-account",
        "wrong-customer",
        "wrong-currency",
        "wrong-gross",
        "missing-reference",
        "wrong-reference"
      })
  void corruptOriginalPostingCannotWriteAnyRefundEntry(String corruption) {
    assertThatThrownBy(
            () -> {
              var original =
                  new PaymentPostingSnapshot(
                      corruption.equals("wrong-customer") ? UUID.randomUUID() : db.customer,
                      corruption.equals("same-account") ? db.customer : db.clearing,
                      db.fee,
                      corruption.equals("wrong-currency") ? "EUR" : "USD",
                      new BigDecimal(corruption.equals("wrong-gross") ? "99.0000" : "100.0000"),
                      new BigDecimal("95.0000"),
                      new BigDecimal("5.0000"),
                      corruption.equals("missing-reference")
                          ? null
                          : corruption.equals("wrong-reference")
                              ? "payment:other"
                              : "payment:" + paymentId);
              var payment =
                  new PaymentSnapshot(
                      paymentId.toString(),
                      db.user,
                      db.customer,
                      db.clearing,
                      new BigDecimal("100.0000"),
                      "USD",
                      "INR",
                      PaymentStatus.FAILED,
                      original);
              db.transactional(new RefundJournalService(db.journals, db.writer)).refund(payment);
            })
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.count()).isZero();
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
  }

  @Test
  void missingOriginalPostingFailsBeforeRefundWrites() {
    var payment =
        new PaymentSnapshot(
            paymentId.toString(),
            db.user,
            db.customer,
            db.clearing,
            new BigDecimal("100.0000"),
            "USD",
            "INR",
            PaymentStatus.FAILED);
    assertThatThrownBy(
            () ->
                db.transactional(new RefundJournalService(db.journals, db.writer)).refund(payment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.count()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", ""})
  void postedPaymentWithMissingSnapshotCannotBeReadAsUnposted(String json) {
    var confirmation = confirmation(new com.fasterxml.jackson.databind.ObjectMapper());
    confirmation.payment.recordPosting(json.isEmpty() ? null : json, Instant.now());
    var reader =
        new DbPaymentReader(
            confirmation.payments,
            confirmation.recipients,
            new com.fasterxml.jackson.databind.ObjectMapper());
    assertThatThrownBy(() -> reader.get(paymentId.toString()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.count()).isZero();
  }

  @Test
  void originalJournalIsPersistedAndReconstructedByConfirmation() {
    var confirmation =
        confirmation(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    confirmation.service.confirm(
        db.user,
        paymentId,
        new com.fluxpay.dto.ConfirmPaymentRequest(confirmation.quote.id()),
        "confirm-key");
    var reader =
        new DbPaymentReader(
            confirmation.payments,
            confirmation.recipients,
            new com.fasterxml.jackson.databind.ObjectMapper());
    var restored = reader.get(paymentId.toString());
    assertThat(restored.posting().originalJournalReference()).isEqualTo("payment:" + paymentId);
    assertThat(restored.posting().customerWalletId()).isEqualTo(db.customer);
    assertThat(restored.posting().clearingWalletId()).isEqualTo(db.clearing);
    assertThat(restored.posting().feeWalletId()).isEqualTo(db.fee);
    assertThat(restored.posting().gross()).isEqualByComparingTo("100.0000");
    assertThat(restored.posting().net()).isEqualByComparingTo("95.0000");
    assertThat(restored.posting().fee()).isEqualByComparingTo("5.0000");
    assertThat(restored.sourceCurrency()).isEqualTo("USD");
    assertThat(restored.targetCurrency()).isEqualTo("INR");
    db.transactional(new RefundJournalService(db.journals, db.writer)).refund(restored);
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
    assertThat(db.count()).isEqualTo(6);
  }

  @Test
  void failureSavingConfirmationMetadataRollsBackTheAlreadyPostedJournal() throws Exception {
    var mapper =
        org.mockito.Mockito.spy(
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    var confirmation = confirmation(mapper);
    org.mockito.Mockito.doAnswer(
            call -> {
              if (call.getArgument(0) instanceof com.fasterxml.jackson.databind.JsonNode node
                  && node.has("customerWalletId"))
                throw new IllegalStateException("controlled snapshot failure");
              return call.callRealMethod();
            })
        .when(mapper)
        .writeValueAsString(org.mockito.ArgumentMatchers.any());
    assertThatThrownBy(
            () ->
                confirmation.service.confirm(
                    db.user,
                    paymentId,
                    new com.fluxpay.dto.ConfirmPaymentRequest(confirmation.quote.id()),
                    "confirm-key"))
        .isInstanceOf(com.fluxpay.exception.BusinessException.class);
    assertThat(db.count()).isZero();
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
  }

  @Test
  void ambiguousSystemRoleFailsExplicitly() {
    db.seed(UUID.randomUUID(), db.system, WalletAccountRole.PAYOUT_CLEARING, "0");
    assertThatThrownBy(() -> accounts.require("USD", WalletAccountRole.PAYOUT_CLEARING))
        .isInstanceOf(SystemAccountUnavailableException.class);
    assertThat(db.count()).isZero();
  }

  @Test
  void customerRoleCannotResolveAsASystemAccount() {
    db.seed(UUID.randomUUID(), db.system, WalletAccountRole.CUSTOMER, "0");
    assertThatThrownBy(() -> accounts.require("USD", WalletAccountRole.CUSTOMER))
        .isInstanceOf(SystemAccountUnavailableException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"owner", "currency", "role"})
  void inconsistentResolvedSystemWalletCannotBeUsedForPosting(String wrongField) {
    var wallet =
        new com.fluxpay.beans.Wallet(
            wrongField.equals("owner") ? db.user : db.system,
            wrongField.equals("currency") ? "EUR" : "USD",
            wrongField.equals("role")
                ? WalletAccountRole.CUSTOMER
                : WalletAccountRole.PAYOUT_CLEARING);
    org.mockito.Mockito.when(
            db.wallets.findByUserIdAndCurrencyAndAccountRole(
                db.system, "USD", WalletAccountRole.PAYOUT_CLEARING))
        .thenReturn(Optional.of(wallet));
    assertThatThrownBy(() -> confirm("5.0000"))
        .isInstanceOf(SystemAccountUnavailableException.class);
    assertThat(db.count()).isZero();
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
  }

  void confirm(String fee) {
    var adapter = db.transactional(new PersistentWalletAdapter(db.wallets, accounts));
    var service =
        db.transactional(new PaymentPostingService(adapter, db.journals, Clock.systemUTC()));
    new org.springframework.transaction.support.TransactionTemplate(db.transactions)
        .executeWithoutResult(
            ignored ->
                service.postApprovedPayment(
                    paymentId,
                    db.user,
                    db.customer,
                    "USD",
                    new BigDecimal("100.0000"),
                    new BigDecimal(fee),
                    Instant.now().plusSeconds(600)));
  }

  PaymentSnapshot snapshot(String fee) {
    return snapshot(paymentId, fee, "100.0000");
  }

  PaymentSnapshot snapshot(UUID snapshotPaymentId, String fee, String gross) {
    return new PaymentSnapshot(
        snapshotPaymentId.toString(),
        db.user,
        db.customer,
        db.clearing,
        new BigDecimal(gross),
        "USD",
        "INR",
        PaymentStatus.FAILED,
        new PaymentPostingSnapshot(
            db.customer,
            db.clearing,
            db.fee,
            "USD",
            new BigDecimal(gross),
            fee.equals("0.0000")
                ? new BigDecimal(gross)
                : new BigDecimal(gross).subtract(new BigDecimal(fee)),
            new BigDecimal(fee),
            "payment:" + snapshotPaymentId));
  }

  private static UUID paymentIdWithCollidingRefundBucket(UUID postingPaymentId) {
    int bucket = Math.floorMod(("payment:" + postingPaymentId).hashCode(), 64);
    while (true) {
      UUID candidate = UUID.randomUUID();
      if (Math.floorMod(("refund:" + candidate).hashCode(), 64) == bucket) {
        return candidate;
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("lock-order coordination timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("lock-order coordination interrupted", interrupted);
    }
  }

  Confirmation confirmation(com.fasterxml.jackson.databind.ObjectMapper mapper) {
    var now = Instant.now();
    var recipient =
        new com.fluxpay.beans.Recipient(
            UUID.randomUUID(),
            db.user,
            "Recipient",
            "account",
            "Bank",
            "IN",
            "INR",
            com.fluxpay.beans.RecipientStatus.ACTIVE,
            now);
    var payment =
        new com.fluxpay.beans.Payment(
            paymentId,
            db.user,
            db.customer,
            recipient,
            new BigDecimal("100.0000"),
            "USD",
            "INR",
            com.fluxpay.beans.PaymentPurpose.FAMILY_SUPPORT,
            com.fluxpay.domain.RoutePreference.CHEAPEST,
            "{}",
            now);
    payment.quoted(1, now);
    var quote =
        new com.fluxpay.beans.PaymentQuote(
            UUID.randomUUID(),
            paymentId,
            1,
            "BANK",
            new BigDecimal("80"),
            BigDecimal.ZERO,
            new BigDecimal("80"),
            new BigDecimal("5.0000"),
            new BigDecimal("7600.0000"),
            240,
            true,
            now,
            now.plusSeconds(600));
    var payments = org.mockito.Mockito.mock(com.fluxpay.repository.PaymentRepository.class);
    var recipients = org.mockito.Mockito.mock(com.fluxpay.repository.RecipientRepository.class);
    var quotes = org.mockito.Mockito.mock(com.fluxpay.repository.PaymentQuoteRepository.class);
    var routes = org.mockito.Mockito.mock(com.fluxpay.repository.PayoutRouteRepository.class);
    org.mockito.Mockito.when(payments.lockOwned(paymentId, db.user))
        .thenReturn(Optional.of(payment));
    org.mockito.Mockito.when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    org.mockito.Mockito.when(recipients.lockOwned(recipient.id(), db.user))
        .thenReturn(Optional.of(recipient));
    org.mockito.Mockito.when(recipients.findByIdAndUserId(recipient.id(), db.user))
        .thenReturn(Optional.of(recipient));
    org.mockito.Mockito.when(quotes.findByIdAndPaymentId(quote.id(), paymentId))
        .thenReturn(Optional.of(quote));
    org.mockito.Mockito.when(routes.findByCode("BANK"))
        .thenReturn(
            Optional.of(
                com.fluxpay.beans.PayoutRoute.seed(
                    UUID.randomUUID(), "BANK", "Bank", "Bank", "STANDARD", "0", "5", 5, "90")));
    var adapter = db.transactional(new PersistentWalletAdapter(db.wallets, accounts));
    var posting =
        db.transactional(new PaymentPostingService(adapter, db.journals, Clock.systemUTC()));
    var service =
        db.transactional(
            new PaymentConfirmationService(
                payments,
                quotes,
                recipients,
                user -> true,
                (user, amount, currency) -> com.fluxpay.common.enums.ScreeningVerdict.APPROVE,
                posting,
                Clock.systemUTC(),
                new PaymentOperationService(
                    org.mockito.Mockito.mock(
                        com.fluxpay.repository.PaymentOperationRepository.class),
                    mapper,
                    Clock.systemUTC(),
                    db.transactions),
                org.mockito.Mockito.mock(com.fluxpay.repository.OutboxEventRepository.class),
                org.mockito.Mockito.mock(com.fluxpay.repository.OutboxDeliveryRepository.class),
                mapper,
                routes));
    return new Confirmation(service, payment, quote, payments, recipients);
  }

  record Confirmation(
      PaymentConfirmationService service,
      com.fluxpay.beans.Payment payment,
      com.fluxpay.beans.PaymentQuote quote,
      com.fluxpay.repository.PaymentRepository payments,
      com.fluxpay.repository.RecipientRepository recipients) {}
}
