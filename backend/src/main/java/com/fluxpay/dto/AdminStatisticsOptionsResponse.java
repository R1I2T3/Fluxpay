package com.fluxpay.dto;

import java.time.LocalDate;
import java.util.List;

public record AdminStatisticsOptionsResponse(
    List<Currency> currencies,
    String defaultCurrency,
    String reportingZone,
    LocalDate today,
    int maximumRangeDays) {
  public AdminStatisticsOptionsResponse {
    currencies = List.copyOf(currencies);
  }

  public record Currency(String code, int scale) {}
}
