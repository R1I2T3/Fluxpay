package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
