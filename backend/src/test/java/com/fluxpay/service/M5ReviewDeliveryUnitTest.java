package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.M5ReviewDecision;
import com.fluxpay.dto.M5DeliveryAck;
import com.fluxpay.dto.M5ReviewCommand;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class M5ReviewDeliveryUnitTest {
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final M5ComplianceLifecycleContractTest.MemoryStore store =
      new M5ComplianceLifecycleContractTest.MemoryStore();
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private M5ReviewCommand command;

  @BeforeEach
  void setUp() {
    when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
    command = new M5ReviewCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "APPROVE", UUID.randomUUID(),
        NOW, "Evidence reviewed");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACKNOWLEDGED", "CONFLICT"})
  void validLateAcknowledgmentSurvivesConcurrentFinalFailure(String terminalState) {
    store.insertDecision(new M5ReviewDecision(command, "PENDING", 7, NOW, null));
    var unsuccessfulWorker = delivery(value -> { throw new IllegalStateException("unavailable"); });
    var successfulWorker = delivery(value -> {
      // Deterministic interleaving: another worker finishes after this worker captured retry seven.
      assertEquals(1, unsuccessfulWorker.deliverPending(1));
      assertEquals(8, current().retryCount());
      assertEquals("PENDING", current().deliveryState());
      return new M5DeliveryAck(value.decisionId(), terminalState);
    });
    assertEquals(1, successfulWorker.deliverPending(1));
    assertEquals(terminalState, current().deliveryState());
    assertEquals(command, current().command());
    assertEquals(8, current().retryCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"WRONG_ID", "UNKNOWN_STATUS", "NO_RESPONSE"})
  void invalidAcknowledgmentCannotMarkTheDecisionDelivered(String scenario) {
    store.insertDecision(new M5ReviewDecision(command, "PENDING", 0, NOW, null));
    var worker = delivery(value -> switch (scenario) {
      case "WRONG_ID" -> new M5DeliveryAck(UUID.randomUUID(), "ACKNOWLEDGED");
      case "UNKNOWN_STATUS" -> new M5DeliveryAck(value.decisionId(), "ACCEPTED");
      default -> null;
    });
    assertEquals(1, worker.deliverPending(1));
    assertEquals("PENDING", current().deliveryState());
    assertEquals(1, current().retryCount());
    assertEquals("DELIVERY_UNAVAILABLE", current().lastErrorCode());
    assertEquals(command, current().command());
    assertEquals(0, worker.deliverPending(1));
  }

  @Test
  void staleFailureDoesNotConsumeAnotherRetry() {
    store.insertDecision(new M5ReviewDecision(command, "PENDING", 0, NOW, null));
    var firstFailure = delivery(value -> null);
    var secondFailure = delivery(value -> {
      assertEquals(1, firstFailure.deliverPending(1));
      return null;
    });
    assertEquals(1, secondFailure.deliverPending(1));
    assertEquals(1, current().retryCount());
    assertEquals("PENDING", current().deliveryState());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACKNOWLEDGED", "CONFLICT"})
  void lateWorkerCannotReplaceAnExistingTerminalState(String firstState) {
    store.insertDecision(new M5ReviewDecision(command, "PENDING", 0, NOW, null));
    var firstWorker = delivery(value -> new M5DeliveryAck(value.decisionId(), firstState));
    var lateWorker = delivery(value -> {
      assertEquals(1, firstWorker.deliverPending(1));
      return new M5DeliveryAck(value.decisionId(),
          firstState.equals("ACKNOWLEDGED") ? "CONFLICT" : "ACKNOWLEDGED");
    });
    assertEquals(1, lateWorker.deliverPending(1));
    assertEquals(firstState, current().deliveryState());
    assertEquals(command, current().command());
  }

  @Test
  void exhaustedFailuresStopFurtherDeliveryAttempts() {
    store.insertDecision(new M5ReviewDecision(command, "PENDING", 0, NOW, null));
    var calls = new AtomicInteger();
    M5ReviewDecisionSink unavailable = value -> { calls.incrementAndGet(); return null; };
    for (int attempt = 0; attempt < 8; attempt++) {
      var later = new M5ReviewDeliveryService(store, unavailable, transactions,
          Clock.offset(clock, Duration.ofHours(attempt)));
      assertEquals(1, later.deliverPending(1));
      assertEquals(attempt + 1, current().retryCount());
    }
    var later = new M5ReviewDeliveryService(store, unavailable, transactions,
        Clock.offset(clock, Duration.ofDays(1)));
    assertEquals(0, later.deliverPending(1));
    assertEquals(8, calls.get());
    assertEquals(command, current().command());
  }

  private M5ReviewDecision current() {
    return store.decision(command.decisionId(), false).orElseThrow();
  }

  private M5ReviewDeliveryService delivery(M5ReviewDecisionSink sink) {
    return new M5ReviewDeliveryService(store, sink, transactions, clock);
  }
}
