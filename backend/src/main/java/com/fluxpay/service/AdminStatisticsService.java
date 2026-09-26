package com.fluxpay.service;

import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsOptionsResponse;
import com.fluxpay.dto.AdminStatisticsPaymentPageResponse;
import com.fluxpay.dto.AdminStatisticsQuery;
import com.fluxpay.dto.AdminStatisticsResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.AdminStatisticsRepository;
import com.fluxpay.repository.AdminStatisticsRepository.CustomerBucket;
import com.fluxpay.repository.AdminStatisticsRepository.PaymentBucket;
import com.fluxpay.repository.AdminStatisticsRepository.ProviderAggregate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminStatisticsService {
  private final AdminStatisticsRepository repository;
  private final Clock clock;

  public AdminStatisticsService(AdminStatisticsRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AdminStatisticsOptionsResponse options() {
    Instant now = clock.instant();
    List<AdminStatisticsOptionsResponse.Currency> currencies = currencies();
    String defaultCurrency =
        currencies.stream()
            .map(AdminStatisticsOptionsResponse.Currency::code)
            .filter("INR"::equals)
            .findFirst()
            .orElse(currencies.isEmpty() ? null : currencies.get(0).code());
    return new AdminStatisticsOptionsResponse(
        currencies,
        defaultCurrency,
        AdminStatisticsRules.REPORT_ZONE.getId(),
        now.atZone(AdminStatisticsRules.REPORT_ZONE).toLocalDate(),
        AdminStatisticsRules.MAXIMUM_RANGE_DAYS);
  }

  @Transactional(readOnly = true)
  public AdminStatisticsResponse summary(String from, String to, String currency) {
    AdminStatisticsQuery query = resolve(from, to, currency);
    List<PaymentBucket> paymentBuckets = repository.paymentBuckets(query);
    List<ProviderAggregate> providerAggregates = repository.providers(query);
    List<CustomerBucket> customerBuckets = repository.registrations(query);

    Map<PaymentStatus, Long> counts = statusCounts(paymentBuckets);
    long completed = counts.get(PaymentStatus.COMPLETED);
    long failed = counts.get(PaymentStatus.FAILED);
    long refunded = counts.get(PaymentStatus.REFUNDED);
    long processing = counts.get(PaymentStatus.PROCESSING);
    long paymentCount = counts.values().stream().mapToLong(Long::longValue).sum();
    BigDecimal completedAmount =
        paymentBuckets.stream()
            .map(PaymentBucket::completedAmount)
            .filter(amount -> amount != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<AdminStatisticsResponse.PaymentDay> paymentTrend = paymentDays(query, paymentBuckets);
    List<AdminStatisticsResponse.CustomerDay> customerTrend = customerDays(query, customerBuckets);
    List<AdminStatisticsResponse.StatusCount> statuses =
        List.of(PaymentStatus.values()).stream()
            .map(status -> new AdminStatisticsResponse.StatusCount(status, counts.get(status)))
            .toList();
    List<AdminStatisticsResponse.ProviderStats> providers =
        providerAggregates.stream().map(this::providerStats).toList();
    long registrations = customerBuckets.stream().mapToLong(CustomerBucket::count).sum();

    return new AdminStatisticsResponse(
        AdminStatisticsRules.metadata(query),
        new AdminStatisticsResponse.PaymentSummary(
            paymentCount,
            completed,
            AdminStatisticsRules.money(completedAmount, query.currencyScale()),
            AdminStatisticsRules.percentage(completed, completed + failed + refunded),
            failed,
            processing),
        paymentTrend,
        statuses,
        providers,
        new AdminStatisticsResponse.CustomerSummary(
            repository.totalCustomers(query.generatedAt()), registrations),
        customerTrend,
        repository.workload(query.generatedAt()));
  }

  @Transactional(readOnly = true)
  public AdminStatisticsPaymentPageResponse payments(
      String from, String to, String currency, String status, String page, String size) {
    AdminStatisticsQuery query = resolve(from, to, currency);
    PaymentStatus parsedStatus = AdminStatisticsRules.status(status);
    int parsedPage = AdminStatisticsRules.page(page);
    int parsedSize = AdminStatisticsRules.size(size);
    AdminStatisticsRepository.PaymentPage result =
        repository.paymentPage(query, parsedStatus, parsedPage, parsedSize);
    long totalPages = result.total() / parsedSize + (result.total() % parsedSize == 0 ? 0 : 1);
    return new AdminStatisticsPaymentPageResponse(
        AdminStatisticsRules.metadata(query),
        parsedStatus,
        parsedPage,
        parsedSize,
        result.total(),
        totalPages,
        result.items());
  }

  private AdminStatisticsQuery resolve(String from, String to, String currency) {
    Instant now = clock.instant();
    return AdminStatisticsRules.resolve(from, to, currency, currencies(), now);
  }

  private List<AdminStatisticsOptionsResponse.Currency> currencies() {
    List<AdminStatisticsOptionsResponse.Currency> configured = repository.currencies();
    if (configured == null) {
      throw invalidConfiguration();
    }
    List<AdminStatisticsOptionsResponse.Currency> normalized = new ArrayList<>();
    Set<String> codes = new HashSet<>();
    for (AdminStatisticsOptionsResponse.Currency currency : configured) {
      if (currency == null || currency.code() == null || currency.code().isBlank()) {
        throw invalidConfiguration();
      }
      String code = currency.code().trim().toUpperCase(Locale.ROOT);
      if (currency.scale() < 0 || currency.scale() > 4 || !codes.add(code)) {
        throw invalidConfiguration();
      }
      normalized.add(new AdminStatisticsOptionsResponse.Currency(code, currency.scale()));
    }
    normalized.sort(Comparator.comparing(AdminStatisticsOptionsResponse.Currency::code));
    return List.copyOf(normalized);
  }

  private Map<PaymentStatus, Long> statusCounts(List<PaymentBucket> buckets) {
    Map<PaymentStatus, Long> counts = new EnumMap<>(PaymentStatus.class);
    for (PaymentStatus status : PaymentStatus.values()) {
      counts.put(status, 0L);
    }
    for (PaymentBucket bucket : buckets) {
      counts.merge(bucket.status(), bucket.count(), Long::sum);
    }
    return counts;
  }

  private List<AdminStatisticsResponse.PaymentDay> paymentDays(
      AdminStatisticsQuery query, List<PaymentBucket> buckets) {
    Map<LocalDate, Long> counts = new HashMap<>();
    Map<LocalDate, BigDecimal> amounts = new HashMap<>();
    for (PaymentBucket bucket : buckets) {
      counts.merge(bucket.date(), bucket.count(), Long::sum);
      if (bucket.completedAmount() != null) {
        amounts.merge(bucket.date(), bucket.completedAmount(), BigDecimal::add);
      }
    }
    List<AdminStatisticsResponse.PaymentDay> days = new ArrayList<>();
    for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
      days.add(
          new AdminStatisticsResponse.PaymentDay(
              date,
              counts.getOrDefault(date, 0L),
              AdminStatisticsRules.money(
                  amounts.getOrDefault(date, BigDecimal.ZERO), query.currencyScale())));
    }
    return List.copyOf(days);
  }

  private List<AdminStatisticsResponse.CustomerDay> customerDays(
      AdminStatisticsQuery query, List<CustomerBucket> buckets) {
    Map<LocalDate, Long> counts = new HashMap<>();
    for (CustomerBucket bucket : buckets) {
      counts.merge(bucket.date(), bucket.count(), Long::sum);
    }
    List<AdminStatisticsResponse.CustomerDay> days = new ArrayList<>();
    for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
      days.add(new AdminStatisticsResponse.CustomerDay(date, counts.getOrDefault(date, 0L)));
    }
    return List.copyOf(days);
  }

  private AdminStatisticsResponse.ProviderStats providerStats(ProviderAggregate provider) {
    return new AdminStatisticsResponse.ProviderStats(
        provider.providerId(),
        provider.providerCode(),
        provider.providerName(),
        provider.totalAttempts(),
        provider.completedAttempts(),
        provider.failedAttempts(),
        provider.inProgressAttempts(),
        AdminStatisticsRules.percentage(
            provider.completedAttempts(),
            provider.completedAttempts() + provider.failedAttempts()));
  }

  private BusinessException invalidConfiguration() {
    return new BusinessException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INVALID_REPORT_CONFIGURATION",
        "The reporting currency configuration is invalid.");
  }
}
