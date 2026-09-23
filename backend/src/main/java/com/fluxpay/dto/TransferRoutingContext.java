package com.fluxpay.dto;

import com.fluxpay.domain.DestinationType;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * Quote-time routing input: the transfer destination plus the source amount and market rate used
 * for route-driven pricing. Eligibility matches destination type, country, and payout currency;
 * pricing consumes the gross amount and market rate.
 */
public record TransferRoutingContext(
    DestinationType destinationType,
    String destinationCountry,
    String payoutCurrency,
    BigDecimal gross,
    BigDecimal marketRate) {
  public TransferRoutingContext {
    Objects.requireNonNull(destinationType, "destinationType must not be null");
    destinationCountry = normalizeCountry(destinationCountry);
    payoutCurrency = normalizeCurrency(payoutCurrency);
    if (gross == null || gross.signum() <= 0) {
      throw new IllegalArgumentException("gross must be positive");
    }
    if (marketRate == null || marketRate.signum() <= 0) {
      throw new IllegalArgumentException("marketRate must be positive");
    }
  }

  private static String normalizeCountry(String country) {
    if (country == null || country.isBlank()) {
      return null;
    }
    String normalized = country.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{2}")) {
      throw new IllegalArgumentException("destinationCountry must be ISO-3166 alpha-2");
    }
    return normalized;
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("payoutCurrency must not be blank");
    }
    String normalized = currency.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("payoutCurrency must be ISO-4217");
    }
    return normalized;
  }
}
