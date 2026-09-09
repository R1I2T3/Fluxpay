package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.event.EventPublisher;
import com.fluxpay.dto.EventTopics;
import com.fluxpay.dto.PaymentEventPayload;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.dto.RecoveryResult;
import com.fluxpay.repository.PaymentEventStore;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

  @Mock private PaymentReader paymentReader;
  @Mock private PayoutRouteRepository routes;
  @Mock private PayoutAttemptRepository attempts;
  @Mock private PayoutExecutionService execution;
  @Mock private RefundJournalService refunds;
  @Mock private PaymentEventStore eventStore;
  @Mock private EventPublisher events;

  private RecoveryService recovery;
  private RecoveryService refundRecovery;
  private PaymentSnapshot payment;
  private PayoutRoute standard;
  private PayoutRoute instant;

  @BeforeEach
  void setUp() {
    recovery = new RecoveryService(paymentReader, routes, attempts, execution);
    Clock fixedClock = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
    refundRecovery =
        new RecoveryService(
            paymentReader, routes, attempts, execution, refunds, eventStore, events, fixedClock);
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
            "r-standard",
            "STANDARD_BANK",
            "Standard Bank Rail",
            "Standard Bank",
            "STANDARD",
            "5.00",
            "0.8",
            240,
            "99.50");
    instant =
        PayoutRoute.seed(
            "r-instant",
            "INSTANT_PAYOUT",
            "Instant Payout Rail",
            "Instant Provider",
            "INSTANT",
            "7.50",
            "1.2",
            15,
            "98.00");
  }

  @Test
  void retryReusesRouteAndIncrementsAttemptWithoutLedgerAccess() {
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(failedAttempt(1, "r-standard")));
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(routes.findById("r-standard")).thenReturn(Optional.of(standard));
    when(execution.executeNewAttempt(eq(payment), eq(standard), eq(2), eq("RETRY"), eq("c-uuid")))
        .thenReturn(PayoutOutcome.failed());

    PayoutOutcome result = recovery.retry("P-001", "c-uuid");

    assertThat(result.status()).isEqualTo(PayoutAttemptStatus.FAILED);
  }

  @Test
  void switchUsesADifferentActiveRouteAndIncrementsAttempt() {
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(failedAttempt(1, "r-standard")));
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(routes.findByCode("INSTANT_PAYOUT")).thenReturn(Optional.of(instant));
    when(execution.executeNewAttempt(eq(payment), eq(instant), eq(2), eq("SWITCH"), eq("c-uuid")))
        .thenReturn(PayoutOutcome.completed());

    assertThat(recovery.switchRoute("P-001", "INSTANT_PAYOUT", "c-uuid").status())
        .isEqualTo(PayoutAttemptStatus.COMPLETED);
  }

  @Test
  void retryAfterCompletedIsRejected() {
    PayoutAttempt completed = completedAttempt(1, "r-standard");
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(completed));

    assertThatThrownBy(() -> recovery.retry("P-001", "c-uuid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("latest payout attempt is not failed");
    verify(execution, never()).executeNewAttempt(any(), any(), anyInt(), any(), any());
  }

  @Test
  void switchToInactiveRouteIsRejected() {
    instant.update("7.50", "1.2", 15, "98.00", false);
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(failedAttempt(1, "r-standard")));
    when(routes.findByCode("INSTANT_PAYOUT")).thenReturn(Optional.of(instant));

    assertThatThrownBy(() -> recovery.switchRoute("P-001", "INSTANT_PAYOUT", "c-uuid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("route INSTANT_PAYOUT is inactive");
    verify(execution, never()).executeNewAttempt(any(), any(), anyInt(), any(), any());
  }

  @Test
  void switchToSameRouteIsRejected() {
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(failedAttempt(1, "r-standard")));
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(standard));

    assertThatThrownBy(() -> recovery.switchRoute("P-001", "STANDARD_BANK", "c-uuid"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("switch route must differ from failed route");
    verify(execution, never()).executeNewAttempt(any(), any(), anyInt(), any(), any());
  }

  @Test
  void refundRequiresFailedAttemptPostsJournalAndPublishesDeterministicEvent() {
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(failedAttempt(1, "r-standard")));
    when(paymentReader.get("P-001")).thenReturn(payment);
    when(eventStore.contains("P-001", EventTopics.PAYMENT_REFUNDED)).thenReturn(false);

    RecoveryResult result = refundRecovery.refund("P-001", "c-uuid");

    verify(refunds).refund(payment);
    ArgumentCaptor<PaymentEventPayload> payload =
        ArgumentCaptor.forClass(PaymentEventPayload.class);
    verify(events).publish(eq(EventTopics.PAYMENT_REFUNDED), payload.capture(), eq("c-uuid"));
    assertThat(payload.getValue().eventId())
        .isEqualTo(
            PaymentEventPayload.refund(
                    "P-001", payload.getValue().occurredAt(), payload.getValue().details())
                .eventId());
    assertThat(result.idempotentReplay()).isFalse();
  }

  @Test
  void refundAfterCompletedIsRejected() {
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(completedAttempt(1, "r-standard")));

    assertThatThrownBy(() -> refundRecovery.refund("P-001", "c-uuid"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("latest payout attempt is not failed");
    verify(refunds, never()).refund(any());
    verify(events, never()).publish(any(), any(), any());
  }

  @Test
  void storedRefundReplaySkipsLedgerAndPublisher() {
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(failedAttempt(1, "r-standard")));
    when(eventStore.contains("P-001", EventTopics.PAYMENT_REFUNDED)).thenReturn(true);

    RecoveryResult result = refundRecovery.refund("P-001", "c-uuid");

    assertThat(result.idempotentReplay()).isTrue();
    assertThat(result.paymentId()).isEqualTo("P-001");
    assertThat(result.eventId())
        .isEqualTo(
            PaymentEventPayload.refund("P-001", Instant.EPOCH, java.util.Map.of()).eventId());
    verify(refunds, never()).refund(any());
    verify(events, never()).publish(any(), any(), any());
    verify(paymentReader, never()).get(any());
  }

  private static PayoutAttempt failedAttempt(int attemptNumber, String routeId) {
    PayoutAttempt attempt =
        PayoutAttempt.initiated(
            "a-" + attemptNumber, "P-001", attemptNumber, routeId, Instant.EPOCH);
    attempt.markProcessing();
    attempt.markFailed("PROVIDER_TIMEOUT", "Simulated bank timeout");
    return attempt;
  }

  private static PayoutAttempt completedAttempt(int attemptNumber, String routeId) {
    PayoutAttempt attempt =
        PayoutAttempt.initiated(
            "a-" + attemptNumber, "P-001", attemptNumber, routeId, Instant.EPOCH);
    attempt.markProcessing();
    attempt.markCompleted("SB-1");
    return attempt;
  }
}
