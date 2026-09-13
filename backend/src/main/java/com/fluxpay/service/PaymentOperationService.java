package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.common.json.OperationJson;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentOperationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns durable payment request identities and response snapshots. */
@Service
public class PaymentOperationService {
  private final PaymentOperationRepository operations;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public PaymentOperationService(
      PaymentOperationRepository operations,
      ObjectMapper mapper,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.operations = operations;
    this.mapper = mapper;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactions);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public record Reservation<T>(UUID id, T response, Integer httpStatus) {
    public boolean replayed() {
      return httpStatus != null;
    }
  }

  public record Result<T>(int httpStatus, T response, UUID paymentId) {}

  /** Database-only mutations and their response commit together. No provider calls belong here. */
  public <T> Result<T> execute(
      UUID user,
      String key,
      String action,
      UUID payment,
      Object request,
      Class<T> responseType,
      Supplier<Result<T>> work) {
    requireKey(key);
    String normalized = normalized(action, payment, request);
    try {
      return transaction.execute(
          ignored -> {
            var existing = operations.findByUserIdAndClientKey(user, key);
            if (existing.isPresent()) {
              var replay = replay(existing.get(), payment, normalized, responseType);
              return new Result<>(
                  replay.httpStatus(), replay.response(), existing.get().paymentId());
            }
            var pending =
                new PaymentOperation(
                    UUID.randomUUID(),
                    user,
                    action,
                    key,
                    normalized,
                    null,
                    null,
                    payment,
                    Instant.now(clock));
            operations.saveAndFlush(pending);
            var result = work.get();
            pending.complete(result.httpStatus(), json(result.response()), result.paymentId());
            operations.saveAndFlush(pending);
            return result;
          });
    } catch (org.springframework.dao.DataIntegrityViolationException race) {
      return transaction.execute(
          ignored -> {
            var winner = operations.findByUserIdAndClientKey(user, key).orElseThrow(() -> race);
            var replay = replay(winner, payment, normalized, responseType);
            return new Result<>(replay.httpStatus(), replay.response(), winner.paymentId());
          });
    }
  }

  private String normalized(String action, UUID payment, Object request) {
    var envelope = new java.util.LinkedHashMap<String, Object>();
    envelope.put("action", action);
    envelope.put("paymentId", payment == null ? null : payment.toString());
    envelope.put("request", request);
    return OperationJson.normalize(mapper, envelope);
  }

  public <T> Reservation<T> reserve(
      UUID user, String key, String action, UUID payment, Object request, Class<T> responseType) {
    return reserve(user, key, action, payment, request, responseType, () -> {});
  }

  /** Validate local prerequisites after replay lookup, before committing a delivery reservation. */
  public <T> Reservation<T> reserve(
      UUID user,
      String key,
      String action,
      UUID payment,
      Object request,
      Class<T> responseType,
      Runnable validate) {
    requireKey(key);
    String normalized = normalized(action, payment, request);
    try {
      return transaction.execute(
          ignored -> {
            var existing = operations.findByUserIdAndClientKey(user, key);
            if (existing.isPresent())
              return replay(existing.get(), payment, normalized, responseType);
            validate.run();
            var pending =
                new PaymentOperation(
                    UUID.randomUUID(),
                    user,
                    action,
                    key,
                    normalized,
                    null,
                    null,
                    payment,
                    Instant.now(clock));
            operations.saveAndFlush(pending);
            return new Reservation<T>(pending.id(), null, null);
          });
    } catch (org.springframework.dao.DataIntegrityViolationException race) {
      // The losing EntityManager has been rolled back and closed before entering this transaction.
      return transaction.execute(
          ignored ->
              replay(
                  operations.findByUserIdAndClientKey(user, key).orElseThrow(() -> race),
                  payment,
                  normalized,
                  responseType));
    }
  }

  public static String requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 255)
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Idempotency-Key must be 1 to 255 characters.");
    return key;
  }

  public void complete(UUID id, Object response, int httpStatus) {
    String snapshot = json(response);
    transaction.executeWithoutResult(
        ignored -> {
          var pending = operations.findById(id).orElseThrow();
          pending.complete(httpStatus, snapshot);
          operations.saveAndFlush(pending);
        });
  }

  private <T> Reservation<T> replay(
      PaymentOperation op, UUID payment, String normalized, Class<T> responseType) {
    if ((payment != null && !Objects.equals(op.paymentId(), payment))
        || !op.normalizedRequest().equals(normalized))
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_CONFLICT",
          "Idempotency key was already used with a different request.");
    if (!"COMPLETED".equals(op.status()))
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "OPERATION_IN_PROGRESS",
          "The operation is still in progress; retry with the same key.");
    try {
      return new Reservation<>(
          op.id(), mapper.readValue(op.responseData(), responseType), op.outcomeStatus());
    } catch (JsonProcessingException e) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_REPLAY_FAILED",
          "Stored idempotency response could not be read.");
    }
  }

  private String json(Object value) {
    try {
      String snapshot = mapper.writeValueAsString(value);
      if (!mapper.readTree(snapshot).isObject())
        throw new IllegalArgumentException("An operation response must be a JSON object");
      return snapshot;
    } catch (JsonProcessingException | RuntimeException e) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_STORE_FAILED",
          "Idempotency response could not be stored.");
    }
  }
}
