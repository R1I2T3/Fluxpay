package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
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
public class WalletConversionService {
  private static final String OPERATION_TYPE = "CONVERT";
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");
  private static final M2ConversionMath CONVERSION_MATH = new M2ConversionMath();

  private final WalletOperationRepository operations;
  private final WalletPostingService posting;
  private final FxQuoteService quotes;
  private final ObjectMapper objectMapper;

  public WalletConversionService(
      WalletOperationRepository operations,
      WalletPostingService posting,
      FxQuoteService quotes,
      ObjectMapper objectMapper) {
    this.operations = operations;
    this.posting = posting;
    this.quotes = quotes;
    this.objectMapper = objectMapper;
  }

  public WalletConvertResponse convert(
      UUID userId, WalletConvertRequest request, String clientKey) {
    UUID owner = requireUser(userId);
    String key = requireKey(clientKey);
    NormalizedConversion normalized = normalize(request);
    String normalizedRequest = normalized.json();

    Optional<WalletOperation> existing = find(owner, key);
    if (existing.isPresent()) {
      return replay(existing.orElseThrow(), normalizedRequest, key);
    }

    FxSnapshot snapshot = quotes.snapshot(normalized.from(), normalized.to());
    BigDecimal fee = CONVERSION_MATH.fee(normalized.amount());
    BigDecimal net = normalized.amount().subtract(fee).setScale(4);
    BigDecimal credit = CONVERSION_MATH.convertedAmount(normalized.amount(), snapshot.rate());

    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return posting.convert(
            owner,
            normalized.from(),
            normalized.to(),
            normalized.amount(),
            fee,
            net,
            credit,
            snapshot,
            normalizedRequest,
            key);
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

  private WalletConvertResponse replay(
      WalletOperation operation, String normalizedRequest, String clientKey) {
    if (!normalizedRequest.equals(operation.getNormalizedRequest())) {
      throw new LedgerIdempotencyConflictException(clientKey);
    }
    if (!"COMPLETED".equals(operation.getStatus()) || operation.getResponseSnapshot() == null) {
      throw new DemoFundingRetryException();
    }
    try {
      return objectMapper.readValue(operation.getResponseSnapshot(), WalletConvertResponse.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored wallet-conversion response is invalid", exception);
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

  private static NormalizedConversion normalize(WalletConvertRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("Request body is required");
    }
    String from = normalizeCurrency(request.from());
    String to = normalizeCurrency(request.to());
    if (!CURRENCIES.contains(from) || !CURRENCIES.contains(to)) {
      throw new IllegalArgumentException("Conversion currencies must be USD, EUR or INR");
    }
    if (from.equals(to)) {
      throw new IllegalArgumentException("Conversion currencies must be different");
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
    CONVERSION_MATH.fee(amount);
    return new NormalizedConversion(from, to, amount);
  }

  private static String normalizeCurrency(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private record NormalizedConversion(String from, String to, BigDecimal amount) {
    String json() {
      return "{\"from\":\""
          + from
          + "\",\"to\":\""
          + to
          + "\",\"amount\":\""
          + amount.toPlainString()
          + "\"}";
    }
  }
}
