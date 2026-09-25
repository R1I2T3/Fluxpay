package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.User;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsQuery;
import com.fluxpay.dto.ProviderRow;
import com.fluxpay.service.ReportQueryService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS_ACTIVE", matches = "true")
@EnabledIfEnvironmentVariable(named = "ORACLE_TEST_JDBC_URL", matches = ".+")
@DataJpaTest(
    showSql = false,
    properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("oracle-it")
@Import({AdminStatisticsRepository.class, ReportQueryService.class})
class AdminStatisticsRepositoryIT {
  private static final Instant CUTOFF = Instant.parse("2026-09-25T12:00:00Z");
  private static final Instant INSIDE = Instant.parse("2026-09-10T08:00:00Z");
  private static final Instant PAYMENT_FROM = Instant.parse("2026-09-09T18:30:00Z");
  private static final Instant PAYMENT_TO = Instant.parse("2026-09-10T18:30:00Z");
  private static final AdminStatisticsQuery INR_QUERY =
      new AdminStatisticsQuery(
          LocalDate.parse("2026-09-10"),
          LocalDate.parse("2026-09-10"),
          "INR",
          2,
          PAYMENT_FROM,
          PAYMENT_TO,
          CUTOFF);

  @Autowired AdminStatisticsRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManager entityManager;
  @Autowired ReportQueryService legacyReports;

  private AdminStatisticsFixture fixture;

  @BeforeEach
  void assertDedicatedTestSchemaAndCreateFixture() {
    assertThat(System.getenv("ORACLE_TEST_USERNAME")).isEqualToIgnoringCase("FLUXPAY_TEST");
    assertThat(
            jdbc.queryForObject(
                "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual", String.class))
        .isEqualToIgnoringCase("FLUXPAY_TEST");
    fixture = new AdminStatisticsFixture(jdbc, entityManager);
  }

  @Test
  void listsConfiguredCurrenciesAndCountsOnlyUserCustomersCreatedBeforeCutoff() {
    assertThat(repository.currencies())
        .contains(new com.fluxpay.dto.AdminStatisticsOptionsResponse.Currency("INR", 2));

    long before = repository.totalCustomers(CUTOFF);
    fixture.customer("USER", CUTOFF.minusSeconds(60));
    fixture.customer("ADMIN", CUTOFF.minusSeconds(60));
    fixture.customer("SYSTEM", CUTOFF.minusSeconds(60));
    fixture.customer("USER", CUTOFF);

    assertThat(repository.totalCustomers(CUTOFF)).isEqualTo(before + 1);
  }

  @Test
  void groupsPaymentsByKolkataDayAndStatusWithCurrencyAndHalfOpenBounds() {
    List<AdminStatisticsRepository.PaymentBucket> beforeBuckets =
        repository.paymentBuckets(INR_QUERY);
    var before =
        beforeBuckets.stream()
            .map(AdminStatisticsRepository.PaymentBucket::completedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    fixture.payment("INR", "100.00", PaymentStatus.COMPLETED, INSIDE);
    fixture.payment("INR", "50.00", PaymentStatus.COMPLETED, PAYMENT_FROM);
    fixture.payment("USD", "999.00", PaymentStatus.COMPLETED, INSIDE);
    fixture.payment("INR", "88.00", PaymentStatus.FAILED, INSIDE);
    fixture.payment("INR", "15.00", PaymentStatus.COMPLETED, PAYMENT_FROM.minusSeconds(1));
    fixture.payment("INR", "16.00", PaymentStatus.COMPLETED, PAYMENT_TO);

    List<AdminStatisticsRepository.PaymentBucket> buckets = repository.paymentBuckets(INR_QUERY);
    assertThat(count(buckets, PaymentStatus.COMPLETED))
        // The in-range payment and the payment at Kolkata midnight are both included.
        .isEqualTo(count(beforeBuckets, PaymentStatus.COMPLETED) + 2);
    assertThat(count(buckets, PaymentStatus.FAILED))
        .isEqualTo(count(beforeBuckets, PaymentStatus.FAILED) + 1);
    assertThat(
            buckets.stream()
                .map(AdminStatisticsRepository.PaymentBucket::completedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(before.add(new BigDecimal("150.00")));
    assertThat(buckets)
        .anySatisfy(
            bucket -> {
              assertThat(bucket.date()).isEqualTo(LocalDate.parse("2026-09-10"));
              assertThat(bucket.status()).isEqualTo(PaymentStatus.COMPLETED);
              assertThat(bucket.count()).isGreaterThanOrEqualTo(1);
              assertThat(bucket.completedAmount()).isGreaterThanOrEqualTo(new BigDecimal("100.00"));
            })
        .anySatisfy(
            bucket -> {
              assertThat(bucket.date()).isEqualTo(LocalDate.parse("2026-09-10"));
              assertThat(bucket.status()).isEqualTo(PaymentStatus.FAILED);
              assertThat(bucket.count()).isGreaterThanOrEqualTo(1);
              assertThat(bucket.completedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            });
  }

  @Test
  void countsDailyRegistrationsAndUsesTheExistingOrmTimestampWriter() {
    Instant from = Instant.parse("2026-08-31T18:30:00Z");
    Instant to = Instant.parse("2026-09-03T18:30:00Z");
    var query =
        new AdminStatisticsQuery(
            LocalDate.parse("2026-09-01"),
            LocalDate.parse("2026-09-03"),
            "INR",
            2,
            from,
            to,
            CUTOFF);
    var before = repository.registrations(query);
    fixture.customer("USER", from.minusSeconds(1));
    fixture.customer("USER", from);
    fixture.customer("USER", to.minusSeconds(1));
    fixture.customer("USER", to);
    UUID userId = UUID.randomUUID();
    Instant registration = Instant.parse("2026-08-31T18:30:00Z");
    entityManager.persist(
        new User(
            userId,
            "orm-" + userId + "@stats-test.invalid",
            "!ORACLE_TEST_NO_LOGIN!",
            "USER",
            "ORM statistics fixture",
            registration,
            registration));
    entityManager.flush();
    entityManager.clear();

    assertThat(repository.registrations(query))
        .containsExactlyInAnyOrderElementsOf(withRegistrationsAddedAtBothInclusiveBounds(before));
  }

  @Test
  void returnsGlobalWorkloadAggregatesIncludingExactAgingBoundaryAndResubmissionTime() {
    var before = repository.workload(CUTOFF);
    fixture.kyc(CUTOFF.minusSeconds(24 * 60 * 60L), "PENDING");
    fixture.kyc(CUTOFF.minusSeconds(24 * 60 * 60L + 1), "PENDING");
    fixture.kyc(CUTOFF.minusSeconds(1), "PENDING");
    fixture.kyc(CUTOFF, "PENDING");
    fixture.kyc(CUTOFF.plusSeconds(1), "PENDING");
    fixture.kyc(CUTOFF.minusSeconds(1), "VERIFIED");
    for (int i = 0; i < 101; i++) {
      fixture.kyc(CUTOFF.minusSeconds(10), "PENDING");
    }

    UUID highRiskPayment =
        fixture.payment("INR", "1.00", PaymentStatus.COMPLETED, CUTOFF.minusSeconds(2));
    UUID lowRiskPayment =
        fixture.payment("INR", "1.00", PaymentStatus.COMPLETED, CUTOFF.minusSeconds(2));
    fixture.compliance(highRiskPayment, "HIGH", "OPEN", CUTOFF.minusSeconds(24 * 60 * 60L + 1));
    fixture.compliance(lowRiskPayment, "LOW", "OPEN", CUTOFF.minusSeconds(10));
    fixture.compliance(lowRiskPayment, "HIGH", "CLOSED", CUTOFF.minusSeconds(24 * 60 * 60L + 1));
    fixture.compliance(lowRiskPayment, "HIGH", "OPEN", CUTOFF);
    fixture.compliance(lowRiskPayment, "HIGH", "OPEN", CUTOFF.plusSeconds(1));
    fixture.ticket("OPEN", CUTOFF.minusSeconds(24 * 60 * 60L + 1));
    fixture.ticket("IN_PROGRESS", CUTOFF.minusSeconds(24 * 60 * 60L));
    fixture.ticket("RESOLVED", CUTOFF.minusSeconds(24 * 60 * 60L + 1));
    fixture.ticket("OPEN", CUTOFF);
    fixture.ticket("IN_PROGRESS", CUTOFF.plusSeconds(1));

    var after = repository.workload(CUTOFF);
    assertThat(after.kycPending()).isEqualTo(before.kycPending() + 104);
    assertThat(after.kycOver24h()).isEqualTo(before.kycOver24h() + 1);
    assertThat(after.kycPending()).isGreaterThan(100);
    assertThat(after.complianceOpen()).isEqualTo(before.complianceOpen() + 2);
    assertThat(after.complianceHighRisk()).isEqualTo(before.complianceHighRisk() + 1);
    assertThat(after.complianceOver24h()).isEqualTo(before.complianceOver24h() + 1);
    assertThat(after.ticketsOpen()).isEqualTo(before.ticketsOpen() + 2);
    assertThat(after.ticketsOver24h()).isEqualTo(before.ticketsOver24h() + 1);
  }

  @Test
  void groupsProviderAttemptsByPaymentCreationCohortAndAttemptCutoff() {
    var paymentBucketsBefore = repository.paymentBuckets(INR_QUERY);
    var amountsBefore =
        paymentBucketsBefore.stream()
            .map(AdminStatisticsRepository.PaymentBucket::completedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    var countsBefore = count(paymentBucketsBefore, PaymentStatus.COMPLETED);

    UUID providerId = fixture.provider("STAT", true, "Archived statistics provider");
    UUID routeId = fixture.route(providerId);
    UUID paymentId = fixture.payment("INR", "25.00", PaymentStatus.COMPLETED, INSIDE);
    Instant afterPaymentRange = PAYMENT_TO.plusSeconds(1);
    fixture.attempt(paymentId, routeId, 1, "FAILED", INSIDE.plusSeconds(60));
    fixture.attempt(paymentId, routeId, 2, "COMPLETED", afterPaymentRange);

    var paymentBucketsAfterTarget = repository.paymentBuckets(INR_QUERY);
    assertThat(count(paymentBucketsAfterTarget, PaymentStatus.COMPLETED) - countsBefore)
        .isEqualTo(1);
    assertThat(
            paymentBucketsAfterTarget.stream()
                .map(AdminStatisticsRepository.PaymentBucket::completedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(amountsBefore.add(new BigDecimal("25.00")));

    UUID processingProvider = fixture.provider("PROC", true, "Processing statistics provider");
    UUID processingRoute = fixture.route(processingProvider);
    UUID processingPayment =
        fixture.payment("INR", "3.00", PaymentStatus.COMPLETED, INSIDE.plusSeconds(1));
    fixture.attempt(processingPayment, processingRoute, 1, "PROCESSING", INSIDE.plusSeconds(2));

    UUID usdProvider = fixture.provider("USD", true, "USD statistics provider");
    UUID usdRoute = fixture.route(usdProvider);
    UUID usdPayment = fixture.payment("USD", "7.00", PaymentStatus.COMPLETED, INSIDE);
    fixture.attempt(usdPayment, usdRoute, 1, "COMPLETED", INSIDE.plusSeconds(2));
    fixture.standaloneAttempt("P-NON-UUID", routeId, INSIDE.plusSeconds(120));

    var row =
        repository.providers(INR_QUERY).stream()
            .filter(item -> item.providerId().equals(providerId))
            .findFirst()
            .orElseThrow();
    assertThat(row.providerCode()).contains("STAT_");
    assertThat(row.providerName()).isEqualTo("Archived statistics provider");
    assertThat(row.totalAttempts()).isEqualTo(2);
    assertThat(row.completedAttempts()).isEqualTo(1);
    assertThat(row.failedAttempts()).isEqualTo(1);
    assertThat(row.inProgressAttempts()).isZero();

    var processing =
        repository.providers(INR_QUERY).stream()
            .filter(item -> item.providerId().equals(processingProvider))
            .findFirst()
            .orElseThrow();
    assertThat(processing.totalAttempts()).isEqualTo(1);
    assertThat(processing.inProgressAttempts()).isEqualTo(1);
    assertThat(repository.providers(INR_QUERY))
        .noneMatch(item -> item.providerId().equals(usdProvider));
  }

  @Test
  void keepsSameNamedProvidersSeparateInNewApiAndGroupsThemInLegacyApi() {
    String sharedName = "Same name " + UUID.randomUUID();
    UUID firstProvider = fixture.provider("SAME_A", false, sharedName);
    UUID secondProvider = fixture.provider("SAME_B", false, sharedName);
    UUID firstRoute = fixture.route(firstProvider);
    UUID secondRoute = fixture.route(secondProvider);
    UUID payment = fixture.payment("INR", "1.00", PaymentStatus.COMPLETED, INSIDE);
    fixture.attempt(payment, firstRoute, 1, "FAILED", INSIDE.plusSeconds(10));
    fixture.attempt(payment, secondRoute, 2, "COMPLETED", INSIDE.plusSeconds(20));

    var providerRows =
        repository.providers(INR_QUERY).stream()
            .filter(row -> row.providerName().equals(sharedName))
            .toList();
    assertThat(providerRows).hasSize(2);
    assertThat(providerRows)
        .extracting(AdminStatisticsRepository.ProviderAggregate::providerId)
        .containsExactlyInAnyOrder(firstProvider, secondProvider);

    Instant legacyFrom = INSIDE;
    Instant legacyTo = INSIDE.plusSeconds(15);
    List<ProviderRow> legacyRows = legacyReports.providerSummary(legacyFrom, legacyTo);
    assertThat(legacyRows.stream().filter(row -> row.providerName().equals(sharedName)))
        .containsExactly(new ProviderRow(sharedName, 1, 0, 1));
  }

  @Test
  void pagesSameTimestampPaymentsStablyAndSharesCurrencyStatusAndRangePredicates() {
    Instant cohort = Instant.parse("1901-02-03T08:00:00Z");
    var query =
        new AdminStatisticsQuery(
            LocalDate.parse("1901-02-03"),
            LocalDate.parse("1901-02-03"),
            "INR",
            2,
            Instant.parse("1901-02-03T00:00:00Z"),
            Instant.parse("1901-02-04T00:00:00Z"),
            CUTOFF);
    assertThat(repository.paymentPage(query, null, 0, 20).total()).isZero();

    java.util.ArrayList<UUID> fixtureIds = new java.util.ArrayList<>();
    for (int index = 0; index < 41; index++) {
      PaymentStatus status = index % 3 == 0 ? PaymentStatus.FAILED : PaymentStatus.COMPLETED;
      fixtureIds.add(fixture.payment("INR", "7.50", status, cohort));
    }
    fixture.payment("USD", "99.00", PaymentStatus.COMPLETED, cohort);
    fixture.payment("INR", "88.00", PaymentStatus.COMPLETED, query.toExclusive());

    List<UUID> expected =
        fixtureIds.stream()
            .sorted(java.util.Comparator.comparing(AdminStatisticsRepositoryIT::rawHex).reversed())
            .toList();
    var first = repository.paymentPage(query, null, 0, 20);
    var second = repository.paymentPage(query, null, 1, 20);
    var third = repository.paymentPage(query, null, 2, 20);
    assertThat(first.total()).isEqualTo(41);
    assertThat(second.total()).isEqualTo(41);
    assertThat(third.total()).isEqualTo(41);
    assertThat(first.items())
        .extracting(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
        .containsExactlyElementsOf(expected.subList(0, 20));
    assertThat(second.items())
        .extracting(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
        .containsExactlyElementsOf(expected.subList(20, 40));
    assertThat(third.items())
        .extracting(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
        .containsExactly(expected.get(40));
    assertThat(first.items())
        .extracting(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
        .doesNotContainAnyElementsOf(
            second.items().stream()
                .map(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
                .toList());
    assertThat(second.items())
        .extracting(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
        .doesNotContainAnyElementsOf(
            third.items().stream()
                .map(com.fluxpay.dto.AdminStatisticsPaymentPageResponse.Row::paymentId)
                .toList());
    assertThat(repository.paymentPage(query, PaymentStatus.FAILED, 0, 100).total()).isEqualTo(14);
    assertThat(repository.paymentPage(query, PaymentStatus.FAILED, 0, 100).items())
        .allSatisfy(row -> assertThat(row.status()).isEqualTo(PaymentStatus.FAILED));
    var outOfRange = repository.paymentPage(query, null, 999, 20);
    assertThat(outOfRange.items()).isEmpty();
    assertThat(outOfRange.total()).isEqualTo(41);
  }

  @Test
  void legacyProviderReportReturnsEmptyProvidersAndHonorsAttemptTimeBounds() {
    UUID providerId =
        fixture.provider("EMPTY", false, "Provider with no routes " + UUID.randomUUID());
    String legacyProviderName = "Legacy interval " + UUID.randomUUID();
    String providerName =
        jdbc.queryForObject(
            "SELECT provider_name FROM transfer_providers WHERE id = HEXTORAW(?)",
            String.class,
            providerId.toString().replace("-", ""));
    UUID withAttempts = fixture.provider("LEGACY", false, legacyProviderName);
    UUID routeId = fixture.route(withAttempts);
    UUID paymentId = fixture.payment("INR", "1.00", PaymentStatus.COMPLETED, INSIDE);
    fixture.attempt(paymentId, routeId, 1, "FAILED", INSIDE.plusSeconds(10));
    fixture.attempt(paymentId, routeId, 2, "COMPLETED", INSIDE.plusSeconds(20));

    Instant from = INSIDE;
    Instant to = INSIDE.plusSeconds(15);
    List<ProviderRow> rows = legacyReports.providerSummary(from, to);
    assertThat(rows).contains(new ProviderRow(providerName, 0, 0, 0));
    assertThat(rows).contains(new ProviderRow(legacyProviderName, 1, 0, 1));
    assertThat(rows.stream().filter(row -> row.providerName().equals(legacyProviderName)))
        .singleElement()
        .satisfies(row -> assertThat(row.totalAttempts()).isEqualTo(1));
  }

  private List<AdminStatisticsRepository.CustomerBucket>
      withRegistrationsAddedAtBothInclusiveBounds(
          List<AdminStatisticsRepository.CustomerBucket> original) {
    java.util.Map<LocalDate, Long> counts = new java.util.HashMap<>();
    original.forEach(bucket -> counts.put(bucket.date(), bucket.count()));
    counts.merge(LocalDate.parse("2026-09-01"), 2L, Long::sum);
    counts.merge(LocalDate.parse("2026-09-03"), 1L, Long::sum);
    return counts.entrySet().stream()
        .map(
            entry -> new AdminStatisticsRepository.CustomerBucket(entry.getKey(), entry.getValue()))
        .toList();
  }

  private long count(List<AdminStatisticsRepository.PaymentBucket> buckets, PaymentStatus status) {
    return buckets.stream()
        .filter(bucket -> bucket.date().equals(LocalDate.parse("2026-09-10")))
        .filter(bucket -> bucket.status() == status)
        .mapToLong(AdminStatisticsRepository.PaymentBucket::count)
        .sum();
  }

  private static String rawHex(UUID id) {
    return id.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
  }
}
