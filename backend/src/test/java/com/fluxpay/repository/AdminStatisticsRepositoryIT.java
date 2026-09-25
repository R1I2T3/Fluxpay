package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.User;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.AdminStatisticsQuery;
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
@Import(AdminStatisticsRepository.class)
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
}
