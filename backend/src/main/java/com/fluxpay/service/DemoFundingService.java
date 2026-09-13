package com.fluxpay.service;

import com.fluxpay.config.DemoFundingConfig;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.exception.DemoFundingDisabledException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DemoFundingService {
  private static final String OPERATION_TYPE = "RECEIVE_DEMO";
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");
  private static final BigDecimal MAX_MONEY = new BigDecimal("999999999999999.9999");

  private final DemoFundingConfig config;
  private final WalletOperationService operations;
  private final WalletPostingService posting;

  public DemoFundingService(
      DemoFundingConfig config, WalletOperationService operations, WalletPostingService posting) {
    this.config = config;
    this.operations = operations;
    this.posting = posting;
  }

  public WalletResponse receiveDemo(UUID userId, WalletReceiveRequest request, String clientKey) {
    if (!config.isEnabled()) {
      throw new DemoFundingDisabledException();
    }

    UUID owner = requireUser(userId);
    String key = WalletOperationService.requireKey(clientKey);
    NormalizedReceive normalized = normalize(request);
    String normalizedRequest = normalized.json();

    return operations.execute(
        owner,
        OPERATION_TYPE,
        key,
        normalizedRequest,
        WalletResponse.class,
        canonical ->
            posting.receiveDemo(owner, normalized.currency(), normalized.amount(), canonical, key));
  }

  private static UUID requireUser(UUID userId) {
    if (userId == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return userId;
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
