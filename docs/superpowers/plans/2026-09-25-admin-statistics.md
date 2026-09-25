# Admin Statistics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ADMIN-only Statistics page showing accurate send-money activity, provider outcomes, customer registrations, current workload, and payment drill-downs.

**Architecture:** Read-only Oracle aggregates feed a summary API, an options API, and a paginated payment API. The existing Oracle JET/Knockout admin shell hosts the page and links records to the existing Payment Operations view. Financial totals come from payment rows; provider attempts remain a separate measure.

**Tech Stack:** Existing Spring Boot/JPA/JdbcTemplate, Oracle, Java records/BigDecimal/Clock, Oracle JET 16.1, Knockout, TypeScript, Node test runner, JUnit/MockMvc, Maven wrapper.

**Spec:** [Approved Admin Statistics design](../specs/2026-09-25-admin-statistics-design.md). Read both documents before execution.

## Global Constraints

- "Preserve authentication, session restoration, expiry behavior, API-base selection, and the existing ADMIN role."
- "Add the Statistics navigation entry without redesigning Overview or the customer dashboard."
- "No commits, branch changes, or deployment are part of this planning task."
- "The selector means source currency (`payments.currency`)."
- "Require from <= to, no future end date, and at most 366 inclusive calendar days."
- Reporting timezone: `Asia/Kolkata`; default range: Last 30 days; initial currency: INR when configured, otherwise first alphabetical code.
- "Payment statistics describe payments CREATED in the selected period and their CURRENT outcomes."
- "Amounts travel as decimal strings and are aggregated with Oracle NUMBER and Java BigDecimal. Counts are integers."
- "Use 20 rows by default, server maximum 100, zero-based pages, and deterministic `created_at DESC, id DESC` ordering."
- "There is no polling timer or background refresh in this version."
- "All operations are read-only. Do not acquire payment-execution locks."
- "Never edit an applied migration."
- "Wallet top-ups, direct wallet transfers, exchanges and withdrawals" are excluded from financial reporting in this version.
- Existing colors, compact spacing, keyboard access, and text alternatives are required; no new chart package is needed.
- Execution is not authorized by this planning deliverable. Preserve the user's branch/commit preferences when execution is requested; task checkpoints below do not instruct automatic commits.

## Review Focus

These five easily missed cases have explicit tests assigned below:

1. An ORM-written TIMESTAMP near India midnight must fall into the same day under UTC and India JVM configurations; no silent assumption that every unzoned stored timestamp is UTC. Tasks 2 and 8.
2. A payment retried after the selected creation period counts once as a payment but contributes all eligible attempts; arbitrary text attempt IDs must not crash SQL. Task 3.
3. Currency configuration can be empty, a bookmarked currency can be removed, and a large decimal can exceed JavaScript integer precision; show an explicit state or exact amount. Tasks 1 and 5.
4. Parameter-only navigation, browser Back, rapid filter changes, and logout can race requests; the page must not display a response for an old route or session. Tasks 6 and 7.
5. The clock can be exactly at midnight, a denominator can be zero, and page 999 can be empty despite a positive total; empty successful results must remain distinguishable from errors. Tasks 1, 4, and 7.

## File map and ownership

All paths below are relative to the repository root. The inspected baseline is
`a84c2b8` on `main`; recheck local changes before implementation. Do not assume
the checkout will still be clean or on that commit.

| Responsibility | Files |
| --- | --- |
| Public Java contracts | New `backend/src/main/java/com/fluxpay/dto/AdminStatisticsResponse.java`, `AdminStatisticsOptionsResponse.java`, `AdminStatisticsPaymentPageResponse.java`, `AdminStatisticsQuery.java` in the same dto directory |
| Query rules and calculations | New `backend/src/main/java/com/fluxpay/service/AdminStatisticsRules.java` |
| Oracle reporting projections | New `backend/src/main/java/com/fluxpay/repository/AdminStatisticsRepository.java` |
| Reporting orchestration | New `backend/src/main/java/com/fluxpay/service/AdminStatisticsService.java` |
| HTTP entry points | Modify `backend/src/main/java/com/fluxpay/controller/AdminReportController.java` |
| Existing provider report repair | Modify `backend/src/main/java/com/fluxpay/service/ReportQueryService.java` |
| Backend tests | New `backend/src/test/java/com/fluxpay/service/AdminStatisticsRulesTest.java`, `AdminStatisticsServiceTest.java`; new `backend/src/test/java/com/fluxpay/repository/AdminStatisticsRepositoryIT.java`, `AdminStatisticsFixture.java`; modify existing `AdminReportControllerMvcTest.java` and `ReportQueryServiceTest.java` in their current controller/service test directories |
| Browser contracts and pure helpers | New `frontend/fluxpay-ui/src/ts/services/admin-statistics-contracts.ts`, `admin-statistics.ts` |
| HTTP wrappers | Modify `frontend/fluxpay-ui/src/ts/services/flux-api.ts` |
| Page lifecycle and template | New `frontend/fluxpay-ui/src/ts/viewModels/admin-statistics.ts`, `frontend/fluxpay-ui/src/ts/views/admin-statistics.html` |
| Styling and navigation | New `frontend/fluxpay-ui/src/css/admin-statistics.css`; modify `frontend/fluxpay-ui/src/index.html` and `frontend/fluxpay-ui/src/ts/appController.ts` |
| Browser tests | New `frontend/fluxpay-ui/tests/admin-statistics.test.cjs`, `admin-statistics-page.test.cjs`, `admin-statistics-api.test.cjs`, `admin-statistics-fixture.cjs`; modify existing `admin-navigation.test.cjs` |
| Usage and acceptance documentation | Modify `docs/api-catalog.md`; create `docs/admin-statistics.md` |

`AdminStatisticsRules` and `AdminStatisticsQuery` are small implementation units
for the service's approved validation responsibility. The repository owns SQL,
row mapping, and timestamp binding. The service owns aggregation of already
grouped rows, zero-filled days, and response construction. Browser helpers do
not recompute business metrics from individual records.

## Execution order

| Task | Deliverable | Depends on |
| --- | --- | --- |
| 1 | Typed contracts and date/currency/math rules | Approved spec |
| 2 | Payment, customer, and workload database projections | 1 |
| 3 | Provider projections and existing report SQL correction | 2 |
| 4 | Summary/options/payment-list APIs | 1-3 |
| 5 | Browser contracts, API wrappers, filter/format helpers | 1 and 4 contracts |
| 6 | Statistics page, charts, navigation, lifecycle | 5 |
| 7 | Payment drill-downs and restored navigation state | 4 and 6 |
| 8 | Documentation and integrated verification | 1-7 |

Use the current workspace for planning. At execution time, first inspect
`git status --short` and relevant instructions, and honor any user request about
checkout or worktree. Do not install dependencies, modify product files, seed a
database, or create a branch while only reviewing this plan.

## Contract dictionary

These names are shared across tasks; use them consistently. Java records use
the field order below. Use nested records in each response file rather than a
separate file per small projection. JSON dates/instants/UUIDs are strings.

```java
// dto/AdminStatisticsOptionsResponse.java
public record AdminStatisticsOptionsResponse(
    List<Currency> currencies, String defaultCurrency,
    String reportingZone, LocalDate today, int maximumRangeDays) {
  public record Currency(String code, int scale) {}
}

// dto/AdminStatisticsQuery.java
public record AdminStatisticsQuery(
    LocalDate from, LocalDate to, String currency, int currencyScale,
    Instant fromInclusive, Instant toExclusive, Instant generatedAt) {}

// dto/AdminStatisticsResponse.java
public record AdminStatisticsResponse(
    Metadata meta, PaymentSummary paymentSummary, List<PaymentDay> paymentTrend,
    List<StatusCount> paymentStatuses, List<ProviderStats> providers,
    CustomerSummary customers, List<CustomerDay> customerTrend, Workload workload) {
  public record Metadata(
      LocalDate from, LocalDate to, String currency, int currencyScale,
      String reportingZone, Instant fromInclusive, Instant toExclusive,
      Instant generatedAt, String periodBasis) {}
  public record PaymentSummary(
      long paymentCount, long completedCount, String completedAmount,
      BigDecimal payoutSuccessRate, long failedCount, long processingCount) {}
  public record PaymentDay(LocalDate date, long paymentCount, String completedAmount) {}
  public record StatusCount(PaymentStatus status, long count) {}
  public record ProviderStats(
      UUID providerId, String providerCode, String providerName, long totalAttempts,
      long completedAttempts, long failedAttempts, long inProgressAttempts,
      BigDecimal successRate) {}
  public record CustomerSummary(long totalCustomers, long newRegistrations) {}
  public record CustomerDay(LocalDate date, long registrations) {}
  public record Workload(
      long kycPending, long kycOver24h, long complianceOpen,
      long complianceHighRisk, long complianceOver24h,
      long ticketsOpen, long ticketsOver24h) {}
}

// dto/AdminStatisticsPaymentPageResponse.java
public record AdminStatisticsPaymentPageResponse(
    AdminStatisticsResponse.Metadata meta, PaymentStatus status, int page, int size,
    long totalElements, long totalPages, List<Row> items) {
  public record Row(
      UUID paymentId, Instant createdAt, String sourceAmount,
      String sourceCurrency, PaymentStatus status) {}
}
```

Use immutable lists when constructing the DTOs. Both rate fields are nullable.
`defaultCurrency` is null when the configured currency list is empty.
`periodBasis` is the fixed string `PAYMENT_CREATED_AT`. Do not serialize service
exceptions, JPA entities, payment snapshots, or account data into these responses.

### Task 1: Define reporting contracts and rules

**Files:** Create the four DTO files and `service/AdminStatisticsRules.java`;
create `service/AdminStatisticsRulesTest.java` in the backend test source tree.

**Interfaces:**

```java
// Public static methods on AdminStatisticsRules.
AdminStatisticsQuery resolve(String from, String to, String currency,
    List<AdminStatisticsOptionsResponse.Currency> currencies, Instant now);
PaymentStatus status(String value); // null for absent/blank filter
int page(String value);             // default 0; reject negative/noninteger
int size(String value);             // default 20; valid 1..100
BigDecimal percentage(long numerator, long denominator); // null if denominator == 0
String money(BigDecimal value, int scale);
AdminStatisticsResponse.Metadata metadata(AdminStatisticsQuery query);
```

- [ ] **1. Write failing rule tests with a fixed clock instant.** Include the following assertions plus parameterized invalid dates, future dates, unsupported currency, invalid status, and pagination cases.

```java
var currencies = List.of(new AdminStatisticsOptionsResponse.Currency("INR", 2));
var now = Instant.parse("2026-09-25T12:00:00Z");
var q = AdminStatisticsRules.resolve("2026-09-01", "2026-09-25", " inr ", currencies, now);
assertThat(q.fromInclusive()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
assertThat(q.toExclusive()).isEqualTo(now);
assertThat(q.currency()).isEqualTo("INR");
assertThat(AdminStatisticsRules.percentage(1, 3)).isEqualByComparingTo("33.33");
assertThat(AdminStatisticsRules.percentage(0, 0)).isNull();
assertThat(AdminStatisticsRules.money(new BigDecimal("900719925474099.12"), 2))
    .isEqualTo("900719925474099.12");
```

Test 366 inclusive days accepted and 367 rejected. At
`2026-09-24T18:30:00Z`, a Today query for September 25 has equal effective
start/end instants and is valid empty data. Test empty currency configuration
rejects a dashboard currency but does not require options to invent a currency.

- [ ] **2. Run the tests and confirm the new classes/behavior are missing.** From repository root:

```powershell
.\mvnw.cmd -f backend/pom.xml -Dtest=AdminStatisticsRulesTest test
```

Expected initially: compile/test failure identifying the new reporting rules.

- [ ] **3. Implement the DTOs and strict rules.** Use this boundary/math logic inside `resolve`, after strict YYYY-MM-DD parsing and currency lookup:

```java
public static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");
public static final int MAXIMUM_RANGE_DAYS = 366;

// fromDate/toDate are parsed LocalDate values; chosen is the matched Currency.
LocalDate today = now.atZone(REPORT_ZONE).toLocalDate();
long inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
if (inclusiveDays < 1 || inclusiveDays > MAXIMUM_RANGE_DAYS || toDate.isAfter(today)) {
  throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_RANGE",
      "Select up to 366 days ending no later than today.");
}
Instant start = fromDate.atStartOfDay(REPORT_ZONE).toInstant();
Instant end = toDate.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
if (end.isAfter(now)) end = now;
return new AdminStatisticsQuery(fromDate, toDate, chosen.code(), chosen.scale(), start, end, now);
```

```java
public static BigDecimal percentage(long numerator, long denominator) {
  if (denominator == 0) return null;
  return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
      .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
}

public static String money(BigDecimal value, int scale) {
  return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
}
```

Reject date strings not matching `^\d{4}-\d{2}-\d{2}$`, impossible dates, missing
required date/currency values, and configured scales outside the application's
supported 0..4 range. Bad configuration is a server error, not an empty result.
Normalize currency/status with Locale.ROOT. Return INVALID_REPORT_RANGE,
INVALID_REPORT_CURRENCY, INVALID_REPORT_STATUS, and INVALID_REPORT_PAGE using
BusinessException. Page and size parse failures must also use INVALID_REPORT_PAGE.
The metadata method copies the query fields and supplies the fixed zone/basis.

- [ ] **4. Rerun the rule tests and review every error code.** Expected: all new rule assertions pass; no database needed. Review this task as one checkpoint before SQL work.

### Task 2: Query payment, customer, and workload aggregates

**Files:** Create `repository/AdminStatisticsRepository.java`,
`repository/AdminStatisticsRepositoryIT.java` and `repository/AdminStatisticsFixture.java`
under the corresponding main/test backend source roots.

**Interfaces:** The repository is a Spring `@Repository`, with constructor
`(JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory)`.
Define these nested projection records and methods:

```java
public record PaymentBucket(LocalDate date, PaymentStatus status, long count,
                            BigDecimal completedAmount) {}
public record CustomerBucket(LocalDate date, long count) {}

List<AdminStatisticsOptionsResponse.Currency> currencies();
List<PaymentBucket> paymentBuckets(AdminStatisticsQuery query);
long totalCustomers(Instant cutoff);
List<CustomerBucket> registrations(AdminStatisticsQuery query);
AdminStatisticsResponse.Workload workload(Instant cutoff);
```

- [ ] **1. Establish a transactionally isolated Oracle fixture and failing aggregate tests.** Use `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)`, `@ActiveProfiles("oracle-it")`, and `@Import(AdminStatisticsRepository.class)` on the IT. Gate it with ORACLE_TESTS_ACTIVE and ORACLE_TEST_JDBC_URL. Before inserting fixtures, assert both ORACLE_TEST_USERNAME and `SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual` are FLUXPAY_TEST. Test transactions roll back; no TRUNCATE, cleanup of unrelated records, or schema reset.

Create a test-only `AdminStatisticsFixture` with the following exact contract.
It owns randomly generated identifiers so tests can coexist with preexisting
test-schema records. Use before/after aggregate deltas for global workload and
customer assertions; use a fixture-exclusive historical day for payment queries.

```java
AdminStatisticsFixture(JdbcTemplate jdbc, EntityManager entityManager);
UUID customer(String role, Instant createdAt);
UUID payment(String currency, String amount, PaymentStatus status, Instant createdAt);
UUID provider(String code, boolean archived);
UUID route(UUID providerId);
void attempt(UUID paymentId, UUID routeId, int number, String status, Instant initiatedAt);
void standaloneAttempt(String paymentId, UUID routeId, Instant initiatedAt);
void kyc(Instant submittedAt, String status);
void compliance(UUID paymentId, String risk, String status, Instant createdAt);
void ticket(String status, Instant createdAt);
```

The helper inserts prerequisite USER, CUSTOMER wallet, ACTIVE recipient, and
provider/route rows using the current V001/V002/V003/V601/V006 schema, never
legacy table names. Populate all NOT NULL routing fields with valid values:
rail BANK_NETWORK, external destination IN/INR, fee 0, spread 0, estimated minutes
1, configured success 99, active 1, system_protected 0, version 0, and timestamps.
Keep provider/route codes unique using an uppercase UUID suffix. Use bound
parameters and RAW UUID bytes. For KYC, set decision metadata consistently when
not PENDING; each KYC helper call creates a separate customer.

Add these checks against real repository results:

```java
var before = repository.totalCustomers(cutoff);
fixture.customer("USER", cutoff.minusSeconds(60));
fixture.customer("ADMIN", cutoff.minusSeconds(60));
fixture.customer("SYSTEM", cutoff.minusSeconds(60));
assertThat(repository.totalCustomers(cutoff)).isEqualTo(before + 1);

var amountBefore = repository.paymentBuckets(inrQuery).stream()
    .map(AdminStatisticsRepository.PaymentBucket::completedAmount)
    .reduce(BigDecimal.ZERO, BigDecimal::add);
fixture.payment("INR", "100.00", PaymentStatus.COMPLETED, inside);
fixture.payment("USD", "999.00", PaymentStatus.COMPLETED, inside);
var buckets = repository.paymentBuckets(inrQuery);
assertThat(buckets.stream().map(AdminStatisticsRepository.PaymentBucket::completedAmount)
    .reduce(BigDecimal.ZERO, BigDecimal::add))
    .isEqualByComparingTo(amountBefore.add(new BigDecimal("100.00")));
```

Here `cutoff`, `inside`, and `inrQuery` are method-local fixed instants/query
values from Task 1. Add fixtures immediately before/at/after each period bound,
at exactly 24 hours old and one second older, more than 100 pending KYC rows,
and a resubmitted KYC case with old created_at but recent submitted_at.

- [ ] **2. Run the focused Oracle test to establish failure.** With the existing dedicated integration environment configured:

```powershell
.\mvnw.cmd -f backend/pom.xml -Pintegration -Dtest=AdminStatisticsRulesTest -Dit.test=AdminStatisticsRepositoryIT verify
```

Expected initially: missing repository/query behavior. If prerequisites are
absent, record the environment gate; do not use portable mocks as proof of SQL.

- [ ] **3. Implement aggregate SQL and deliberate timestamp binding.** Resolve the effective Hibernate JDBC storage timezone once from the EntityManagerFactory. If Hibernate has no explicit JDBC timezone, capture the JVM timezone used by its existing timestamp writer. Pass that resolved zone explicitly to all reporting comparisons/extractions and SQL day conversion; do not change global application configuration.

```java
TimeZone configured = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
    .getSessionFactoryOptions().getJdbcTimeZone();
ZoneId storageZone = configured == null ? ZoneId.systemDefault() : configured.toZoneId();

// For existing TIMESTAMP columns, mirror the ORM writer explicitly.
ps.setTimestamp(index, Timestamp.from(instant),
    Calendar.getInstance(TimeZone.getTimeZone(storageZone)));
// For TIMESTAMP WITH TIME ZONE columns, bind an instant-bearing value.
ps.setObject(index, instant.atOffset(ZoneOffset.UTC));
```

Use `getTimestamp(column, Calendar)` for unzoned timestamp extraction and raw
UUID bytes mapped with ByteBuffer for identifiers. The local Hibernate 6.4.4
dependency exposes getJdbcTimeZone returning TimeZone. Exercise its behavior
through the ORM-backed test below; checking the signature does not prove
timestamp semantics.

Payment aggregation is one bounded grouped result, not a list of payments:

```sql
SELECT created_day, status, COUNT(*) AS payment_count,
       SUM(CASE WHEN status = 'COMPLETED' THEN amount ELSE 0 END) AS completed_amount
FROM (
  SELECT TO_CHAR(FROM_TZ(p.created_at, ?) AT TIME ZONE 'Asia/Kolkata',
                 'YYYY-MM-DD') AS created_day,
         p.status, p.amount
  FROM payments p
  WHERE p.created_at >= ? AND p.created_at < ? AND p.currency = ?
)
GROUP BY created_day, status
ORDER BY created_day, status
```

Bind storageZone ID, query start, query end, and currency in that order. The
maximum grouped result is 366 days times nine statuses. No attempt join belongs
in this query. Read money with `getBigDecimal` and counts with `getLong`.

```sql
SELECT TRIM(code) AS code, scale FROM currencies ORDER BY code;

SELECT COUNT(*) FROM users WHERE role = 'USER' AND created_at < ?;

SELECT created_day, COUNT(*) AS registrations
FROM (
  SELECT TO_CHAR(FROM_TZ(u.created_at, ?) AT TIME ZONE 'Asia/Kolkata',
                 'YYYY-MM-DD') AS created_day
  FROM users u
  WHERE u.role = 'USER' AND u.created_at >= ? AND u.created_at < ?
)
GROUP BY created_day
ORDER BY created_day;
```

Use these three workload aggregates and combine their projections into Workload.
The age bound is cutoff minus exactly 24 hours; each query also excludes records
created after the request cutoff. Bind each column using its actual SQL type.

```sql
SELECT COUNT(*) AS pending,
       COALESCE(SUM(CASE WHEN submitted_at < ? THEN 1 ELSE 0 END), 0) AS aged
FROM kyc_cases WHERE status = 'PENDING' AND submitted_at < ?;

SELECT COUNT(*) AS open_count,
       COALESCE(SUM(CASE WHEN risk = 'HIGH' THEN 1 ELSE 0 END), 0) AS high_risk,
       COALESCE(SUM(CASE WHEN created_at < ? THEN 1 ELSE 0 END), 0) AS aged
FROM compliance_cases WHERE status = 'OPEN' AND created_at < ?;

SELECT COUNT(*) AS open_count,
       COALESCE(SUM(CASE WHEN created_at < ? THEN 1 ELSE 0 END), 0) AS aged
FROM support_tickets WHERE status IN ('OPEN', 'IN_PROGRESS') AND created_at < ?;
```

- [ ] **4. Verify ORM timestamp compatibility explicitly.** Persist a User through EntityManager with creation instant `2026-08-31T18:30:00Z`, flush/clear, and verify that the September 1 registration bucket increments. Repeat focused IT execution under `-Duser.timezone=UTC` and `-Duser.timezone=Asia/Kolkata` in Task 8. This pins reporting to the existing writer without changing authentication, JPA mappings, or global timezone settings. If actual stored writer behavior differs, correct reporting-local binding before accepting the queries.

- [ ] **5. Rerun the targeted integration tests and inspect row totals.** The scoped INR query must exclude USD, the complete KYC count must exceed 100, and exact-midnight/24-hour assertions must pass. This is the database projection review checkpoint.

### Task 3: Add provider statistics and repair the legacy provider report

**Files:** Extend `AdminStatisticsRepository.java` and its IT/fixture; modify
`service/ReportQueryService.java` and `service/ReportQueryServiceTest.java`.

**Interfaces:** Add this nested repository record and method; preserve the
existing `ReportQueryService.providerSummary(Instant from, Instant to)` signature.

```java
public record ProviderAggregate(UUID providerId, String providerCode, String providerName,
    long totalAttempts, long completedAttempts, long failedAttempts, long inProgressAttempts) {}
List<ProviderAggregate> providers(AdminStatisticsQuery query);
```

- [ ] **1. Add failing real-SQL tests for cohort/attempt distinctions.** Create a completed INR payment with one FAILED attempt during its creation range and one COMPLETED attempt after that range but before query.generatedAt. Assert one completed payment, its amount once, two attempts, and provider counts 1 completed/1 failed. Add an archived provider, a PROCESSING attempt, a USD payment, and a standalone attempt with payment ID `P-NON-UUID`.

```java
var provider = fixture.provider("STAT_" + UUID.randomUUID().toString().replace("-", ""), true);
var route = fixture.route(provider);
var payment = fixture.payment("INR", "25.00", PaymentStatus.COMPLETED, created);
fixture.attempt(payment, route, 1, "FAILED", created.plusSeconds(60));
fixture.attempt(payment, route, 2, "COMPLETED", afterPeriod);
fixture.standaloneAttempt("P-NON-UUID", route, created.plusSeconds(120));
var row = repository.providers(query).stream()
    .filter(item -> item.providerId().equals(provider)).findFirst().orElseThrow();
assertThat(row.totalAttempts()).isEqualTo(2);
assertThat(row.completedAttempts()).isEqualTo(1);
assertThat(row.failedAttempts()).isEqualTo(1);
```

Use query dates that include `created` but exclude `afterPeriod`, with generatedAt
after both. Separately test equal provider names with distinct IDs: the new API
keeps separate rows; the legacy API groups by name as before.
Add ReportQueryService to the IT's @Import list and inject it as legacyReports
so the legacy assertions execute its real SQL in the same rollback transaction.

- [ ] **2. Run the focused IT and existing provider test before changing SQL.**

```powershell
.\mvnw.cmd -f backend/pom.xml -Pintegration '-Dtest=AdminStatisticsRulesTest,ReportQueryServiceTest' -Dit.test=AdminStatisticsRepositoryIT verify
```

- [ ] **3. Implement provider cohort SQL.** Bind the payment creation start/end/currency with Task 2's binding strategy and generatedAt as an offset-bearing instant.

```sql
SELECT v.id AS provider_id, v.provider_code, v.provider_name,
       COUNT(*) AS total_attempts,
       SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_attempts,
       SUM(CASE WHEN a.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_attempts,
       SUM(CASE WHEN a.status IN ('INITIATED', 'PROCESSING') THEN 1 ELSE 0 END) AS in_progress_attempts
FROM payments p
JOIN payout_attempts a
  ON REPLACE(UPPER(TRIM(a.payment_id)), '-', '') = RAWTOHEX(p.id)
JOIN transfer_routes r ON r.id = a.transfer_route_id
JOIN transfer_providers v ON v.id = r.provider_id
WHERE p.created_at >= ? AND p.created_at < ? AND p.currency = ?
  AND a.initiated_at < ?
GROUP BY v.id, v.provider_code, v.provider_name
ORDER BY total_attempts DESC, v.provider_code
```

Do not add provider/route active filters. Do not use configured_success_rate.
Text normalization must never attempt numeric/RAW conversion on an arbitrary ID.

- [ ] **4. Replace only the legacy query's obsolete schema references.** Keep the existing instant-based API and ProviderRow shape:

```sql
SELECT v.provider_name, COUNT(a.id) AS total_attempts,
       SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completed_attempts,
       SUM(CASE WHEN a.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_attempts
FROM transfer_providers v
LEFT JOIN transfer_routes r ON r.provider_id = v.id
LEFT JOIN payout_attempts a ON a.transfer_route_id = r.id
  AND a.initiated_at >= ? AND a.initiated_at < ?
GROUP BY v.provider_name
ORDER BY v.provider_name
```

Bind these TSTZ range parameters explicitly as OffsetDateTime. Add IT assertions
that a provider with no routes/attempts returns zero counts and a later retry is
excluded when its initiation time is outside the legacy interval. Keep existing
validation and controller tests intact; expand mocked tests only for behavior
they can actually prove.

- [ ] **5. Rerun both targeted tests.** Expected: provider statistics safely exclude unlinked attempts, retain archived history, and the legacy SQL executes on the current schema. Review this correction independently of the UI.

### Task 4: Expose summary, options, and paginated payment APIs

**Files:** Create `service/AdminStatisticsService.java` and its unit test;
extend `AdminStatisticsRepository.java` and its IT; modify
`controller/AdminReportController.java` and `controller/AdminReportControllerMvcTest.java`.

**Interfaces:**

```java
// Service constructor: (AdminStatisticsRepository repository, Clock clock).
AdminStatisticsOptionsResponse options();
AdminStatisticsResponse summary(String from, String to, String currency);
AdminStatisticsPaymentPageResponse payments(
    String from, String to, String currency, String status, String page, String size);

// Repository additions. Row is the public payment-page DTO's nested Row.
public record PaymentPage(List<AdminStatisticsPaymentPageResponse.Row> items, long total) {}
PaymentPage paymentPage(AdminStatisticsQuery query, PaymentStatus status, int page, int size);
```

- [ ] **1. Add service/controller failing tests around a complete response.** Mock the repository, not the service in service tests. Inject `Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC)`. Supply grouped rows with two completed payments, one failed, one refunded, one processing, and one draft. Expected payment count is 6 and payoutSuccessRate is 50.00. Return an empty provider list, zero customer buckets, and a zero Workload from repository mocks.

```java
var day = LocalDate.parse("2026-09-24");
when(repository.currencies()).thenReturn(List.of(new Currency("INR", 2)));
when(repository.paymentBuckets(any())).thenReturn(List.of(
    new PaymentBucket(day, PaymentStatus.COMPLETED, 2, new BigDecimal("125.00")),
    new PaymentBucket(day, PaymentStatus.FAILED, 1, BigDecimal.ZERO),
    new PaymentBucket(day, PaymentStatus.REFUNDED, 1, BigDecimal.ZERO),
    new PaymentBucket(day, PaymentStatus.PROCESSING, 1, BigDecimal.ZERO),
    new PaymentBucket(day, PaymentStatus.DRAFT, 1, BigDecimal.ZERO)));
when(repository.providers(any())).thenReturn(List.of());
when(repository.registrations(any())).thenReturn(List.of());
when(repository.workload(any())).thenReturn(new Workload(0, 0, 0, 0, 0, 0, 0));
var result = service.summary("2026-09-23", "2026-09-25", "INR");
assertThat(result.paymentSummary().paymentCount()).isEqualTo(6);
assertThat(result.paymentSummary().completedAmount()).isEqualTo("125.00");
assertThat(result.paymentSummary().payoutSuccessRate()).isEqualByComparingTo("50.00");
assertThat(result.paymentTrend()).hasSize(3);
assertThat(result.paymentStatuses()).hasSize(PaymentStatus.values().length);
assertThat(result.customerTrend()).hasSize(3);
```

Import Currency, PaymentBucket, and Workload from the nested types defined in
Tasks 1/2. Add cases for empty data, all-pending outcomes, provider percentage
rounding, registrations independent of currency, and totalCustomers independent
of the selected range. Options with an empty currency list must return null
defaultCurrency, not INR. Options sorts/validates configuration and returns the
current reporting-zone date from Clock.

Extend the existing MockMvc test with `@MockBean AdminStatisticsService statistics`
because its controller gains that constructor dependency. Test each new route
for ADMIN success, USER denial, and anonymous denial using existing auth fixtures.
Verify errors are the existing ApiError envelope, for example:

```java
when(statistics.summary("bad", "2026-09-25", "INR"))
    .thenThrow(new BusinessException(HttpStatus.BAD_REQUEST,
        "INVALID_REPORT_RANGE", "Use YYYY-MM-DD dates."));
mvc.perform(get("/api/admin/reports/statistics")
        .header("Authorization", "Bearer " + ADMIN_TOKEN)
        .queryParam("from", "bad").queryParam("to", "2026-09-25")
        .queryParam("currency", "INR"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("INVALID_REPORT_RANGE"));
```

- [ ] **2. Run the focused portable tests.**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=AdminStatisticsRulesTest,AdminStatisticsServiceTest,AdminReportControllerMvcTest,ReportQueryServiceTest' test
```

Expected initially: missing service/endpoints or response assertions.

- [ ] **3. Implement read-only service orchestration and zero-filled series.** Resolve currency and one `clock.instant()` per request. Build status totals from repository buckets with an EnumMap initialized with all nine enum values. Derive counts and completed amount from this same grouped result; never issue a per-payment request.

```java
Map<PaymentStatus, Long> counts = new EnumMap<>(PaymentStatus.class);
for (PaymentStatus status : PaymentStatus.values()) counts.put(status, 0L);
for (var bucket : buckets) counts.merge(bucket.status(), bucket.count(), Long::sum);
long completed = counts.get(PaymentStatus.COMPLETED);
long failed = counts.get(PaymentStatus.FAILED);
long refunded = counts.get(PaymentStatus.REFUNDED);
BigDecimal completedAmount = buckets.stream().map(PaymentBucket::completedAmount)
    .reduce(BigDecimal.ZERO, BigDecimal::add);
var paymentSummary = new PaymentSummary(
    counts.values().stream().mapToLong(Long::longValue).sum(), completed,
    AdminStatisticsRules.money(completedAmount, query.currencyScale()),
    AdminStatisticsRules.percentage(completed, completed + failed + refunded),
    failed, counts.get(PaymentStatus.PROCESSING));
```

Create payment/customer day rows for every date from query.from through query.to
in ascending order, looking up that date's grouped rows and substituting zero.
For providers, calculate percentage(completedAttempts, completedAttempts +
failedAttempts); preserve repository ordering. Customer totals come from
totalCustomers(generatedAt); registrations are the sum of their date buckets.
Workload uses generatedAt only. Mark public read operations `@Transactional(readOnly = true)`.

- [ ] **4. Implement payment pagination with shared count/list predicates.** Add optional status to one server-owned WHERE fragment used by both queries. Bind all values; only append the fixed `AND p.status = ?` clause when a parsed status is present. Compute offset as `(long) page * size`.

```sql
SELECT COUNT(*) FROM payments p
WHERE p.created_at >= ? AND p.created_at < ? AND p.currency = ?;

SELECT p.id, p.created_at, p.amount, p.currency, p.status
FROM payments p
WHERE p.created_at >= ? AND p.created_at < ? AND p.currency = ?
ORDER BY p.created_at DESC, p.id DESC
OFFSET ? ROWS FETCH NEXT ? ROWS ONLY;
```

The SQL above is the no-status form; insert the same fixed status clause before
ORDER BY in the list and at the end of the count query. Use existing UUID-byte
mapping and timestamp extraction from Task 2. Map amount to a decimal string
using query.currencyScale. Total pages is `total / size + (total % size == 0 ? 0 : 1)`.
An out-of-range page returns empty items and truthful totalElements/totalPages;
it does not silently change the requested page.

Add IT cases in a historical cohort verified empty before fixture insertion,
with 41 matching payments with the same created_at, size 20, and
three stable pages; no overlapping IDs; matching count/status predicates; a
different currency excluded; and page 999 empty with total 41. Use generated
UUIDs and compare the ordering to the database's RAW ID order, not insertion order.

- [ ] **5. Add the three controller methods under its existing ADMIN guard.** Keep request params as strings so rules produce consistent errors for malformed values; required business inputs use `required = false` and are validated by the service, preserving the existing envelope handler.

```java
@GetMapping("/statistics/options")
public ApiResponse<AdminStatisticsOptionsResponse> statisticsOptions() {
  return envelope(statistics.options());
}

@GetMapping("/statistics")
public ApiResponse<AdminStatisticsResponse> statistics(
    @RequestParam(required = false) String from,
    @RequestParam(required = false) String to,
    @RequestParam(required = false) String currency) {
  return envelope(statistics.summary(from, to, currency));
}

@GetMapping("/statistics/payments")
public ApiResponse<AdminStatisticsPaymentPageResponse> statisticsPayments(
    @RequestParam(required = false) String from,
    @RequestParam(required = false) String to,
    @RequestParam(required = false) String currency,
    @RequestParam(required = false) String status,
    @RequestParam(required = false) String page,
    @RequestParam(required = false) String size) {
  return envelope(statistics.payments(from, to, currency, status, page, size));
}
```

Use constructor injection alongside the existing ReportQueryService. The existing
TicketApiExceptionHandler already handles BusinessException for this controller;
do not rewrite shared authentication/advice. Confirm missing inputs, malformed
page/size, unsupported status, and correlation IDs through MockMvc.

- [ ] **6. Run the targeted portable tests and the Oracle IT.** Expected: contracts, access restrictions, rates, and pagination pass. Record portable and Oracle results separately. This is the backend API checkpoint.

### Task 5: Add browser contracts, request wrappers, and pure helpers

**Files:** Create `services/admin-statistics-contracts.ts`,
`services/admin-statistics.ts`, `tests/admin-statistics.test.cjs`, and
`tests/admin-statistics-api.test.cjs` under `frontend/fluxpay-ui`; modify
`src/ts/services/flux-api.ts`.

**Interfaces:** Mirror every Java response field from the contract dictionary
as TypeScript interfaces named StatisticsOptions, StatisticsSummary, and
StatisticsPaymentPage. Use strings for dates/UUIDs/amounts, numbers for counts,
`number | null` for rates, and `string | null` for defaultCurrency. Define these
additional types in `admin-statistics-contracts.ts`:

```typescript
export type PaymentStatus = 'DRAFT' | 'QUOTED' | 'UNDER_REVIEW' | 'PROCESSING' |
  'COMPLETED' | 'FAILED' | 'REFUNDED' | 'REJECTED' | 'CANCELLED';
export interface StatisticsFilters { from: string; to: string; currency: string; }
export interface StatisticsRouteState extends StatisticsFilters {
  showPayments: boolean; status?: PaymentStatus; day?: string; page: number;
}
export interface StatisticsPaymentQuery extends StatisticsFilters {
  status?: PaymentStatus; page: number; size: number;
}
export interface ResolvedStatisticsRoute { state: StatisticsRouteState; notice: string; }
```

Expose these pure helpers from `admin-statistics.ts`:

```typescript
export function presetRange(today: string, days: 1 | 7 | 30 | 90): {from: string; to: string};
export function resolveRouteState(params: Record<string, unknown>, options: StatisticsOptions): ResolvedStatisticsRoute;
export function toRouteParams(state: StatisticsRouteState): Record<string, string>;
export function paymentQuery(state: StatisticsRouteState): StatisticsPaymentQuery;
export function statisticsSearch(query: StatisticsFilters | StatisticsPaymentQuery): string;
export function formatReportMoney(amount: string, currency: string, scale: number): string;
export function reportChartPath(values: number[], width?: number, height?: number): string;
```

- [ ] **1. Write failing pure-helper tests using the repository's VM loader.** Inject host Date/Intl/URLSearchParams so timezone and date behavior are deliberate.

```javascript
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {load} = require('./helpers/load-typescript.cjs');
const helpers = load('ts/services/admin-statistics.ts', {}, {Date, Intl, URLSearchParams});

test('30-day preset is calendar arithmetic based on server today', () => {
  const range = helpers.presetRange('2026-09-25', 30);
  assert.equal(range.from, '2026-08-27');
  assert.equal(range.to, '2026-09-25');
});
test('large money values retain exact cents', () => {
  assert.equal(helpers.formatReportMoney('900719925474099.12', 'INR', 2),
    'INR 900,719,925,474,099.12');
});
test('day drilldown narrows only the list query', () => {
  const state = {from:'2026-09-01', to:'2026-09-25', currency:'INR',
    showPayments:true, status:'FAILED', day:'2026-09-18', page:2};
  const query = helpers.paymentQuery(state);
  assert.equal(query.from, '2026-09-18');
  assert.equal(query.to, '2026-09-18');
  assert.equal(query.page, 2);
  assert.equal(state.from, '2026-09-01');
});
```

Also cover leap days, ranges crossing a year, invalid dates, unknown status,
removed bookmarked currency, no currencies, range >366, day outside the parent
range, a negative/noninteger page, URL escaping, empty/single-point charts, and
zero values. Confirm no monetary helper converts the amount to Number.

- [ ] **2. Run only the new tests from the frontend directory.**

```powershell
node --test tests/admin-statistics.test.cjs tests/admin-statistics-api.test.cjs
```

- [ ] **3. Implement calendar, state, and money helpers.** Calendar arithmetic uses ISO dates represented as UTC midnight solely as a date arithmetic mechanism; server options.today already represents the reporting timezone. Never derive report today from the browser's local day.

```typescript
export function presetRange(today: string, days: 1 | 7 | 30 | 90) {
  const end = new Date(today + 'T00:00:00.000Z');
  if (Number.isNaN(end.getTime()) || end.toISOString().slice(0, 10) !== today)
    throw new Error('Use a valid calendar date.');
  const start = new Date(end.getTime());
  start.setUTCDate(start.getUTCDate() - days + 1);
  return {from: start.toISOString().slice(0, 10), to: today};
}

export function formatReportMoney(amount: string, currency: string, scale: number): string {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(amount);
  if (!match || !Number.isInteger(scale) || scale < 0 || scale > 4)
    throw new Error('Invalid report amount.');
  const fraction = match[2] || '';
  if (fraction.length > scale && /[1-9]/.test(fraction.slice(scale)))
    throw new Error('Report amount does not match its currency scale.');
  const whole = match[1].replace(/^0+(?=\d)/, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const decimals = scale ? '.' + fraction.padEnd(scale, '0').slice(0, scale) : '';
  return currency + ' ' + whole + decimals;
}
```

`resolveRouteState` defaults missing dates together to the 30-day preset; one
missing date is a validation error. Normalize currency/status to uppercase.
A removed bookmarked currency falls back to the options default with a visible
notice, clears drill-down filters, and resets page to zero. An empty configured
list is handled by the page before resolving a route. Reject invalid status/page,
future/oversized/reversed dates, and a selected day outside the parent range.
Require page to fit the backend int range. `showPayments` is serialized as `1`;
omit unused drill-down fields when the list is closed.

`paymentQuery` returns size 20 and uses day as both dates when present.
`statisticsSearch` uses URLSearchParams and includes only defined fields.
`reportChartPath` can use the existing dashboard chartPath geometry pattern:
8px padding, max(1, maximum), a finite denominator for a one-point series, and
an empty string for an empty series. Only chart coordinates may convert the
server's decimal amount to a Number; labels retain the original string.

- [ ] **4. Add typed GET wrappers to the existing fluxApi object.** Import the response/filter types and statisticsSearch; leave the shared request/token/expiry logic unchanged.

```typescript
adminStatisticsOptions: () =>
  request<StatisticsOptions>('/api/admin/reports/statistics/options'),
adminStatistics: (query: StatisticsFilters) =>
  request<StatisticsSummary>('/api/admin/reports/statistics?' + statisticsSearch(query)),
adminStatisticsPayments: (query: StatisticsPaymentQuery) =>
  request<StatisticsPaymentPage>('/api/admin/reports/statistics/payments?' + statisticsSearch(query)),
```

Test the emitted URL/method/envelope using the existing API test harness pattern:
fake fetch returns ApiResponse data, sessionStorage supplies an ADMIN token,
and each new wrapper yields the typed data. Assert GET, encoded parameters,
Authorization retained, no request body, and no idempotency/action behavior.
Load real shared request code through the TypeScript helper, with its existing
runtime dependencies stubbed explicitly rather than silently ignored.

```javascript
test('report lists preserve auth and encode GET query parameters', async () => {
  const calls = [];
  const events = [];
  const data = {items: [], totalElements: 0};
  const runtime = load('ts/services/flux-api.ts', {'./admin-statistics': helpers}, {
    window: {FLUXPAY_API_URL: '', dispatchEvent: event => events.push(event)},
    sessionStorage: {getItem: () => 'test-admin-token', removeItem() {}},
    fetch: async (url, init) => {
      calls.push({url, init});
      return {ok: true, status: 200, json: async () => ({data})};
    },
    URLSearchParams, Date, Intl
  });
  const result = await runtime.fluxApi.adminStatisticsPayments({
    from:'2026-09-01', to:'2026-09-25', currency:'INR', status:'FAILED', page:0, size:20
  });
  const url = new URL(calls[0].url, 'http://localhost');
  assert.equal(url.pathname, '/api/admin/reports/statistics/payments');
  assert.equal(url.searchParams.get('status'), 'FAILED');
  assert.equal(url.searchParams.get('currency'), 'INR');
  assert.equal(calls[0].init.method, 'GET');
  assert.equal(calls[0].init.headers.Authorization, 'Bearer test-admin-token');
  assert.equal(calls[0].init.headers['Idempotency-Key'], undefined);
  assert.equal(calls[0].init.body, undefined);
  assert.equal(result, data);
  assert.equal(events.length, 0);
});
```

- [ ] **5. Run helper/API tests and typecheck.**

```powershell
node --test tests/admin-statistics.test.cjs tests/admin-statistics-api.test.cjs
npm.cmd run typecheck
```

Expected: types match the Java JSON names exactly; helpers pass without a running
backend. This is the frontend contract checkpoint.

### Task 6: Build the Statistics page and safe loading lifecycle

**Files:** Create `src/ts/viewModels/admin-statistics.ts`,
`src/ts/views/admin-statistics.html`, `src/css/admin-statistics.css`,
`tests/admin-statistics-page.test.cjs`, and `tests/admin-statistics-fixture.cjs`;
modify `src/ts/appController.ts`, `src/index.html`, and `tests/admin-navigation.test.cjs`.
All paths in this task are under `frontend/fluxpay-ui`.

**Interfaces:** Export the view model with the existing `export =` convention.
It consumes fluxApi's three Task 5 methods, session/navigate, and the pure helpers.

```typescript
class AdminStatisticsViewModel {
  // Public observables: session, options, state, snapshot, pageData,
  // from, to, currency, optionsBusy, busy, listBusy,
  // optionsError, error, listError, notice.
  // options/state/snapshot/pageData are undefined until their respective loads.
  ready: Promise<void>;
  constructor(params: {params?: Record<string, unknown>});
  parametersChanged(params: Record<string, unknown>): void;
  selectPreset(days: 1 | 7 | 30 | 90): void;
  applyFilters(): void;
  retryOptions(): Promise<void>;
  refresh(): Promise<void>;
  disconnected(): void;
}
```

`ready` tracks initial/route loading so tests can await it deterministically; it
is not displayed. Keep options, dashboard, and list loading/error state distinct.
The list behavior is completed in Task 7; its absence must not block this task's
summary page tests.

- [ ] **1. Create the page fixture and write failing lifecycle tests.** The fixture module exports these functions:

```javascript
function deferred() {
  let resolve, reject;
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
  return {promise, resolve, reject};
}
// summary(query) returns a complete StatisticsSummary with query-matching meta,
// all nine zero status counts, zero-filled days, empty providers, and zero workload.
// makePage({summary: optional async override, role: optional role}) returns:
// {vm, api, session, calls, settle: async () => void}.
module.exports = {deferred, summary, makePage};
```

Implement summary(query) using the response dictionary; generatedAt is fixed to
`2026-09-25T12:00:00Z`, meta reportingZone is Asia/Kolkata, and all amount strings
are `0.00`. Iterate from query.from to query.to with UTC calendar arithmetic to
fill the date arrays. Use a Knockout-observable user and computed isAdmin in
makePage. The default options contain INR/USD at scale 2 and today 2026-09-25.
Load the VM with the existing loader and explicit dependencies:

```javascript
const Page = load('ts/viewModels/admin-statistics.ts', {
  knockout: ko,
  '../services/flux-api': {fluxApi: api},
  '../services/session': {session, navigate},
  '../services/admin-statistics': helpers
}, {Date, Intl, URLSearchParams});
```

The fixture's navigate records `{path, params}` and invokes vm.parametersChanged
for admin-statistics, matching the installed adapter's actual parameter-only
callback. For other paths it only records navigation. Settle uses setImmediate,
not timers with guessed network delays. All helpers named above are implemented
in the fixture file before importing it in tests.

Test options-before-summary ordering, one initial summary request, no report
request for a regular/anonymous user, options failure and retry, an empty
currency configuration, a successful empty summary, and explicit summary error.
Add this race test:

```javascript
test('a late summary cannot overwrite a newer currency selection', async () => {
  const pending = deferred();
  const f = makePage({summary: () => pending.promise});
  await f.settle();
  const oldReady = f.vm.ready;
  f.api.adminStatistics = async query => summary(query);
  f.vm.parametersChanged({from:'2026-09-01', to:'2026-09-25', currency:'USD'});
  await f.vm.ready;
  pending.resolve(summary({from:'2026-08-27', to:'2026-09-25', currency:'INR'}));
  await oldReady;
  assert.equal(f.vm.snapshot().meta.currency, 'USD');
});
```

Resolve a pending request after logout and after disconnected; neither may
populate protected observables or reset a newer request's busy flag. Test an
ADMIN identity change as well as loss of the ADMIN role.

- [ ] **2. Run the page test to establish failure.**

```powershell
node --test tests/admin-statistics-page.test.cjs
```

- [ ] **3. Implement initialization, parameter updates, and response ownership.** Restore the existing session if needed, verify ADMIN, fetch options, resolve the route, then load the summary. On an empty options list, render the setup state and skip currency-dependent requests. After startup, options are reused until an explicit options retry/re-entry.

The installed `ojmodulerouter-adapter.js` calls `parametersChanged(args.state.params)`
when staying on the same module. Therefore the constructor reads params.params,
but parametersChanged receives the raw parameter map. Do not read params.params
inside that callback or ignore updates to the same route.

Use monotonically increasing options/summary/list generations plus a disposed
flag and captured account identity. The summary's core ownership pattern is:

```typescript
const generation = ++this.summaryGeneration;
const accountId = this.session.user()?.id;
const current = () => !this.disposed && generation === this.summaryGeneration &&
  this.session.isAdmin() && this.session.user()?.id === accountId;
this.busy(true);
this.error('');
try {
  const response = await fluxApi.adminStatistics(filters);
  if (!current()) return;
  this.snapshot(response);
} catch (error: unknown) {
  if (current()) this.error(error instanceof Error ? error.message : 'Statistics are unavailable.');
} finally {
  if (current()) this.busy(false);
}
```

Declare summaryGeneration/optionsGeneration/listGeneration as private numbers
starting at zero and disposed as false. Use the corresponding counter for each
request type. Subscribe to session identity/role changes to invalidate requests
and clear protected data immediately. Reinitialize for a new authenticated ADMIN
identity; do not trigger duplicate initialization for the first session restore.
Dispose the subscription and any pureComputed values in disconnected.

Before loading for new dates/currency, clear the previous summary and list.
For a same-filter manual refresh, an existing summary may stay visible with an
explicit Refreshing/Refresh failed indication. Derive Last updated only from
snapshot.meta.generatedAt. Failed refresh must not change that timestamp.
Filter controls navigate using toRouteParams; parameter handling performs the
load, preventing a second direct fetch for the same change.

- [ ] **4. Render the approved sections with accessible controls.** Use the existing admin sign-in/access gates and dense-table/pill conventions. Bind server strings with Knockout text, never html. Add the five payment cards; separate count and amount charts; all nine status rows; provider attempt table; customer total/new registration trend; and the three workload areas with their aging counts.

Use this structural pattern and complete each section with the exact fields in
the contract dictionary:

```html
<section class="admin-page statistics-page">
  <!-- ko if: session.isAdmin() -->
  <header class="admin-page-heading"><div><span class="eyebrow">STATISTICS</span>
    <h1>Activity and operational health</h1></div>
    <button class="pill soft" data-bind="click:refresh,disable:busy">Refresh</button>
  </header>
  <p role="status" aria-live="polite" class="sr-only"
     data-bind="text:busy()?'Loading statistics.':'Statistics ready.'"></p>
  <!-- ko if: error --><p role="alert" data-bind="text:error"></p><!-- /ko -->
  <!-- ko if: snapshot -->
  <p>Payments created in the selected period, shown with their current outcomes.</p>
  <div class="statistics-grid" data-bind="with:snapshot">
    <section aria-label="Payment summary">
      <span>Payments created</span>
      <strong data-bind="text:paymentSummary.paymentCount"></strong>
    </section>
  </div>
  <!-- /ko -->
  <!-- /ko -->
</section>
```

The completed template also includes options-loading/setup errors and a retry
action, visible refresh status, labeled native date/currency/preset controls,
reporting zone, last successful update, and regular-user/anonymous gates.
Use "No final outcomes" for null payment rates, "No terminal attempts" for null
provider rates, and a factual empty-provider message when there are no rows.
Keep total customers labeled All time, registrations Selected dates / all
currencies, and workload Current / all dates and currencies.

SVG charts use viewBox sizing and accessible titles/descriptions. Add a native
details/summary containing daily data tables; their date buttons provide the
keyboard-accessible chart drill-down controls completed in Task 7. Avoid dual
axes and color-only distinctions. Do not make provider attempt totals look like
payment drill-downs. Explain the payout-rate formula near its display.

- [ ] **5. Add isolated styling and navigation.** Add a stylesheet link after admin-console.css in index.html. Scope every new selector under statistics-page or a statistics-prefixed class; reuse the theme's existing colors and spacing. For layout:

```css
.statistics-page .statistics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
  gap: 1rem;
}
.statistics-page .statistics-toolbar { display: flex; flex-wrap: wrap; gap: .75rem; }
.statistics-page .statistics-chart { width: 100%; min-width: 0; }
.statistics-page .statistics-table-scroll { max-width: 100%; overflow-x: auto; }
```

Add `{path:'admin-statistics',label:'Statistics',icon:'fa-chart-column'}` beside
admin in appController's Overview group. Existing route derivation automatically
registers its module. Update the grouped-route expectation in admin-navigation.test.cjs
to `['Overview',['admin','admin-statistics']]`; keep the other groups intact.
Add a test that an ADMIN can deep-link to the new route with the admin shell,
while regular/anonymous users cannot trigger report loading. Do not add customer
navigation entries or change the admin login landing route.

- [ ] **6. Run page, helper, navigation tests and typecheck.**

```powershell
node --test tests/admin-statistics.test.cjs tests/admin-statistics-api.test.cjs tests/admin-statistics-page.test.cjs tests/admin-navigation.test.cjs
npm.cmd run typecheck
```

Review the page as a working summary dashboard before adding record drill-downs.

### Task 7: Connect payment drill-downs and browser navigation

**Files:** Extend `viewModels/admin-statistics.ts`, `views/admin-statistics.html`,
`tests/admin-statistics-page.test.cjs`, and `tests/admin-statistics-fixture.cjs`.
Use Task 4's endpoint and Task 5's route/payment helpers. The existing Payment
Operations view should require no product changes.

**Interfaces:** Add these public view-model methods:

```typescript
openPayments(status?: PaymentStatus, day?: string): void;
closePayments(): void;
goToPage(page: number): void;
retryPayments(): Promise<void>;
openOperations(row: StatisticsPaymentPage['items'][number]): void;
```

- [ ] **1. Write failing tests for filter mapping and navigation.** Add the fake
adminStatisticsPayments method to the fixture; return a typed empty page by
default and record every query. Use exact rows in the operations-link test:

```javascript
test('failed payment drilldown preserves source currency and opens operations', async () => {
  const f = makePage();
  await f.vm.ready;
  f.vm.openPayments('FAILED');
  await f.vm.ready;
  const query = f.calls.filter(call => call.kind === 'payments').at(-1).query;
  assert.equal(query.status, 'FAILED');
  assert.equal(query.currency, 'INR');
  assert.equal(query.page, 0);
  assert.equal(query.size, 20);
  f.vm.openOperations({paymentId:'00000000-0000-0000-0000-000000000001',
    createdAt:'2026-09-24T12:00:00Z', sourceAmount:'25.00',
    sourceCurrency:'INR', status:'FAILED'});
  const navigation = f.calls.filter(call => call.kind === 'navigate').at(-1);
  assert.equal(navigation.path, 'admin-payment-operations');
  assert.equal(navigation.params.paymentId, '00000000-0000-0000-0000-000000000001');
});
```

Record navigation entries with kind='navigate' and list API entries with
kind='payments' in the fixture. Add mapping checks for all payments, each
status, completed amount -> COMPLETED, count-day -> that day's all statuses,
and amount-day -> that day's COMPLETED payments. Success-rate/provider cells
must have no ambiguous payment-list action.

Also test page changes, new filter resets to page zero, closing the list,
restoring all route fields through parametersChanged, stale list responses,
list failure preserving the summary, logout clearing pageData, and page 999
showing no matching rows without changing server totals.

- [ ] **2. Run the page tests to confirm missing drill-down behavior.**

```powershell
node --test tests/admin-statistics-page.test.cjs
```

- [ ] **3. Implement list route transitions and independent loading.** Route
changes carry the dashboard's dates/currency plus showPayments/status/day/page.
Call paymentQuery only when the list is open; reset its generation/data on a
new list filter. Do not reload summary for a page/status/day-only transition.
The summary query key is only from/to/currency; the list key also includes its
selected status/day/page. Refresh explicitly reloads summary and the open list.

```typescript
openPayments = (status?: PaymentStatus, day?: string): void => {
  const state = this.state();
  if (!state) return;
  navigate('admin-statistics', toRouteParams({
    ...state, showPayments: true, status, day, page: 0
  }));
};

openOperations = (row: StatisticsPaymentPage['items'][number]): void => {
  navigate('admin-payment-operations', {paymentId: row.paymentId});
};
```

Use a separate list generation/current-account check following Task 6's request
ownership pattern. Store list errors in listError only. Retry reuses the same
list state. A page beyond the last page is a successful empty response with a
clear return-to-first-page action. Closing the list invalidates pending list
responses. On invalid route filters, present the validation message and send
neither summary nor list queries with invalid values.

- [ ] **4. Complete the inline payment table and accessible entry points.**
Make payment counts/status values actual buttons. The amount card uses
openPayments('COMPLETED'); the daily count/amount table buttons use the day
argument. Present paymentId, createdAt in the reporting timezone, exact formatted
source amount, sourceCurrency, status, and this operations button:

```html
<button type="button" class="text-button"
        data-bind="click:$root.openOperations">Open operations</button>
```

Use the view model root for repeated-row actions so Knockout foreach/with
contexts cannot accidentally call methods on a row. Previous/Next buttons use
page/totalPages and disable correctly for loading/first/last/empty states.
Announce list results accessibly, show totalElements, and provide Close and Retry.
Do not prefetch operations details for each row or add money-moving actions.

- [ ] **5. Verify same-module and browser Back behavior.** The fixture must
exercise raw parameter maps through parametersChanged, not just construct a new
VM for every click. Then browser-smoke: choose USD/custom dates, open a status/day
and page, open an operation, and press Back. Dates, currency, list filters, and
page must restore from the router URL. Use CoreRouter navigation rather than
manual URL mutation that bypasses the app's route state.

- [ ] **6. Rerun the focused frontend tests/typecheck.** Expected: every list
query matches its visible selection; stale/failed list requests cannot corrupt
the summary; operations navigation passes the exact payment ID. This is the
interactive feature checkpoint.

### Task 8: Document and verify the complete feature

**Files:** Modify `docs/api-catalog.md`; create `docs/admin-statistics.md`.
Review all changed product/tests from Tasks 1-7; no unrelated cleanup.

**Interfaces:** The three reporting endpoints, `admin-statistics` route, and
existing `admin-payment-operations` paymentId route parameter are the handoff.

- [ ] **1. Add usage and API documentation beside the implemented endpoints.**
Document dates as inclusive calendar dates in Asia/Kolkata, payment-creation
cohorts with current outcomes, source-currency selection, customer/workload
filter independence, null rate meanings, paging, exact errors, and the unchanged
legacy provider-summary semantics. Include this request example and the exact
field names from the response dictionary:

```http
GET /api/admin/reports/statistics?from=2026-09-01&to=2026-09-25&currency=INR
Authorization: Bearer <admin-token>

GET /api/admin/reports/statistics/payments?from=2026-09-18&to=2026-09-18&currency=INR&status=FAILED&page=0&size=20
Authorization: Bearer <admin-token>
```

Do not store a real token in documentation. In docs/admin-statistics.md explain
how to choose dates/currency, interpret payment versus attempt rates, open
payments, and return with browser Back. Include a small worked example: one
failed-then-completed payment of INR 25 contributes one completed payment,
INR 25 completed amount, and two attempts with 50% provider success.

- [ ] **2. Run final portable checks once after all changes.** From repo root:

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:check test
git diff --check
```

From frontend/fluxpay-ui:

```powershell
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
```

If formatting fails, format only changed Java files with the existing project
formatter and rerun the affected checks. Do not make unrelated formatting edits.
For failures, determine whether they belong to this change; preserve unrelated
user work and report unrelated failures with their exact output.

- [ ] **3. Execute the targeted Oracle acceptance in both storage timezones.**
Use the existing dedicated integration profile, with ORACLE_TEST_USERNAME=FLUXPAY_TEST,
ORACLE_TEST_JDBC_URL, ORACLE_TEST_PASSWORD, ORACLE_TESTS_ACTIVE=true, and
KAFKA_BOOTSTRAP_SERVERS configured. The Maven profile currently requires Kafka
configuration even though this reporting test does not send messages.

```powershell
.\mvnw.cmd -f backend/pom.xml -Pintegration '-Dtest=AdminStatisticsRulesTest,AdminStatisticsServiceTest,AdminReportControllerMvcTest,ReportQueryServiceTest' -Dit.test=AdminStatisticsRepositoryIT -Duser.timezone=UTC verify
.\mvnw.cmd -f backend/pom.xml -Pintegration '-Dtest=AdminStatisticsRulesTest,AdminStatisticsServiceTest,AdminReportControllerMvcTest,ReportQueryServiceTest' -Dit.test=AdminStatisticsRepositoryIT -Duser.timezone=Asia/Kolkata verify
```

Check the Failsafe report actually executed the new tests; a skipped class is not
a pass. The two runs specifically exercise the timestamp concern in Review Focus,
not a reason to rerun all infrastructure tests repeatedly. Do not provision/reset
databases or alter the user's seed data merely to make this check available.

- [ ] **4. Run browser acceptance against the available application.** Use a
real ADMIN session and existing data. Check the page at desktop and a narrow
viewport, keyboard-only controls, all three chart data alternatives, date/currency
changes, empty periods, payment/status/day navigation, pagination, exact operations
IDs, browser Back, refresh, and logout while a request is pending. Verify the
current Overview and customer navigation still work. Exercise failed responses
with local test interception or the existing test harness, not database damage.
If the app/infrastructure is unavailable, record that limitation and the exact
remaining check; do not claim a real-data UI pass from static HTML tests.

- [ ] **5. Reconcile a controlled fixture against every panel and its list.**
Use the transactional IT fixture, with completed/failed/refunded/processing/draft
payments, mixed currencies, a later retry, archived providers, USER/ADMIN/SYSTEM
accounts, old/recent/resubmitted KYC, compliance risk levels, and support states.
Verify status counts sum to paymentCount, daily counts sum to paymentCount,
daily completed amounts sum to completedAmount at server precision, and each
drill-down's total uses the same cohort/status predicates. Keep provider attempt
totals distinct. Normal concurrent transitions may change later request results;
the controlled fixture check has no such writers.

- [ ] **6. Deliver the implementation with evidence and remaining limits.**
Summarize the page/API changes and the legacy SQL correction. Report portable,
Oracle, and browser checks separately with actual results. Leave committing,
pushing, and deployment to explicit user instructions. All task checkboxes are
completed only when their work and required verification have actually occurred.

## Coverage map

| Approved requirement | Implementation and verification |
| --- | --- |
| Dedicated admin page, compact styling, existing role/auth | Tasks 4, 6, 8 |
| Real totals beyond capped existing queues | Task 2 aggregates and >100-row IT |
| Source-currency selector and configured precision | Tasks 1, 2, 5, 6 |
| IST dates, presets/custom range, partial day, zero-filled charts | Tasks 1, 2, 4, 5, 8 |
| Payment counts, amount, status breakdown, payout success formula | Tasks 2, 4, 6 |
| Retry-safe payment totals and provider attempt performance | Tasks 2, 3, 4 |
| Archived providers and non-UUID attempt IDs | Task 3 real-SQL tests |
| Legacy provider-summary SQL correction without API drift | Tasks 3, 4, 8 |
| Customer totals/registrations, ADMIN/SYSTEM exclusions | Tasks 2, 4, 6 |
| KYC/compliance/support current and aged workload | Tasks 2, 4, 6 |
| Three typed, validated, read-only report endpoints | Tasks 1, 4, 5 |
| Precise money transport and display | Tasks 1, 2, 4, 5 |
| Payment/status/day list, pagination, operations link, Back | Tasks 4, 5, 7 |
| Manual refresh, timestamp, loading/errors/session/races | Tasks 6, 7 |
| Keyboard access, text equivalents, responsive layout | Tasks 6, 8 |
| Oracle timestamp/UUID/NUMBER behavior rather than mock-only confidence | Tasks 2, 3, 4, 8 |
| Usage/API documentation and scope exclusions | Task 8 and Global Constraints |

## Handoff

This document is the implementation plan, not a record of completed product work.
All task checkboxes intentionally remain open. No new schema migration is assumed.

Recommended execution approach: native execution in one session, using
superpowers:executing-plans after the user requests implementation and selects
the execution approach. The tasks share a small set of API/DTO/state contracts;
keeping that context together reduces handoff overhead. Subagent-driven execution
is also available if the user prefers independent task reviews. Do not start
implementation, spawn workers, commit, or publish while only delivering this plan.
