package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.common.contracts.PaymentEligibilityGate;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.exception.QuoteMismatchException;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DbPaymentEligibilityGateTest extends DbPaymentEligibilityGateFixture {
  private PaymentRepository payments;
  private PaymentQuoteRepository quotes;
  private PaymentOperationRepository operations;
  private DbPaymentEligibilityGate gate;
  private UUID paymentId;
  private UUID userId;
  private PaymentSnapshot snapshot;
  private com.fluxpay.repository.PayoutRouteRepository routes;
  private com.fluxpay.beans.PayoutRoute standard;

  @BeforeEach
  void setUp() {
    payments = mock(PaymentRepository.class);
    quotes = mock(PaymentQuoteRepository.class);
    operations = mock(PaymentOperationRepository.class);
    routes = mock(com.fluxpay.repository.PayoutRouteRepository.class);
    standard =
        com.fluxpay.beans.PayoutRoute.seed(
            UUID.randomUUID(), "STANDARD_BANK", "Bank", "Bank", "STANDARD", "5", "0", 240, "99.5");
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(standard));
    gate =
        new DbPaymentEligibilityGate(
            operations,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SelectedQuoteService(payments, quotes, Clock.fixed(NOW, ZoneOffset.UTC), routes));
    paymentId = UUID.randomUUID();
    userId = UUID.randomUUID();
    snapshot =
        new PaymentSnapshot(
            paymentId.toString(),
            userId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal("10.00"),
            "USD",
            "KES",
            PaymentStatus.ROUTED);
  }

  @Test
  void activeQuoteForRequestedRoutePasses() {
    var payment = storedPayment(paymentId, userId, 3);
    var selected = quote(paymentId, 3, "STANDARD_BANK");
    payment.selectAndProcess(selected.id(), NOW);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(quote(paymentId, 3, "LOCAL_PARTNER"), selected));

    gate.assertActiveQuote(snapshot, "STANDARD_BANK");
  }

  @Test
  void wrongRouteThrowsMismatch() {
    var payment = storedPayment(paymentId, userId, 3);
    var selected = quote(paymentId, 3, "STANDARD_BANK");
    payment.selectAndProcess(selected.id(), NOW);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(selected));

    assertThrows(
        QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "LOCAL_PARTNER"));
  }

  @Test
  void missingGenerationThrowsExpired() {
    when(payments.findById(paymentId)).thenReturn(Optional.of(unquotedPayment(paymentId, userId)));

    assertThrows(
        QuoteExpiredException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void anotherOwnerCannotExecuteSelectedQuote() {
    var payment = storedPayment(paymentId, UUID.randomUUID(), 3);
    var selected = quote(paymentId, 3, "STANDARD_BANK");
    payment.selectAndProcess(selected.id(), NOW);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(selected));
    assertThrows(
        QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void quoteFromOlderGenerationCannotExecute() {
    var payment = storedPayment(paymentId, userId, 3);
    var selected = quote(paymentId, 2, "STANDARD_BANK");
    payment.selectAndProcess(selected.id(), NOW);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(selected));
    assertThrows(
        QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void anotherQuoteOnSameRouteCannotReplaceSelectedQuote() {
    var payment = storedPayment(paymentId, userId, 3);
    payment.selectAndProcess(UUID.randomUUID(), NOW);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(quote(paymentId, 3, "STANDARD_BANK")));
    assertThrows(
        QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void noSelectedQuoteCannotExecute() {
    when(payments.findById(paymentId)).thenReturn(Optional.of(storedPayment(paymentId, userId, 3)));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(quote(paymentId, 3, "STANDARD_BANK")));
    assertThrows(
        QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void disabledSelectedRouteCannotExecute() {
    var payment = storedPayment(paymentId, userId, 3);
    var selected = quote(paymentId, 3, "STANDARD_BANK");
    payment.selectAndProcess(selected.id(), NOW);
    standard.update("5", "0", 240, "99.5", false);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(selected));
    assertThrows(
        QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void expiryAtExactBoundaryCannotExecute() {
    var payment = storedPayment(paymentId, userId, 3);
    var selected =
        new com.fluxpay.beans.PaymentQuote(
            UUID.randomUUID(),
            paymentId,
            3,
            "STANDARD_BANK",
            new BigDecimal("80"),
            BigDecimal.ZERO,
            new BigDecimal("80"),
            new BigDecimal("5"),
            new BigDecimal("400"),
            240,
            true,
            NOW.minusSeconds(900),
            NOW);
    payment.selectAndProcess(selected.id(), NOW);
    when(payments.findById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(selected));
    assertThrows(
        QuoteExpiredException.class, () -> gate.assertActiveQuote(snapshot, "STANDARD_BANK"));
  }

  @Test
  void firstConfirmCreatesPendingReservation() {
    when(operations.findByUserIdAndOperationTypeAndClientKey(userId, "PAYOUT_CONFIRM", "key"))
        .thenReturn(Optional.empty());

    PaymentEligibilityGate.ConfirmOutcome outcome = gate.confirmIdempotent(snapshot, "key");

    assertFalse(outcome.alreadyConfirmed());
    ArgumentCaptor<PaymentOperation> pending = ArgumentCaptor.forClass(PaymentOperation.class);
    verify(operations).saveAndFlush(pending.capture());
    assertEquals("", pending.getValue().responseData());
    assertEquals(202, pending.getValue().outcomeStatus());
  }

  @Test
  void completedReservationReplaysOriginalEvent() {
    PaymentOperation done =
        new PaymentOperation(
            UUID.randomUUID(),
            userId,
            "PAYOUT_CONFIRM",
            "key",
            paymentId.toString(),
            200,
            "evt-1",
            paymentId,
            NOW);
    when(operations.findByUserIdAndOperationTypeAndClientKey(userId, "PAYOUT_CONFIRM", "key"))
        .thenReturn(Optional.of(done));

    PaymentEligibilityGate.ConfirmOutcome outcome = gate.confirmIdempotent(snapshot, "key");

    assertTrue(outcome.alreadyConfirmed());
    assertEquals("evt-1", outcome.originalEventId());
    verify(operations, never()).saveAndFlush(any());
  }

  @Test
  void pendingReservationBlocksSecondConfirm() {
    PaymentOperation pending =
        new PaymentOperation(
            UUID.randomUUID(),
            userId,
            "PAYOUT_CONFIRM",
            "key",
            paymentId.toString(),
            202,
            "",
            paymentId,
            NOW);
    when(operations.findByUserIdAndOperationTypeAndClientKey(userId, "PAYOUT_CONFIRM", "key"))
        .thenReturn(Optional.of(pending));

    assertThrows(IllegalStateException.class, () -> gate.confirmIdempotent(snapshot, "key"));
  }

  @Test
  void completeStoresPublishedEventId() {
    PaymentOperation pending =
        new PaymentOperation(
            UUID.randomUUID(),
            userId,
            "PAYOUT_CONFIRM",
            "key",
            paymentId.toString(),
            202,
            "",
            paymentId,
            NOW);
    when(operations.findByUserIdAndOperationTypeAndClientKey(userId, "PAYOUT_CONFIRM", "key"))
        .thenReturn(Optional.of(pending));

    gate.complete(snapshot, "key", "evt-9");

    ArgumentCaptor<PaymentOperation> done = ArgumentCaptor.forClass(PaymentOperation.class);
    verify(operations).saveAndFlush(done.capture());
    assertEquals("evt-9", done.getValue().responseData());
    assertEquals(pending.id(), done.getValue().id());
  }

  @Test
  void releaseDeletesOnlyPendingReservation() {
    PaymentOperation pending =
        new PaymentOperation(
            UUID.randomUUID(),
            userId,
            "PAYOUT_CONFIRM",
            "key",
            paymentId.toString(),
            202,
            "",
            paymentId,
            NOW);
    when(operations.findByUserIdAndOperationTypeAndClientKey(userId, "PAYOUT_CONFIRM", "key"))
        .thenReturn(Optional.of(pending));

    gate.release(snapshot, "key");

    verify(operations).delete(pending);
  }
}
