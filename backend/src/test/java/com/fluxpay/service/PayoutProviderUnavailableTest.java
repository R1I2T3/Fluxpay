package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.RecipientRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Provider capability is validated before entering an operation that would otherwise claim a
 * completed external action: an unknown provider fails with 503 and reserves nothing.
 */
class PayoutProviderUnavailableTest {
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  @Test
  void unknownProviderFailsWith503BeforeReservingAnAttempt() {
    Fixture fixture = new Fixture();
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    PayoutReservationService reservations = fixture.reservations(attempts);

    assertThatThrownBy(
            () ->
                reservations.reserve(
                    fixture.user,
                    fixture.payment.id(),
                    "SUBMIT",
                    "STANDARD_BANK",
                    null,
                    "capability",
                    code -> false))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("PAYOUT_PROVIDER_UNAVAILABLE");
            });
    verify(attempts, never()).saveAndFlush(any());
  }

  @Test
  void inactiveRouteFailsWith503RouteUnavailable() {
    Fixture fixture = new Fixture();
    fixture.route.update("5", "0", 240, "99.5", false);
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    PayoutReservationService reservations = fixture.reservations(attempts);

    assertThatThrownBy(
            () ->
                reservations.reserve(
                    fixture.user,
                    fixture.payment.id(),
                    "SUBMIT",
                    "STANDARD_BANK",
                    null,
                    "capability",
                    code -> true))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("PAYOUT_ROUTE_UNAVAILABLE");
            });
    verify(attempts, never()).saveAndFlush(any());
  }

  @Test
  void missingRouteFailsWith503RouteUnavailable() {
    Fixture fixture = new Fixture();
    when(fixture.routes.findByRouteCode("STANDARD_BANK")).thenReturn(Optional.empty());
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    PayoutReservationService reservations = fixture.reservations(attempts);

    assertThatThrownBy(
            () ->
                reservations.reserve(
                    fixture.user,
                    fixture.payment.id(),
                    "SUBMIT",
                    "STANDARD_BANK",
                    null,
                    "capability",
                    code -> true))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("PAYOUT_ROUTE_UNAVAILABLE");
            });
    verify(attempts, never()).saveAndFlush(any());
  }

  @Test
  void executionWithoutProviderFailsHonestlyInsteadOfNullPointer() {
    Fixture fixture = new Fixture();
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    // Genuine reservation with the provider present at reserve time.
    PayoutReservationService.Reserved reserved =
        fixture
            .reservations(attempts)
            .reserve(
                fixture.user,
                fixture.payment.id(),
                "SUBMIT",
                "STANDARD_BANK",
                null,
                "capability",
                code -> true);

    PaymentOperationService operations = mock(PaymentOperationService.class);
    PayoutReservationService reservations = mock(PayoutReservationService.class);
    PayoutFinalizationService finalization = mock(PayoutFinalizationService.class);
    when(reservations.reserve(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(reserved);
    when(operations.reserve(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Runnable validate = invocation.getArgument(6);
              validate.run();
              return new PaymentOperationService.Reservation<>(UUID.randomUUID(), null, null);
            });
    // The provider disappeared after reservation: the execution map is empty.
    PayoutExecutionService execution =
        new PayoutExecutionService(operations, reservations, finalization, List.of());

    assertThatThrownBy(
            () ->
                execution.perform(
                    fixture.user,
                    "key-1",
                    "SUBMIT",
                    fixture.payment.id(),
                    "STANDARD_BANK",
                    null,
                    "cid-1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("PAYOUT_PROVIDER_UNAVAILABLE");
            });
    // Expected state: no finalization ran, so no money moved and no terminal outbox event exists;
    // the operation stays pending and the attempt stays PROCESSING, so a same-key retry is
    // retryable (OPERATION_IN_PROGRESS) and exactly one reservation exists — never a second
    // attempt.
    verify(finalization, never()).finish(any(), any(), any(), any());
    verify(reservations, times(1)).reserve(any(), any(), any(), any(), any(), any(), any());
    assertThat(fixture.payment.status()).isEqualTo(PaymentStatus.PROCESSING);
  }

  /** Funded PROCESSING payment reaching the provider/route capability checks. */
  private static final class Fixture {
    final UUID user = UUID.randomUUID();
    final UUID quoteId = UUID.randomUUID();
    final Recipient recipient =
        new Recipient(
            UUID.randomUUID(),
            user,
            "Recipient",
            "account",
            "Bank",
            "IN",
            "INR",
            RecipientStatus.ACTIVE,
            NOW);
    final Payment payment =
        new Payment(
            UUID.randomUUID(),
            user,
            UUID.randomUUID(),
            recipient,
            new BigDecimal("100.0000"),
            "USD",
            "INR",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.CHEAPEST,
            QuoteEntryPointsTest.snapshot("IN", "INR"),
            NOW);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    final TransferRouteRepository routes = mock(TransferRouteRepository.class);
    final RecipientRepository recipients = mock(RecipientRepository.class);
    final TransferRoute route =
        TransferRoute.seed(
            UUID.randomUUID(), "STANDARD_BANK", "Bank", "Bank", "STANDARD", "5", "0", 240, "99.5");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    Fixture() {
      payment.quoted(1, NOW);
      payment.selectAndProcess(quoteId, NOW);
      payment.recordPosting(
          "{\"customerWalletId\":\"%s\",\"clearingWalletId\":\"%s\",\"feeWalletId\":\"%s\","
                  .formatted(payment.sourceWalletId(), UUID.randomUUID(), UUID.randomUUID())
              + "\"currency\":\"USD\",\"gross\":\"100.0000\",\"net\":\"95.0000\",\"fee\":\"5.0000\","
              + "\"originalJournalReference\":\"payment:"
              + payment.id()
              + "\"}",
          NOW);
      when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
      when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
      when(recipients.findByIdAndUserId(recipient.id(), user)).thenReturn(Optional.of(recipient));
      DbPaymentReader reader = new DbPaymentReader(payments, recipients, new ObjectMapper());
      when(routes.findByRouteCode("STANDARD_BANK")).thenReturn(Optional.of(route));
      when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(payment.id(), 1))
          .thenReturn(
              List.of(
                  new PaymentQuote(
                      quoteId,
                      payment.id(),
                      1,
                      "STANDARD_BANK",
                      new BigDecimal("80"),
                      BigDecimal.ZERO,
                      new BigDecimal("80"),
                      new BigDecimal("5"),
                      new BigDecimal("7600"),
                      1,
                      true,
                      NOW,
                      NOW.plusSeconds(300))));
    }

    PayoutReservationService reservations(PayoutAttemptRepository attempts) {
      return new PayoutReservationService(
          payments,
          new DbPaymentReader(payments, recipients, new ObjectMapper()),
          attempts,
          routes,
          new SelectedQuoteService(payments, quotes, clock, routes),
          clock,
          mock(LedgerWriter.class),
          mock(PayoutOutboxService.class));
    }
  }
}
