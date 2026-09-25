package com.fluxpay.dto;

import com.fluxpay.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AdminStatisticsResponse(
    Metadata meta,
    PaymentSummary paymentSummary,
    List<PaymentDay> paymentTrend,
    List<StatusCount> paymentStatuses,
    List<ProviderStats> providers,
    CustomerSummary customers,
    List<CustomerDay> customerTrend,
    Workload workload) {
  public AdminStatisticsResponse {
    paymentTrend = List.copyOf(paymentTrend);
    paymentStatuses = List.copyOf(paymentStatuses);
    providers = List.copyOf(providers);
    customerTrend = List.copyOf(customerTrend);
  }

  public record Metadata(
      LocalDate from,
      LocalDate to,
      String currency,
      int currencyScale,
      String reportingZone,
      Instant fromInclusive,
      Instant toExclusive,
      Instant generatedAt,
      String periodBasis) {}

  public record PaymentSummary(
      long paymentCount,
      long completedCount,
      String completedAmount,
      BigDecimal payoutSuccessRate,
      long failedCount,
      long processingCount) {}

  public record PaymentDay(LocalDate date, long paymentCount, String completedAmount) {}

  public record StatusCount(PaymentStatus status, long count) {}

  public record ProviderStats(
      UUID providerId,
      String providerCode,
      String providerName,
      long totalAttempts,
      long completedAttempts,
      long failedAttempts,
      long inProgressAttempts,
      BigDecimal successRate) {}

  public record CustomerSummary(long totalCustomers, long newRegistrations) {}

  public record CustomerDay(LocalDate date, long registrations) {}

  public record Workload(
      long kycPending,
      long kycOver24h,
      long complianceOpen,
      long complianceHighRisk,
      long complianceOver24h,
      long ticketsOpen,
      long ticketsOver24h) {}
}
