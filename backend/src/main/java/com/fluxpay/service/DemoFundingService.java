package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.config.M2DemoFundingConfig;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.repository.WalletOperationRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class DemoFundingService {
  private static final String OPERATION_TYPE = "RECEIVE_DEMO";
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999.9999");

  private final M2DemoFundingConfig config;
  private final WalletOperationRepository operations;
  private final WalletPostingService posting;
  private final ObjectMapper objectMapper;

  public DemoFundingService(
      M2DemoFundingConfig config,
      WalletOperationRepository operations,
      WalletPostingService posting,
      ObjectMapper objectMapper) {
    this.config = config;
    this.operations = operations;
    this.posting = posting;
    this.objectMapper = objectMapper;
  }

  public WalletResponse receiveDemo(UUID userId, WalletReceiveRequest request, String clientKey) {
    if (!config.isEnabled()) {
      throw new DemoFundingDisabledException();
    }

    UUID owner = requireUser(userId);
    String key = requireKey(clientKey);
    NormalizedReceive normalized = normalize(request);
    String normalizedRequest = normalized.json();

    for (int attempt = 0; attempt < 2; attempt++) {
      Optional<WalletOperation> existing = find(owner, key);
      if (existing.isPresent()) {
        return replay(existing.orElseThrow(), normalizedRequest, key);
      }

      try {
        return posting.receiveDemo(
            owner, normalized.currency(), normalized.amount(), normalizedRequest, key);
      } catch (OperationRaceException
          | DataIntegrityViolationException
          | ObjectOptimisticLockingFailureException exception) {
        Optional<WalletOperation> winner = find(owner, key);
        if (winner.isPresent()) {
          return replay(winner.orElseThrow(), normalizedRequest, key);
        }
        if (attempt == 1) {
          throw new DemoFundingRetryException();
        }
      }
    }
    throw new DemoFundingRetryException();
  }

  private Optional<WalletOperation> find(UUID userId, String key) {
    return operations.findByUserIdAndOperationTypeAndClientKey(userId, OPERATION_TYPE, key);
  }

  private WalletResponse replay(
      WalletOperation operation, String normalizedRequest, String clientKey) {
    if (!normalizedRequest.equals(operation.getNormalizedRequest())) {
      throw new LedgerIdempotencyConflictException(clientKey);
    }
    if (!"COMPLETED".equals(operation.getStatus()) || operation.getResponseSnapshot() == null) {
      throw new DemoFundingRetryException();
    }
    try {
      return objectMapper.readValue(operation.getResponseSnapshot(), WalletResponse.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored demo-funding response is invalid", exception);
    }
  }

  private static UUID requireUser(UUID userId) {
    if (userId == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return userId;
  }

  private static String requireKey(String clientKey) {
    if (clientKey == null || clientKey.isBlank() || clientKey.length() > 255) {
      throw new IllegalArgumentException("Idempotency-Key must contain 1 to 255 characters");
    }
    return clientKey;
  }

  private static NormalizedReceive normalize(WalletReceiveRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("Request body is required");
    }
    String currency =
        request.currency() == null ? "" : request.currency().trim().toUpperCase(Locale.ROOT);
    if (!CURRENCIES.contains(currency)) {
      throw new IllegalArgumentException("Unsupported currency: " + currency);
    }

    BigDecimal amount;
    try {
      amount = new BigDecimal(request.amount());
    } catch (NullPointerException | NumberFormatException exception) {
      throw new IllegalArgumentException("Amount must be a decimal number", exception);
    }
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("Amount must be greater than zero");
    }
    if (amount.scale() > 4) {
      throw new IllegalArgumentException("Amount must have at most four decimal places");
    }
    amount = amount.setScale(4);
    if (amount.compareTo(MAX_MONEY) > 0) {
      throw new IllegalArgumentException("Amount exceeds the NUMBER(19,4) money limit");
    }
    return new NormalizedReceive(currency, amount);
  }

  private record NormalizedReceive(String currency, BigDecimal amount) {
    String json() {
      return "{\"currency\":\"" + currency + "\",\"amount\":\"" + amount.toPlainString() + "\"}";
    }
  }
}
