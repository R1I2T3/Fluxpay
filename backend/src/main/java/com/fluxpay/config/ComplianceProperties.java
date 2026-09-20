package com.fluxpay.config;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Explicit, source-currency review thresholds for the payment-confirmation compliance port. */
@ConfigurationProperties(prefix = "fluxpay.compliance")
public record ComplianceProperties(
    Map<String, BigDecimal> reviewThresholds, @DefaultValue("24") int recipientRecentHours) {
  public ComplianceProperties {
    reviewThresholds =
        reviewThresholds.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    entry -> entry.getKey().toUpperCase(Locale.ROOT), Map.Entry::getValue));
    if (recipientRecentHours <= 0
        || reviewThresholds.isEmpty()
        || reviewThresholds.entrySet().stream()
            .anyMatch(
                entry ->
                    !entry.getKey().matches("[A-Z]{3}")
                        || entry.getValue() == null
                        || entry.getValue().signum() <= 0)) {
      throw new IllegalArgumentException(
          "Positive three-letter currency review thresholds are required");
    }
  }
}
