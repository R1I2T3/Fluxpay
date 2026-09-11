package com.fluxpay.config;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

public record M5ComplianceSettings(Map<String, BigDecimal> highValueThresholds, ZoneId dayZone,
    String recipientTodayMode, Set<String> highRiskCountries) {
  public M5ComplianceSettings {
    highValueThresholds = Map.copyOf(highValueThresholds);
    highRiskCountries = Set.copyOf(highRiskCountries);
    if (highValueThresholds.isEmpty() || dayZone == null
        || !Set.of("ALL_ATTEMPTS", "COMPLETED_ONLY").contains(recipientTodayMode)
        || highValueThresholds.entrySet().stream().anyMatch(e -> !e.getKey().matches("[A-Z]{3}")
            || e.getValue() == null || e.getValue().signum() <= 0)
        || highRiskCountries.stream().anyMatch(c -> !c.matches("[A-Z]{2}"))) {
      throw new IllegalArgumentException("Explicit valid compliance business settings are required");
    }
  }
}
