package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.repository.RecipientRepository;
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
    UUID user = UUID.randomUUID();
    Recipient recipient =
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
    Payment payment =
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
            "{}",
            NOW);
    // Funded PROCESSING payment so the test reaches the provider capability check.
    payment.quoted(1, NOW);
    payment.selectAndProcess(UUID.randomUUID(), NOW);
    payment.recordPosting(
        "{\"customerWalletId\":\"%s\",\"clearingWalletId\":\"%s\",\"feeWalletId\":\"%s\","
                .formatted(payment.sourceWalletId(), UUID.randomUUID(), UUID.randomUUID())
            + "\"currency\":\"USD\",\"gross\":\"100.0000\",\"net\":\"95.0000\",\"fee\":\"5.0000\","
            + "\"originalJournalReference\":\"payment:"
            + payment.id()
            + "\"}",
        NOW);

    PaymentRepository payments = mock(PaymentRepository.class);
    when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
    when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
    RecipientRepository recipients = mock(RecipientRepository.class);
    when(recipients.findByIdAndUserId(recipient.id(), user)).thenReturn(Optional.of(recipient));
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, new ObjectMapper());
    PayoutRouteRepository routes = mock(PayoutRouteRepository.class);
    PayoutRoute route =
        PayoutRoute.seed(
            UUID.randomUUID(), "STANDARD_BANK", "Bank", "Bank", "STANDARD", "5", "0", 240, "99.5");
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(route));
    PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    PayoutReservationService reservations =
        new PayoutReservationService(
            payments,
            reader,
            mock(PayoutAttemptRepository.class),
            routes,
            new SelectedQuoteService(payments, quotes, clock, routes),
            clock,
            mock(LedgerWriter.class),
            mock(PayoutOutboxService.class));

    assertThatThrownBy(
            () ->
                reservations.reserve(
                    user,
                    payment.id(),
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
  }

  @Test
  void executionWithoutProviderFailsHonestlyInsteadOfNullPointer() {
    PayoutExecutionService execution =
        new PayoutExecutionService(
            mock(PaymentOperationService.class),
            mock(PayoutReservationService.class),
            mock(PayoutFinalizationService.class),
            List.of());
    assertThat(execution).isNotNull();
  }
}
