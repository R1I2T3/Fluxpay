package com.fluxpay.messaging;

import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.repository.*;
import com.fluxpay.service.*;
import java.time.Clock;
import java.util.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Durable recovery commands are delivered through the common outbox. */
@Component
public class PayoutRetryConsumer {
  private final PaymentOperationService operations;
  private final PaymentOperationRepository operationRepository;
  private final PaymentRepository payments;
  private final PayoutAttemptRepository attempts;
  private final PayoutExecutionService execution;
  private final RecoveryService recovery;
  private final PayoutOutboxService outbox;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public PayoutRetryConsumer(
      PaymentOperationService operations,
      PaymentOperationRepository operationRepository,
      PaymentRepository payments,
      PayoutAttemptRepository attempts,
      PayoutExecutionService execution,
      RecoveryService recovery,
      PayoutOutboxService outbox,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.operations = operations;
    this.operationRepository = operationRepository;
    this.payments = payments;
    this.attempts = attempts;
    this.execution = execution;
    this.recovery = recovery;
    this.outbox = outbox;
    this.codec = codec;
    this.clock = clock;
  }

  @KafkaListener(
      topics = {EventTopics.PAYOUT_FAILED, EventTopics.PAYOUT_RETRY, EventTopics.PAYOUT_REFUND},
      groupId = "${fluxpay.kafka.recovery-group:fluxpay-recovery}",
      concurrency = "1")
  public void onEvent(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    var event = codec.read(json);
    if (!topic.equals(event.eventType()))
      throw new IllegalArgumentException("Recovery topic does not match envelope");
    if (EventTopics.PAYOUT_FAILED.equals(topic)) schedule(event);
    else if (EventTopics.PAYOUT_RETRY.equals(topic) || EventTopics.PAYOUT_REFUND.equals(topic))
      execute(event);
  }

  private void schedule(PaymentEventEnvelope event) {
    UUID id = UUID.fromString(event.paymentId());
    UUID sender = payments.findById(id).orElseThrow().senderId();
    int failedAttempt = ((Number) event.payload().get("attempt")).intValue();
    operations.execute(
        sender,
        "auto:schedule:" + id + ":" + failedAttempt,
        "AUTO_SCHEDULE",
        id,
        Map.of("attempt", failedAttempt),
        Map.class,
        () -> {
          var payment = payments.lockOwned(id, sender).orElseThrow();
          var latest =
              attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(id.toString()).orElseThrow();
          if (payment.status() != PaymentStatus.FAILED
              || latest.status() != PayoutAttemptStatus.FAILED
              || latest.attemptNumber() != failedAttempt)
            return new PaymentOperationService.Result<>(200, Map.of("skipped", true), id);
          long completedRetries =
              operationRepository.countByPaymentIdAndOperationType(id, "AUTO_RETRY");
          boolean terminal = completedRetries >= 5;
          int ordinal = terminal ? 5 : (int) completedRetries + 1;
          var nextRun = terminal ? latest.completedAt() : latest.completedAt().plusSeconds(120);
          var eventId =
              outbox.enqueue(
                  payment,
                  terminal ? EventTopics.PAYOUT_REFUND : EventTopics.PAYOUT_RETRY,
                  event.correlationId(),
                  Map.of(
                      "paymentId",
                      id.toString(),
                      "attemptCount",
                      ordinal,
                      "failedAttempt",
                      failedAttempt,
                      "nextRun",
                      nextRun.toString()),
                  nextRun);
          return new PaymentOperationService.Result<>(200, Map.of("eventId", eventId), id);
        });
  }

  private void execute(PaymentEventEnvelope event) {
    var nextRun = java.time.Instant.parse((String) event.payload().get("nextRun"));
    if (clock.instant().isBefore(nextRun))
      throw new IllegalStateException("Recovery command is not due yet");
    UUID id = UUID.fromString(event.paymentId());
    UUID sender = payments.findById(id).orElseThrow().senderId();
    int ordinal = ((Number) event.payload().get("attemptCount")).intValue();
    int failedAttempt = ((Number) event.payload().get("failedAttempt")).intValue();
    if (EventTopics.PAYOUT_RETRY.equals(event.eventType())) {
      try {
        execution.performAutomatic(sender, id, failedAttempt, ordinal, event.correlationId());
      } catch (com.fluxpay.exception.BusinessException ex) {
        // Uncertain delivery is retained for explicit reconciliation, never a fresh payout.
        if (!Set.of("STALE_RECOVERY", "OPERATION_IN_PROGRESS", "PAYOUT_PENDING_RECONCILIATION")
            .contains(ex.code())) throw ex;
      }
    } else {
      if (ordinal != 5)
        throw new IllegalArgumentException("Refund requires all five automated retries");
      try {
        operations.execute(
            sender,
            "auto:refund:" + id + ":5",
            "AUTO_REFUND",
            id,
            Map.of(),
            Map.class,
            () -> {
              var payment = payments.lockOwned(id, sender).orElseThrow();
              var latest =
                  attempts
                      .findFirstByPaymentIdOrderByAttemptNumberDesc(id.toString())
                      .orElseThrow();
              if (payment.status() != PaymentStatus.FAILED
                  || latest.status() != PayoutAttemptStatus.FAILED
                  || latest.attemptNumber() != failedAttempt)
                throw new com.fluxpay.exception.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "STALE_RECOVERY",
                    "A later payout or refund superseded this recovery command.");
              if (operationRepository.countByPaymentIdAndOperationType(id, "AUTO_RETRY") != 5)
                throw new IllegalStateException("Refund requires all five automated retries");
              var result = recovery.refundFunded(sender, id, event.correlationId());
              return new PaymentOperationService.Result<>(
                  200, Map.of("eventId", result.eventId()), id);
            });
      } catch (com.fluxpay.exception.BusinessException ex) {
        // Roll back a stale reservation so a later definitive failure can still refund.
        if (!"STALE_RECOVERY".equals(ex.code())) throw ex;
      }
    }
  }
}
