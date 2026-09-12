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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.M3PaymentOperation;
import com.fluxpay.beans.QuoteRoute;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.exception.QuoteMismatchException;
import com.fluxpay.repository.M3PaymentOperationRepository;
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
  private M3PaymentOperationRepository operations;
  private DbPaymentEligibilityGate gate;
  private UUID paymentId;
  private UUID userId;
  private PaymentSnapshot snapshot;

  @BeforeEach
  void setUp() {
    payments = mock(PaymentRepository.class);
    quotes = mock(PaymentQuoteRepository.class);
    operations = mock(M3PaymentOperationRepository.class);
    gate =
        new DbPaymentEligibilityGate(
            payments, quotes, operations, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
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
    when(payments.findById(paymentId)).thenReturn(Optional.of(storedPayment(paymentId, userId, 3)));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(
            List.of(
                quote(paymentId, 3, QuoteRoute.CHEAPEST),
                quote(paymentId, 3, QuoteRoute.BALANCED)));

    gate.assertActiveQuote(snapshot, "BALANCED");
  }

  @Test
  void wrongRouteThrowsMismatch() {
    when(payments.findById(paymentId)).thenReturn(Optional.of(storedPayment(paymentId, userId, 3)));
    when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(paymentId, 3))
        .thenReturn(List.of(quote(paymentId, 3, QuoteRoute.BALANCED)));

    assertThrows(QuoteMismatchException.class, () -> gate.assertActiveQuote(snapshot, "CHEAPEST"));
  }

  @Test
  void missingGenerationThrowsExpired() {
    when(payments.findById(paymentId)).thenReturn(Optional.of(unquotedPayment(paymentId, userId)));

    assertThrows(QuoteExpiredException.class, () -> gate.assertActiveQuote(snapshot, "BALANCED"));
  }

  @Test
  void firstConfirmCreatesPendingReservation() {
    when(operations.findByUserIdAndOperationTypeAndClientKey(userId, "PAYOUT_CONFIRM", "key"))
        .thenReturn(Optional.empty());

    PaymentEligibilityGate.ConfirmOutcome outcome = gate.confirmIdempotent(snapshot, "key");

    assertFalse(outcome.alreadyConfirmed());
    ArgumentCaptor<M3PaymentOperation> pending = ArgumentCaptor.forClass(M3PaymentOperation.class);
    verify(operations).saveAndFlush(pending.capture());
    assertEquals("", pending.getValue().responseData());
    assertEquals(202, pending.getValue().outcomeStatus());
  }

  @Test
  void completedReservationReplaysOriginalEvent() {
    M3PaymentOperation done =
        new M3PaymentOperation(
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
    M3PaymentOperation pending =
        new M3PaymentOperation(
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
    M3PaymentOperation pending =
        new M3PaymentOperation(
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

    ArgumentCaptor<M3PaymentOperation> done = ArgumentCaptor.forClass(M3PaymentOperation.class);
    verify(operations).saveAndFlush(done.capture());
    assertEquals("evt-9", done.getValue().responseData());
    assertEquals(pending.id(), done.getValue().id());
  }

  @Test
  void releaseDeletesOnlyPendingReservation() {
    M3PaymentOperation pending =
        new M3PaymentOperation(
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
