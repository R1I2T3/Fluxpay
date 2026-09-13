package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.common.json.OperationJson;
import com.fluxpay.exception.LedgerIdempotencyConflictException;
import com.fluxpay.exception.OperationRaceException;
import com.fluxpay.exception.OperationRetryException;
import com.fluxpay.repository.WalletOperationRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Wallet-only replay and race recovery. Posting owns a separate transaction. */
@Service
public class WalletOperationService {
  private final WalletOperationRepository operations;
  private final ObjectMapper mapper;
  private final TransactionTemplate read;

  public WalletOperationService(
      WalletOperationRepository operations,
      ObjectMapper mapper,
      PlatformTransactionManager transactions) {
    this.operations = operations;
    this.mapper = mapper;
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setReadOnly(true);
  }

  public <T> T execute(
      UUID user,
      String action,
      String key,
      String normalized,
      Class<T> type,
      Function<String, T> posting) {
    requireKey(key);
    String canonical = OperationJson.canonicalize(mapper, normalized);
    for (int attempt = 0; attempt < 2; attempt++) {
      var existing = find(user, action, key);
      if (existing.isPresent()) return replay(existing.get(), canonical, key, type);
      try {
        return posting.apply(canonical);
      } catch (OperationRaceException
          | DataIntegrityViolationException
          | ObjectOptimisticLockingFailureException race) {
        // WalletPostingService's REQUIRES_NEW transaction has already unwound here.
        var winner = find(user, action, key);
        if (winner.isPresent()) return replay(winner.get(), canonical, key, type);
        if (attempt == 1) throw new OperationRetryException();
      }
    }
    throw new OperationRetryException();
  }

  public static String requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 255)
      throw new IllegalArgumentException("Idempotency-Key must contain 1 to 255 characters");
    return key;
  }

  private Optional<WalletOperation> find(UUID user, String action, String key) {
    return read.execute(
        ignored -> operations.findByUserIdAndOperationTypeAndClientKey(user, action, key));
  }

  private <T> T replay(WalletOperation operation, String normalized, String key, Class<T> type) {
    if (!OperationJson.canonicalize(mapper, operation.getNormalizedRequest()).equals(normalized))
      throw new LedgerIdempotencyConflictException(key);
    if (!"COMPLETED".equals(operation.getStatus())) throw new OperationRetryException();
    try {
      return mapper.readValue(operation.getResponseSnapshot(), type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored wallet operation response is invalid", e);
    }
  }
}
