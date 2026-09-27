package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsOptionsResponse;
import com.fluxpay.dto.AdminStatisticsQuery;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class AdminStatisticsRulesTest {
  private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");
  private static final List<AdminStatisticsOptionsResponse.Currency> INR =
      List.of(new AdminStatisticsOptionsResponse.Currency("INR", 2));

  @Test
  void resolvesCalendarDatesAtKolkataBoundariesAndCapsTodayAtNow() {
    AdminStatisticsQuery query =
        AdminStatisticsRules.resolve("2026-09-01", "2026-09-25", " inr ", INR, NOW);

    assertThat(query.fromInclusive()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
    assertThat(query.toExclusive()).isEqualTo(NOW);
    assertThat(query.currency()).isEqualTo("INR");
    assertThat(query.currencyScale()).isEqualTo(2);
    assertThat(query.from()).isEqualTo(LocalDate.parse("2026-09-01"));
    assertThat(query.to()).isEqualTo(LocalDate.parse("2026-09-25"));
    assertThat(query.generatedAt()).isEqualTo(NOW);
  }

  @Test
  void acceptsAValidEmptyTodayWindowAtKolkataMidnight() {
    Instant midnight = Instant.parse("2026-09-24T18:30:00Z");
    AdminStatisticsQuery query =
        AdminStatisticsRules.resolve("2026-09-25", "2026-09-25", "INR", INR, midnight);

    assertThat(query.fromInclusive()).isEqualTo(midnight);
    assertThat(query.toExclusive()).isEqualTo(midnight);
    assertThat(query.fromInclusive()).isEqualTo(query.toExclusive());
  }

  @Test
  void acceptsExactly366InclusiveCalendarDays() {
    AdminStatisticsQuery query =
        AdminStatisticsRules.resolve(
            "2025-10-01", "2026-10-01", "INR", INR, Instant.parse("2026-10-01T12:00:00Z"));

    assertThat(query.to().toEpochDay() - query.from().toEpochDay() + 1).isEqualTo(366);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "2026-09-2", "2026/09/25", " 2026-09-25", "2026-02-30"})
  void rejectsMissingMalformedOrImpossibleStartDates(String from) {
    assertBusinessError("INVALID_REPORT_RANGE", () -> resolve(from, "2026-09-25", "INR"));
  }

  @Test
  void rejectsMissingEndDate() {
    assertBusinessError("INVALID_REPORT_RANGE", () -> resolve("2026-09-01", null, "INR"));
  }

  @Test
  void rejectsReversedRange() {
    assertBusinessError("INVALID_REPORT_RANGE", () -> resolve("2026-09-26", "2026-09-25", "INR"));
  }

  @Test
  void rejects367InclusiveCalendarDays() {
    assertBusinessError(
        "INVALID_REPORT_RANGE",
        () ->
            AdminStatisticsRules.resolve(
                "2025-09-30", "2026-10-01", "INR", INR, Instant.parse("2026-10-01T12:00:00Z")));
  }

  @Test
  void rejectsFutureDateInReportingZone() {
    assertBusinessError("INVALID_REPORT_RANGE", () -> resolve("2026-09-25", "2026-09-26", "INR"));
  }

  @Test
  void rejectsMissingOrUnsupportedCurrencyIncludingEmptyConfiguration() {
    assertBusinessError("INVALID_REPORT_CURRENCY", () -> resolve("2026-09-01", "2026-09-25", null));
    assertBusinessError(
        "INVALID_REPORT_CURRENCY", () -> resolve("2026-09-01", "2026-09-25", "USD"));
    assertBusinessError(
        "INVALID_REPORT_CURRENCY",
        () -> AdminStatisticsRules.resolve("2026-09-01", "2026-09-25", "INR", List.of(), NOW));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 5})
  void treatsUnsupportedConfiguredCurrencyScaleAsServerConfigurationError(int scale) {
    assertThatThrownBy(
            () ->
                AdminStatisticsRules.resolve(
                    "2026-09-01",
                    "2026-09-25",
                    "INR",
                    List.of(new AdminStatisticsOptionsResponse.Currency("INR", scale)),
                    NOW))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(error.code()).isEqualTo("INVALID_REPORT_CONFIGURATION");
            });
  }

  @Test
  void parsesPaymentStatusCaseInsensitivelyAndTreatsBlankAsAbsent() {
    assertThat(AdminStatisticsRules.status(" completed ")).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(AdminStatisticsRules.status("   ")).isNull();
    assertThat(AdminStatisticsRules.status(null)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"complete", "unknown", "all"})
  void rejectsUnsupportedPaymentStatus(String value) {
    assertBusinessError("INVALID_REPORT_STATUS", () -> AdminStatisticsRules.status(value));
  }

  @Test
  void defaultsAndParsesPaginationValues() {
    assertThat(AdminStatisticsRules.page(null)).isZero();
    assertThat(AdminStatisticsRules.page(" 3 ")).isEqualTo(3);
    assertThat(AdminStatisticsRules.size(null)).isEqualTo(20);
    assertThat(AdminStatisticsRules.size("100")).isEqualTo(100);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "1.5", "word", "2147483648"})
  void rejectsInvalidPageValues(String value) {
    assertBusinessError("INVALID_REPORT_PAGE", () -> AdminStatisticsRules.page(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "101", "-2", "abc", "1.1"})
  void rejectsSizeOutsideOneToOneHundredOrNotAnInteger(String value) {
    assertBusinessError("INVALID_REPORT_PAGE", () -> AdminStatisticsRules.size(value));
  }

  @Test
  void computesPercentagesAndReturnsNullForZeroDenominator() {
    assertThat(AdminStatisticsRules.percentage(1, 3)).isEqualByComparingTo("33.33");
    assertThat(AdminStatisticsRules.percentage(1, 8)).isEqualByComparingTo("12.50");
    assertThat(AdminStatisticsRules.percentage(0, 0)).isNull();
  }

  @Test
  void roundsMoneyWithoutLosingLargeIntegerPrecision() {
    assertThat(AdminStatisticsRules.money(new BigDecimal("900719925474099.12"), 2))
        .isEqualTo("900719925474099.12");
    assertThat(AdminStatisticsRules.money(new BigDecimal("1.235"), 2)).isEqualTo("1.24");
  }

  @Test
  void metadataCopiesTheResolvedQueryAndDeclaresItsBasisAndZone() {
    AdminStatisticsQuery query = resolve("2026-09-01", "2026-09-25", "INR");
    var metadata = AdminStatisticsRules.metadata(query);

    assertThat(metadata.from()).isEqualTo(query.from());
    assertThat(metadata.to()).isEqualTo(query.to());
    assertThat(metadata.currency()).isEqualTo("INR");
    assertThat(metadata.currencyScale()).isEqualTo(2);
    assertThat(metadata.reportingZone()).isEqualTo("Asia/Kolkata");
    assertThat(metadata.fromInclusive()).isEqualTo(query.fromInclusive());
    assertThat(metadata.toExclusive()).isEqualTo(query.toExclusive());
    assertThat(metadata.generatedAt()).isEqualTo(NOW);
    assertThat(metadata.periodBasis()).isEqualTo("PAYMENT_CREATED_AT");
    assertThat(ZoneId.of(metadata.reportingZone())).isEqualTo(ZoneId.of("Asia/Kolkata"));
  }

  @Test
  void supportsStrictLeapDayDates() {
    AdminStatisticsQuery query = resolve("2024-02-29", "2024-02-29", "INR");

    assertThat(query.from()).isEqualTo(LocalDate.of(2024, 2, 29));
  }

  private static Stream<String> invalidDateShapes() {
    return Stream.of("2026-9-25", "2026-09-25T00:00:00", "20260925");
  }

  @ParameterizedTest
  @MethodSource("invalidDateShapes")
  void rejectsDateValuesOutsideStrictIsoShape(String from) {
    assertBusinessError("INVALID_REPORT_RANGE", () -> resolve(from, "2026-09-25", "INR"));
  }

  private static AdminStatisticsQuery resolve(String from, String to, String currency) {
    return AdminStatisticsRules.resolve(from, to, currency, INR, NOW);
  }

  private static void assertBusinessError(String code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.code()).isEqualTo(code);
            });
  }
}
