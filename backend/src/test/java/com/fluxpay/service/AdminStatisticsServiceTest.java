package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsOptionsResponse;
import com.fluxpay.dto.AdminStatisticsPaymentPageResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.AdminStatisticsRepository;
import com.fluxpay.repository.AdminStatisticsRepository.CustomerBucket;
import com.fluxpay.repository.AdminStatisticsRepository.PaymentBucket;
import com.fluxpay.repository.AdminStatisticsRepository.PaymentPage;
import com.fluxpay.repository.AdminStatisticsRepository.ProviderAggregate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminStatisticsServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
  private static final LocalDate DAY = LocalDate.parse("2026-09-24");
  private AdminStatisticsRepository repository;
  private AdminStatisticsService service;

  @BeforeEach
  void setUp() {
    repository = mock(AdminStatisticsRepository.class);
    service = new AdminStatisticsService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    when(repository.currencies())
        .thenReturn(List.of(new AdminStatisticsOptionsResponse.Currency("INR", 2)));
    when(repository.paymentBuckets(any()))
        .thenReturn(
            List.of(
                new PaymentBucket(DAY, PaymentStatus.COMPLETED, 2, new BigDecimal("125.00")),
                new PaymentBucket(DAY, PaymentStatus.FAILED, 1, BigDecimal.ZERO),
                new PaymentBucket(DAY, PaymentStatus.REFUNDED, 1, BigDecimal.ZERO),
                new PaymentBucket(DAY, PaymentStatus.PROCESSING, 1, BigDecimal.ZERO),
                new PaymentBucket(DAY, PaymentStatus.DRAFT, 1, BigDecimal.ZERO)));
    when(repository.providers(any())).thenReturn(List.of());
    when(repository.registrations(any())).thenReturn(List.of());
    when(repository.totalCustomers(NOW)).thenReturn(0L);
    when(repository.workload(NOW)).thenReturn(zeroWorkload());
  }

  @Test
  void summaryBuildsACompleteZeroFilledResponseFromGroupedRows() {
    var result = service.summary("2026-09-23", "2026-09-25", "INR");

    assertThat(result.paymentSummary().paymentCount()).isEqualTo(6);
    assertThat(result.paymentSummary().completedCount()).isEqualTo(2);
    assertThat(result.paymentSummary().completedAmount()).isEqualTo("125.00");
    assertThat(result.paymentSummary().payoutSuccessRate()).isEqualByComparingTo("50.00");
    assertThat(result.paymentSummary().failedCount()).isEqualTo(1);
    assertThat(result.paymentSummary().processingCount()).isEqualTo(1);
    assertThat(result.paymentTrend())
        .extracting(row -> row.date().toString())
        .containsExactly("2026-09-23", "2026-09-24", "2026-09-25");
    assertThat(result.paymentTrend())
        .extracting(com.fluxpay.dto.AdminStatisticsResponse.PaymentDay::paymentCount)
        .containsExactly(0L, 6L, 0L);
    assertThat(result.paymentStatuses()).hasSize(PaymentStatus.values().length);
    assertThat(result.customerTrend())
        .extracting(com.fluxpay.dto.AdminStatisticsResponse.CustomerDay::registrations)
        .containsExactly(0L, 0L, 0L);
    assertThat(result.customers().totalCustomers()).isZero();
    verify(repository).totalCustomers(NOW);
    verify(repository).workload(NOW);
  }

  @Test
  void emptyDataAndPendingOnlyPaymentOutcomesHaveNullSuccessRateAndZeroMoney() {
    when(repository.paymentBuckets(any()))
        .thenReturn(List.of(new PaymentBucket(DAY, PaymentStatus.PROCESSING, 2, BigDecimal.ZERO)));

    var result = service.summary("2026-09-24", "2026-09-24", "INR");

    assertThat(result.paymentSummary().paymentCount()).isEqualTo(2);
    assertThat(result.paymentSummary().completedAmount()).isEqualTo("0.00");
    assertThat(result.paymentSummary().payoutSuccessRate()).isNull();
    assertThat(result.paymentTrend().get(0).paymentCount()).isEqualTo(2);
    assertThat(result.paymentStatuses())
        .filteredOn(row -> row.status() == PaymentStatus.PROCESSING)
        .singleElement()
        .satisfies(row -> assertThat(row.count()).isEqualTo(2));
  }

  @Test
  void emptyBucketsProduceZeroFilledPaymentAndRegistrationSeries() {
    when(repository.paymentBuckets(any())).thenReturn(List.of());

    var result = service.summary("2026-09-23", "2026-09-25", "INR");

    assertThat(result.paymentSummary().paymentCount()).isZero();
    assertThat(result.paymentSummary().completedAmount()).isEqualTo("0.00");
    assertThat(result.paymentSummary().payoutSuccessRate()).isNull();
    assertThat(result.paymentTrend())
        .extracting(com.fluxpay.dto.AdminStatisticsResponse.PaymentDay::paymentCount)
        .containsExactly(0L, 0L, 0L);
    assertThat(result.customerTrend())
        .extracting(com.fluxpay.dto.AdminStatisticsResponse.CustomerDay::registrations)
        .containsExactly(0L, 0L, 0L);
    assertThat(result.paymentStatuses()).allSatisfy(row -> assertThat(row.count()).isZero());
  }

  @Test
  void roundsProviderRateAndCountsRegistrationsRegardlessOfReportCurrency() {
    UUID providerId = UUID.randomUUID();
    when(repository.providers(any()))
        .thenReturn(List.of(new ProviderAggregate(providerId, "BANK", "Bank", 3, 1, 2, 0)));
    when(repository.registrations(any())).thenReturn(List.of(new CustomerBucket(DAY, 4)));
    when(repository.totalCustomers(NOW)).thenReturn(123L);

    var result = service.summary("2026-09-24", "2026-09-25", "INR");

    assertThat(result.providers().get(0).successRate()).isEqualByComparingTo("33.33");
    assertThat(result.customers().newRegistrations()).isEqualTo(4);
    assertThat(result.customers().totalCustomers()).isEqualTo(123);
    assertThat(result.customerTrend())
        .extracting(com.fluxpay.dto.AdminStatisticsResponse.CustomerDay::registrations)
        .containsExactly(4L, 0L);
    verify(repository).registrations(any());
  }

  @Test
  void optionsSortCurrenciesAndUseReportingZoneTodayWithoutAnEmptyDefault() {
    when(repository.currencies())
        .thenReturn(
            List.of(
                new AdminStatisticsOptionsResponse.Currency("USD", 2),
                new AdminStatisticsOptionsResponse.Currency("INR", 2)));

    var options = service.options();

    assertThat(options.currencies())
        .extracting(AdminStatisticsOptionsResponse.Currency::code)
        .containsExactly("INR", "USD");
    assertThat(options.defaultCurrency()).isEqualTo("INR");
    assertThat(options.today()).isEqualTo(LocalDate.parse("2026-09-25"));

    when(repository.currencies()).thenReturn(List.of());
    assertThat(service.options().defaultCurrency()).isNull();
  }

  @Test
  void optionsRejectInvalidCurrencyConfiguration() {
    when(repository.currencies())
        .thenReturn(List.of(new AdminStatisticsOptionsResponse.Currency("INR", 5)));

    assertThatThrownBy(service::options)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_REPORT_CONFIGURATION"));

    when(repository.currencies()).thenReturn(null);
    assertThatThrownBy(service::options)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_REPORT_CONFIGURATION"));
  }

  @Test
  void paymentPagePassesParsedFiltersAndReturnsTruthfulOutOfRangeTotals() {
    AdminStatisticsPaymentPageResponse.Row row =
        new AdminStatisticsPaymentPageResponse.Row(
            UUID.randomUUID(), NOW, "7.50", "INR", PaymentStatus.FAILED);
    when(repository.paymentPage(any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(new PaymentPage(List.of(row), 41));

    var page = service.payments("2026-09-24", "2026-09-25", "INR", "failed", "2", "20");

    assertThat(page.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.totalElements()).isEqualTo(41);
    assertThat(page.totalPages()).isEqualTo(3);
    assertThat(page.items()).containsExactly(row);
    when(repository.paymentPage(any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(new PaymentPage(List.of(), 41));

    var outOfRange = service.payments("2026-09-24", "2026-09-25", "INR", null, "999", "20");
    assertThat(outOfRange.page()).isEqualTo(999);
    assertThat(outOfRange.totalElements()).isEqualTo(41);
    assertThat(outOfRange.totalPages()).isEqualTo(3);
    assertThat(outOfRange.items()).isEmpty();
  }

  @Test
  void eachRequestUsesOneClockInstantForQueryAndGlobalAggregates() {
    CountingClock clock = new CountingClock(NOW);
    service = new AdminStatisticsService(repository, clock);

    service.summary("2026-09-24", "2026-09-24", "INR");

    assertThat(clock.reads).isEqualTo(1);
  }

  private static com.fluxpay.dto.AdminStatisticsResponse.Workload zeroWorkload() {
    return new com.fluxpay.dto.AdminStatisticsResponse.Workload(0, 0, 0, 0, 0, 0, 0);
  }

  private static final class CountingClock extends Clock {
    private final Instant instant;
    private int reads;

    private CountingClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      reads++;
      return instant;
    }
  }
}
