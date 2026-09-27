package com.fluxpay.service;

import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsOptionsResponse;
import com.fluxpay.dto.AdminStatisticsQuery;
import com.fluxpay.dto.AdminStatisticsResponse;
import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

public final class AdminStatisticsRules {
  public static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");
  public static final int MAXIMUM_RANGE_DAYS = 366;

  private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  private AdminStatisticsRules() {}

  public static AdminStatisticsQuery resolve(
      String from,
      String to,
      String currency,
      List<AdminStatisticsOptionsResponse.Currency> currencies,
      Instant now) {
    LocalDate fromDate = parseDate(from);
    LocalDate toDate = parseDate(to);
    String requestedCurrency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    if (requestedCurrency.isEmpty() || currencies == null) {
      throw invalidCurrency();
    }

    AdminStatisticsOptionsResponse.Currency chosen = null;
    for (AdminStatisticsOptionsResponse.Currency configured : currencies) {
      if (configured == null || configured.code() == null || configured.code().isBlank()) {
        throw invalidConfiguration();
      }
      if (configured.scale() < 0 || configured.scale() > 4) {
        throw invalidConfiguration();
      }
      if (configured.code().trim().toUpperCase(Locale.ROOT).equals(requestedCurrency)) {
        chosen = configured;
      }
    }
    if (chosen == null) {
      throw invalidCurrency();
    }

    LocalDate today = now.atZone(REPORT_ZONE).toLocalDate();
    long inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    if (inclusiveDays < 1 || inclusiveDays > MAXIMUM_RANGE_DAYS || toDate.isAfter(today)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REPORT_RANGE",
          "Select up to 366 days ending no later than today.");
    }

    Instant start = fromDate.atStartOfDay(REPORT_ZONE).toInstant();
    Instant end = toDate.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
    if (end.isAfter(now)) {
      end = now;
    }
    return new AdminStatisticsQuery(
        fromDate,
        toDate,
        chosen.code().trim().toUpperCase(Locale.ROOT),
        chosen.scale(),
        start,
        end,
        now);
  }

  public static PaymentStatus status(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return PaymentStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_REPORT_STATUS", "Select a valid payment status.");
    }
  }

  public static int page(String value) {
    int page = parsePagination(value, 0);
    if (page < 0) {
      throw invalidPage();
    }
    return page;
  }

  public static int size(String value) {
    int size = parsePagination(value, 20);
    if (size < 1 || size > 100) {
      throw invalidPage();
    }
    return size;
  }

  public static BigDecimal percentage(long numerator, long denominator) {
    if (denominator == 0) {
      return null;
    }
    return BigDecimal.valueOf(numerator)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }

  public static String money(BigDecimal value, int scale) {
    return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  public static AdminStatisticsResponse.Metadata metadata(AdminStatisticsQuery query) {
    return new AdminStatisticsResponse.Metadata(
        query.from(),
        query.to(),
        query.currency(),
        query.currencyScale(),
        REPORT_ZONE.getId(),
        query.fromInclusive(),
        query.toExclusive(),
        query.generatedAt(),
        "PAYMENT_CREATED_AT");
  }

  private static LocalDate parseDate(String value) {
    if (value == null || !ISO_DATE.matcher(value).matches()) {
      throw invalidRange();
    }
    try {
      return LocalDate.parse(value, DATE_FORMAT);
    } catch (DateTimeException exception) {
      throw invalidRange();
    }
  }

  private static int parsePagination(String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      throw invalidPage();
    }
  }

  private static BusinessException invalidRange() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST, "INVALID_REPORT_RANGE", "Use valid YYYY-MM-DD report dates.");
  }

  private static BusinessException invalidCurrency() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST, "INVALID_REPORT_CURRENCY", "Select a supported report currency.");
  }

  private static BusinessException invalidConfiguration() {
    return new BusinessException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INVALID_REPORT_CONFIGURATION",
        "The reporting currency configuration is invalid.");
  }

  private static BusinessException invalidPage() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST,
        "INVALID_REPORT_PAGE",
        "Page must be non-negative and size must be from 1 to 100.");
  }
}
