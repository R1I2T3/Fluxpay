package com.fluxpay.service;

import com.fluxpay.domain.ConversionCalculation;
import com.fluxpay.domain.ConversionMath;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WalletConversionService {
  private static final String OPERATION_TYPE = "CONVERT";
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");
  private final WalletOperationService operations;
  private final WalletPostingService posting;
  private final FxQuoteService quotes;
  private final ConversionMath conversionMath;
  private final FxQuoteValidator quoteValidator;

  public WalletConversionService(
      WalletOperationService operations,
      WalletPostingService posting,
      FxQuoteService quotes,
      ConversionMath conversionMath,
      FxQuoteValidator quoteValidator) {
    this.operations = operations;
    this.posting = posting;
    this.quotes = quotes;
    this.conversionMath = conversionMath;
    this.quoteValidator = quoteValidator;
  }

  public WalletConvertResponse convert(
      UUID userId, WalletConvertRequest request, String clientKey) {
    UUID owner = requireUser(userId);
    String key = WalletOperationService.requireKey(clientKey);
    NormalizedConversion normalized = normalize(request);
    String normalizedRequest = normalized.json();

    var accepted = new java.util.concurrent.atomic.AtomicReference<AcceptedConversion>();
    return operations.execute(
        owner,
        OPERATION_TYPE,
        key,
        normalizedRequest,
        WalletConvertResponse.class,
        canonical -> {
          // One accepted quote/calculation is retained across a rolled-back posting retry.
          if (accepted.get() == null) {
            FxSnapshot snapshot =
                quoteValidator.accept(
                    quotes.snapshot(normalized.from(), normalized.to()),
                    normalized.from(),
                    normalized.to());
            ConversionCalculation calculation =
                conversionMath.calculate(
                    normalized.from(), normalized.to(), normalized.amount(), snapshot.rate());
            accepted.set(
                new AcceptedConversion(calculation, snapshot, FxQuoteValidator.quoteId(snapshot)));
          }
          AcceptedConversion conversion = accepted.get();
          return posting.convert(
              owner,
              normalized.from(),
              normalized.to(),
              conversion.calculation(),
              conversion.snapshot(),
              conversion.quoteId(),
              canonical,
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
    try {
      amount = amount.setScale(4, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException excessPrecision) {
      throw new IllegalArgumentException("Amount must fit NUMBER(19,4) storage", excessPrecision);
    }
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

  private record AcceptedConversion(
      ConversionCalculation calculation, FxSnapshot snapshot, String quoteId) {}
}
