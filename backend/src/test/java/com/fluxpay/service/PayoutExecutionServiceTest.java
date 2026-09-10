package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.event.EventPublisher;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventPayload;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.dto.RecoveryAction;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PayoutExecutionServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

  @Mock private PaymentReader paymentReader;
  @Mock private PayoutRouteRepository routes;
  @Mock private PayoutAttemptRepository attempts;
  @Mock private EventPublisher events;
  @Mock private PayoutProvider provider;

  private PayoutExecutionService service;
  private PaymentSnapshot payment;
  private PayoutRoute standard;

  @BeforeEach
  void setUp() {
    when(provider.code()).thenReturn("STANDARD_BANK");
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service =
        new PayoutExecutionService(
            paymentReader, routes, attempts, events, List.of(provider), clock);
    payment =
        new PaymentSnapshot(
            "P-001",
            UUID.nameUUIDFromBytes("fluxpay:P-001:user".getBytes()),
            UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes()),
            UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes()),
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentStatus.ROUTED);
    standard =
        PayoutRoute.seed(
            UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8)),
            "STANDARD_BANK",
            "Standard Bank Rail",
            "Standard Bank",
            "STANDARD",
            "5.00",
            "0.8",
            240,
            "99.50");
  }

  @Test
  void failedSubmitFlushesAttemptAndEmitsSelectedSubmittedFailed() {
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(standard));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.empty());
    when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(provider.submit(any()))
        .thenReturn(
            PayoutResult.failed(
                "PROVIDER_TIMEOUT", "Simulated bank timeout", new BigDecimal("5.00")));

    PayoutOutcome outcome = service.submit("P-001", "STANDARD_BANK", "c-uuid");

    assertThat(outcome.status()).isEqualTo(PayoutAttemptStatus.FAILED);
    assertThat(outcome.allowed())
        .containsExactly(RecoveryAction.RETRY, RecoveryAction.SWITCH, RecoveryAction.REFUND);
    InOrder order = inOrder(events);
    order
        .verify(events)
        .publish(
            eq(EventTopics.PAYMENT_ROUTE_SELECTED), any(PaymentEventPayload.class), eq("c-uuid"));
    order
        .verify(events)
        .publish(eq(EventTopics.PAYOUT_SUBMITTED), any(PaymentEventPayload.class), eq("c-uuid"));
    order
        .verify(events)
        .publish(eq(EventTopics.PAYOUT_FAILED), any(PaymentEventPayload.class), eq("c-uuid"));
  }

  @Test
  void successfulSubmitEmitsCompletedAndHasNoAllowedActions() {
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(standard));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.empty());
    when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(provider.submit(any())).thenReturn(PayoutResult.ok("SB-1", new BigDecimal("5.00")));

    PayoutOutcome outcome = service.submit("P-001", "STANDARD_BANK", "c-uuid");

    assertThat(outcome.status()).isEqualTo(PayoutAttemptStatus.COMPLETED);
    assertThat(outcome.allowed()).isEmpty();
    verify(events)
        .publish(eq(EventTopics.PAYOUT_COMPLETED), any(PaymentEventPayload.class), eq("c-uuid"));
  }

  @Test
  void inactiveRouteThrowsBeforeAttemptSave() {
    standard.update("5.00", "0.8", 240, "99.50", false);
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(standard));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submit("P-001", "STANDARD_BANK", "c-uuid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("route STANDARD_BANK is inactive");
    verify(attempts, never()).saveAndFlush(any());
  }

  @Test
  void missingProviderThrowsBeforeAttemptSave() {
    PayoutExecutionService withoutProvider =
        new PayoutExecutionService(
            paymentReader, routes, attempts, events, List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(standard));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> withoutProvider.submit("P-001", "STANDARD_BANK", "c-uuid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("provider STANDARD_BANK is unavailable");
    verify(attempts, never()).saveAndFlush(any());
  }

  @Test
  void completedPaymentIsNotPayoutEligible() {
    PaymentSnapshot completed =
        new PaymentSnapshot(
            payment.paymentId(),
            payment.senderUserId(),
            payment.senderWalletId(),
            payment.payoutClearingWalletId(),
            payment.amount(),
            payment.sourceCurrency(),
            payment.targetCurrency(),
            PaymentStatus.COMPLETED);
    when(paymentReader.get("P-001")).thenReturn(completed);

    assertThatThrownBy(() -> service.submit("P-001", "STANDARD_BANK", "c-uuid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payment P-001 is not payout eligible");
  }
}
