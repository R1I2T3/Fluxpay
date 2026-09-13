package com.fluxpay.service;

import com.fluxpay.domain.ConversionMath;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WalletConversionService {
  private static final String OPERATION_TYPE = "CONVERT";
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");
  private static final ConversionMath CONVERSION_MATH = new ConversionMath();

  private final WalletOperationService operations;
  private final WalletPostingService posting;
  private final FxQuoteService quotes;

  public WalletConversionService(
      WalletOperationService operations, WalletPostingService posting, FxQuoteService quotes) {
    this.operations = operations;
    this.posting = posting;
    this.quotes = quotes;
  }

  public WalletConvertResponse convert(
      UUID userId, WalletConvertRequest request, String clientKey) {
    UUID owner = requireUser(userId);
    String key = WalletOperationService.requireKey(clientKey);
    NormalizedConversion normalized = normalize(request);
    String normalizedRequest = normalized.json();

    var quote = new java.util.concurrent.atomic.AtomicReference<FxSnapshot>();
    return operations.execute(
        owner,
        OPERATION_TYPE,
        key,
        normalizedRequest,
        WalletConvertResponse.class,
        () -> {
          // One accepted FX snapshot is retained across a rolled-back posting retry.
          if (quote.get() == null) quote.set(quotes.snapshot(normalized.from(), normalized.to()));
          FxSnapshot snapshot = quote.get();
          BigDecimal fee = CONVERSION_MATH.fee(normalized.amount());
          BigDecimal net = normalized.amount().subtract(fee).setScale(4);
          BigDecimal credit = CONVERSION_MATH.convertedAmount(normalized.amount(), snapshot.rate());
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
        });
  }

  private static UUID requireUser(UUID userId) {
    if (userId == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return userId;
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
