package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Shared validation before an operation identity or ledger posting is created. */
@Component
public class WalletRequestNormalizer {
  private final CurrencyScaleService scales;
  private final ObjectMapper mapper;

  public WalletRequestNormalizer(CurrencyScaleService scales, ObjectMapper mapper) {
    this.scales = scales;
    this.mapper = mapper;
  }

  public UUID user(UUID user) {
    if (user == null) throw new IllegalArgumentException("Authenticated user is required");
    return user;
  }

  public String currency(String value) {
    String currency = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("USD", "EUR", "INR").contains(currency))
      throw new IllegalArgumentException("Currency must be USD, EUR or INR");
    scales.scale(currency);
    return currency;
  }

  public BigDecimal amount(String value, String currency) {
    try {
      BigDecimal amount = new BigDecimal(value);
      if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("999999999999999.9999")) > 0)
        throw new IllegalArgumentException("Amount must be positive and fit NUMBER(19,4)");
      return amount.setScale(scales.scale(currency), RoundingMode.UNNECESSARY).setScale(4);
    } catch (NullPointerException | NumberFormatException | ArithmeticException e) {
      throw new IllegalArgumentException(
          "Amount must be a decimal with valid currency precision", e);
    }
  }

  public String note(String note) {
    if (note == null) return "";
    String normalized = note.trim();
    if (normalized.length() > 255)
      throw new IllegalArgumentException("Note must not exceed 255 characters");
    return normalized;
  }

  public String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize wallet operation", e);
    }
  }
}
