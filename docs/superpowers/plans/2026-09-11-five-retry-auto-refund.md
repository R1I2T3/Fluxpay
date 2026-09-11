# Five-Retry Automatic Refund Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically retry a confirmed failed payout five times at fixed ten-minute intervals using Kafka events and Oracle scheduling, then refund the sender through the mock in-memory ledger when the retry budget is exhausted.

**Architecture:** Payout outcome changes and canonical events are committed to Oracle through a transactional outbox. A dedicated Kafka consumer stores idempotent recovery decisions and durable Oracle jobs; leased workers execute due retries outside database transactions, reconcile ambiguous provider outcomes, and run one idempotent refund job after exhaustion. All automatic and manual recovery actions serialize on one payment recovery row.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Kafka, Spring Data JPA/JDBC, Oracle, Flyway, Jackson, Micrometer/Actuator, JUnit 5, Mockito, AssertJ, H2 for focused transaction tests.

**Spec:** `docs/superpowers/specs/2026-09-11-five-retry-auto-refund-design.md`

## Global Constraints

- The initial submission is attempt 1; retries 1 through 5 are attempts 2 through 6.
- A retry is due exactly ten minutes after the preceding confirmed transient failure; no jitter is applied.
- Automatic retries use the current route and never debit the sender wallet.
- A confirmed permanent failure skips retries and schedules an immediate refund.
- An ambiguous provider outcome must be reconciled to confirmed success or confirmed failure before another payout attempt or refund.
- Confirmed success cancels every pending retry or refund job.
- Manual refund and route switch are allowed only while no provider call is active; each cancels a pending automatic job under the same payment lock.
- Manual route switch consumes the same five-retry budget and never resets it.
- Manual retry is disabled while automatic recovery is enabled.
- The first release uses `MockLedgerWriter`; wallet balances and refund idempotency do not survive a process restart, so production safety is not claimed.
- Recovery orchestration depends on `LedgerWriter`, never directly on `MockLedgerWriter`.
- Recovery events use consumer group `fluxpay-payout-recovery`; the existing `fluxpay-timeline` group remains independent.
- Kafka/Oracle/outbox processing failures do not consume business retries.
- The feature defaults to disabled and is enabled with `PAYOUT_RECOVERY_ENABLED=true` only in mock/integration environments.
- Existing Flyway migrations remain immutable. The first new migration is `V505__payout_recovery.sql`.

## File Structure

### Configuration and provider contracts

- Create `backend/src/main/java/com/fluxpay/config/PayoutRecoveryProperties.java`: validated recovery settings and fixed defaults.
- Create `backend/src/main/java/com/fluxpay/config/PayoutRecoveryConfiguration.java`: scheduling and recovery-specific Kafka container configuration.
- Modify `backend/src/main/java/com/fluxpay/FluxPayApplication.java`: scan configuration properties.
- Modify `backend/src/main/resources/application.yml`: expose the approved environment-backed settings.
- Create `backend/src/main/java/com/fluxpay/dto/ProviderOutcome.java`: `SUCCEEDED`, `CONFIRMED_FAILED`, `AMBIGUOUS`.
- Create `backend/src/main/java/com/fluxpay/dto/FailureCategory.java`: `TRANSIENT`, `PERMANENT`, `UNKNOWN`.
- Modify `backend/src/main/java/com/fluxpay/dto/PayoutCmd.java`: carry attempt ID and provider idempotency key.
- Modify `backend/src/main/java/com/fluxpay/dto/PayoutResult.java`: represent success, confirmed failure, and ambiguity explicitly.
- Modify `backend/src/main/java/com/fluxpay/service/PayoutProvider.java`: add status lookup by idempotency key.
- Modify the three files under `backend/src/main/java/com/fluxpay/adapter/`: honor stable keys and expose lookup results.

### Recovery persistence

- Create `backend/src/main/resources/db/migration/V505__payout_recovery.sql`: attempt metadata, recovery state, jobs, inbox, and outbox.
- Create recovery enums/entities under `backend/src/main/java/com/fluxpay/recovery/model/`.
- Create Spring Data repositories under `backend/src/main/java/com/fluxpay/recovery/repository/`.
- Create `RecoveryJobClaimRepository.java`: Oracle `SKIP LOCKED` job leasing.
- Modify `backend/src/main/java/com/fluxpay/beans/PayoutAttempt.java`: persist provider key, outcome certainty, and failure category.

### Event outbox

- Create `backend/src/main/java/com/fluxpay/outbox/OutboxEventPublisher.java`: application-facing `EventPublisher` implementation that inserts an outbox row.
- Create `backend/src/main/java/com/fluxpay/outbox/KafkaEventSender.java`: low-level broker send only.
- Create `backend/src/main/java/com/fluxpay/outbox/OutboxLeaseService.java`: transactional row claim/finalization.
- Create `backend/src/main/java/com/fluxpay/outbox/PaymentEventOutboxRelay.java`: scheduled relay orchestration.
- Remove `backend/src/main/java/com/fluxpay/config/KafkaEventPublisher.java` after its tests are replaced.

### Payout and recovery services

- Create `backend/src/main/java/com/fluxpay/service/PayoutReservation.java`: immutable committed attempt reservation.
- Create `backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java`: short reserve/finalize transactions.
- Modify `backend/src/main/java/com/fluxpay/service/PayoutExecutionService.java`: provider I/O outside Oracle transactions.
- Create services under `backend/src/main/java/com/fluxpay/recovery/service/`: policy, event ingestion, coordinator, worker, reconciliation, and metrics.
- Create Kafka components under `backend/src/main/java/com/fluxpay/recovery/kafka/`: failure consumer and shared DLT publisher.
- Modify `backend/src/main/java/com/fluxpay/config/PaymentEventConsumer.java`: use the shared DLT publisher.
- Modify `backend/src/main/java/com/fluxpay/service/RecoveryService.java`: delegate manual actions to the coordinator.
- Modify `backend/src/main/java/com/fluxpay/controller/PayoutController.java`: disable manual retry and report allowed actions correctly when recovery is enabled.
- Create `backend/src/main/java/com/fluxpay/exception/RecoveryConflictException.java` and map it in `M4ApiExceptionHandler`.

### Verification and operations

- Add focused tests mirroring each production file under `backend/src/test/java/com/fluxpay/`.
- Create `backend/src/test/java/com/fluxpay/recovery/PayoutRecoveryPersistenceIT.java`: Kafka/Oracle acceptance coverage.
- Modify `.env.example`, `scripts/test-all.py`, and `docs/04-member4-backend-demo.md` for controlled enablement.

---

### Task 1: Validated Recovery Configuration

**Files:**
- Create: `backend/src/main/java/com/fluxpay/config/PayoutRecoveryProperties.java`
- Create: `backend/src/main/java/com/fluxpay/config/PayoutRecoveryConfiguration.java`
- Modify: `backend/src/main/java/com/fluxpay/FluxPayApplication.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/fluxpay/config/PayoutRecoveryPropertiesTest.java`

**Interfaces:**
- Produces: `PayoutRecoveryProperties` with `enabled()`, `consumerGroup()`, `maxRetries()`, `retryDelay()`, `workerPollInterval()`, `leaseDuration()`, `reconciliationPollInterval()`, and `automaticRefundEnabled()`.
- Consumes: Spring Boot configuration-property binding and validation.

- [ ] **Step 1: Write configuration binding tests**

```java
class PayoutRecoveryPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(TestConfiguration.class);

  @Test
  void bindsApprovedDefaults() {
    runner.run(context -> {
      PayoutRecoveryProperties p = context.getBean(PayoutRecoveryProperties.class);
      assertThat(p.enabled()).isFalse();
      assertThat(p.maxRetries()).isEqualTo(5);
      assertThat(p.retryDelay()).isEqualTo(Duration.ofMinutes(10));
      assertThat(p.workerPollInterval()).isEqualTo(Duration.ofSeconds(5));
      assertThat(p.leaseDuration()).isEqualTo(Duration.ofSeconds(60));
      assertThat(p.reconciliationPollInterval()).isEqualTo(Duration.ofMinutes(1));
      assertThat(p.automaticRefundEnabled()).isTrue();
    });
  }

  @Test
  void rejectsAValueOtherThanFiveRetries() {
    runner.withPropertyValues("fluxpay.payout-recovery.max-retries=4")
        .run(context -> assertThat(context).hasFailed());
  }

  @EnableConfigurationProperties(PayoutRecoveryProperties.class)
  static class TestConfiguration {}
}
```

- [ ] **Step 2: Run the test and verify it fails because the properties type is absent**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=PayoutRecoveryPropertiesTest test`

Expected: FAIL with a compilation error for `PayoutRecoveryProperties`.

- [ ] **Step 3: Add the validated properties record**

```java
@Validated
@ConfigurationProperties("fluxpay.payout-recovery")
public record PayoutRecoveryProperties(
    @DefaultValue("false") boolean enabled,
    @NotBlank @DefaultValue("fluxpay-payout-recovery") String consumerGroup,
    @Min(5) @Max(5) @DefaultValue("5") int maxRetries,
    @NotNull @DefaultValue("10m") Duration retryDelay,
    @NotNull @DefaultValue("5s") Duration workerPollInterval,
    @NotNull @DefaultValue("60s") Duration leaseDuration,
    @NotNull @DefaultValue("1m") Duration reconciliationPollInterval,
    @DefaultValue("true") boolean automaticRefundEnabled) {
  public PayoutRecoveryProperties {
    if (retryDelay.isNegative() || retryDelay.isZero()) {
      throw new IllegalArgumentException("retryDelay must be positive");
    }
    if (leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    if (enabled && !automaticRefundEnabled) {
      throw new IllegalArgumentException("automatic refund is required when recovery is enabled");
    }
  }
}
```

- [ ] **Step 4: Enable property scanning and scheduling configuration**

```java
// FluxPayApplication.java
@SpringBootApplication
@EnableKafka
@ConfigurationPropertiesScan
public class FluxPayApplication {
  public static void main(String[] args) {
    SpringApplication.run(FluxPayApplication.class, args);
  }
}

// PayoutRecoveryConfiguration.java
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PayoutRecoveryConfiguration {}
```

Add this exact block under `fluxpay` in `application.yml`:

```yaml
  payout-recovery:
    enabled: ${PAYOUT_RECOVERY_ENABLED:false}
    consumer-group: ${PAYOUT_RECOVERY_CONSUMER_GROUP:fluxpay-payout-recovery}
    max-retries: ${PAYOUT_RECOVERY_MAX_RETRIES:5}
    retry-delay: ${PAYOUT_RECOVERY_RETRY_DELAY:10m}
    worker-poll-interval: ${PAYOUT_RECOVERY_POLL_INTERVAL:5s}
    lease-duration: ${PAYOUT_RECOVERY_LEASE_DURATION:60s}
    reconciliation-poll-interval: ${PAYOUT_RECOVERY_RECONCILIATION_INTERVAL:1m}
    automatic-refund-enabled: ${PAYOUT_RECOVERY_AUTO_REFUND:true}
```

The `@NotBlank` constraint on `consumerGroup` rejects empty group IDs at startup.

- [ ] **Step 5: Run the focused and context tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=PayoutRecoveryPropertiesTest,PayoutProfileTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/config/PayoutRecoveryProperties.java backend/src/main/java/com/fluxpay/config/PayoutRecoveryConfiguration.java backend/src/main/java/com/fluxpay/FluxPayApplication.java backend/src/main/resources/application.yml backend/src/test/java/com/fluxpay/config/PayoutRecoveryPropertiesTest.java
git commit -m "feat: add payout recovery configuration"
```

---

### Task 2: Provider Idempotency and Outcome Certainty

**Files:**
- Create: `backend/src/main/java/com/fluxpay/dto/ProviderOutcome.java`
- Create: `backend/src/main/java/com/fluxpay/dto/FailureCategory.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/PayoutCmd.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/PayoutResult.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutProvider.java`
- Modify: `backend/src/main/java/com/fluxpay/adapter/StandardBankAdapter.java`
- Modify: `backend/src/main/java/com/fluxpay/adapter/InstantPayoutAdapter.java`
- Modify: `backend/src/main/java/com/fluxpay/adapter/LocalPartnerAdapter.java`
- Test: `backend/src/test/java/com/fluxpay/service/PayoutProviderTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/PayoutExecutionServiceTest.java`

**Interfaces:**
- Produces: `PayoutProvider.submit(PayoutCmd)` and `PayoutProvider.lookup(String)` returning `PayoutResult`.
- Produces: stable provider key `payout:<attemptId>` carried by `PayoutCmd.providerIdempotencyKey()`.
- Produces: explicit `ProviderOutcome` and `FailureCategory` used by transaction finalization and recovery policy.

- [ ] **Step 1: Rewrite provider contract tests around stable keys**

```java
private PayoutCmd command(UUID attemptId, String route) {
  return new PayoutCmd(
      attemptId,
      "payout:" + attemptId,
      "P-001",
      new BigDecimal("1000.00"),
      "USD",
      "KES",
      route,
      new BigDecimal("5.00"),
      1);
}

@Test
void repeatedProviderKeyReturnsTheSameResult() {
  StandardBankAdapter adapter = new StandardBankAdapter(() -> null);
  PayoutCmd cmd = command(UUID.randomUUID(), "STANDARD_BANK");
  assertThat(adapter.submit(cmd)).isEqualTo(adapter.submit(cmd));
  assertThat(adapter.lookup(cmd.providerIdempotencyKey())).isEqualTo(adapter.submit(cmd));
}

@Test
void configuredFailuresAreConfirmedAndTransient() {
  StandardBankAdapter adapter = new StandardBankAdapter(() -> "STANDARD_BANK:2");
  PayoutResult first = adapter.submit(command(UUID.randomUUID(), "STANDARD_BANK"));
  assertThat(first.outcome()).isEqualTo(ProviderOutcome.CONFIRMED_FAILED);
  assertThat(first.failureCategory()).isEqualTo(FailureCategory.TRANSIENT);
}

@Test
void timeoutSimulationIsAmbiguous() {
  StandardBankAdapter adapter = new StandardBankAdapter(() -> "STANDARD_BANK:AMBIGUOUS");
  PayoutResult result = adapter.submit(command(UUID.randomUUID(), "STANDARD_BANK"));
  assertThat(result.outcome()).isEqualTo(ProviderOutcome.AMBIGUOUS);
  assertThat(result.failureCategory()).isEqualTo(FailureCategory.UNKNOWN);
}
```

- [ ] **Step 2: Run the provider test and verify compilation fails**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=PayoutProviderTest test`

Expected: FAIL because the new command fields, enums, and lookup method do not exist.

- [ ] **Step 3: Add the exact outcome enums and result factories**

```java
public enum ProviderOutcome {
  SUCCEEDED,
  CONFIRMED_FAILED,
  AMBIGUOUS
}

public enum FailureCategory {
  TRANSIENT,
  PERMANENT,
  UNKNOWN
}

public record PayoutResult(
    ProviderOutcome outcome,
    String providerRef,
    String errorCode,
    String errorMessage,
    BigDecimal providerFee,
    FailureCategory failureCategory) {

  public boolean success() {
    return outcome == ProviderOutcome.SUCCEEDED;
  }

  public static PayoutResult succeeded(String ref, BigDecimal fee) {
    return new PayoutResult(ProviderOutcome.SUCCEEDED, ref, null, null, fee, null);
  }

  public static PayoutResult confirmedFailure(
      String code, String message, BigDecimal fee, FailureCategory category) {
    return new PayoutResult(ProviderOutcome.CONFIRMED_FAILED, null, code, message, fee, category);
  }

  public static PayoutResult ambiguous(String code, String message, BigDecimal fee) {
    return new PayoutResult(
        ProviderOutcome.AMBIGUOUS, null, code, message, fee, FailureCategory.UNKNOWN);
  }
}
```

Retain constructor validation: success requires a reference and no failure fields; confirmed failure requires a non-`UNKNOWN` category; ambiguity requires `UNKNOWN` and no provider reference.

- [ ] **Step 4: Extend the provider command and interface**

```java
public record PayoutCmd(
    UUID attemptId,
    String providerIdempotencyKey,
    String paymentId,
    BigDecimal amount,
    String sourceCurrency,
    String targetCurrency,
    String routeCode,
    BigDecimal routeBaseFee,
    int attemptNumber) {
  public PayoutCmd {
    Objects.requireNonNull(attemptId, "attemptId must not be null");
    if (!("payout:" + attemptId).equals(providerIdempotencyKey)) {
      throw new IllegalArgumentException("providerIdempotencyKey must match attemptId");
    }
    // Preserve the existing amount, currency, route, and attempt validations.
  }
}

public interface PayoutProvider {
  String code();
  PayoutResult submit(PayoutCmd cmd);
  PayoutResult lookup(String providerIdempotencyKey);
}
```

- [ ] **Step 5: Make each mock adapter idempotent**

Use a `ConcurrentHashMap<String, PayoutResult>` in each adapter:

```java
private final ConcurrentHashMap<String, PayoutResult> outcomes = new ConcurrentHashMap<>();

@Override
public PayoutResult submit(PayoutCmd cmd) {
  Objects.requireNonNull(cmd, "cmd must not be null");
  return outcomes.computeIfAbsent(cmd.providerIdempotencyKey(), ignored -> calculate(cmd));
}

@Override
public PayoutResult lookup(String providerIdempotencyKey) {
  return outcomes.getOrDefault(
      providerIdempotencyKey,
      PayoutResult.ambiguous("STATUS_UNKNOWN", "Provider has no recorded result", BigDecimal.ZERO));
}
```

For `StandardBankAdapter`, define `STANDARD_BANK:N` as confirmed transient failure for the first `N` unique attempt keys, `STANDARD_BANK:PERMANENT` as confirmed permanent failure, and `STANDARD_BANK:AMBIGUOUS` as ambiguity. `InstantPayoutAdapter` succeeds. `LocalPartnerAdapter` classifies `LIMIT_EXCEEDED` as permanent.

Bind the Spring-managed standard-bank adapter without removing the existing environment variable:

```java
@Autowired
public StandardBankAdapter(
    @Value("${fluxpay.mock.standard-bank-mode:${SIMULATE_FAILURE:}}") String mode) {
  this(() -> mode == null || mode.isBlank() ? null : mode);
}
```

- [ ] **Step 6: Update compilation call sites without changing transaction behavior yet**

In `PayoutExecutionService`, construct `PayoutCmd` with `attempt.id()` and `"payout:" + attempt.id()`. Replace `PayoutResult.ok`/`failed` calls and test fixtures with the new factories. Treat `AMBIGUOUS` as an `IllegalStateException("ambiguous provider outcome requires recovery state")` only as a temporary red test guard; Task 5 replaces this guard with persisted reconciliation.

- [ ] **Step 7: Run payout/provider tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=PayoutProviderTest,PayoutExecutionServiceTest,RecoveryServiceTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/dto backend/src/main/java/com/fluxpay/service/PayoutProvider.java backend/src/main/java/com/fluxpay/service/PayoutExecutionService.java backend/src/main/java/com/fluxpay/adapter backend/src/test/java/com/fluxpay/service/PayoutProviderTest.java backend/src/test/java/com/fluxpay/service/PayoutExecutionServiceTest.java backend/src/test/java/com/fluxpay/service/RecoveryServiceTest.java
git commit -m "feat: add idempotent provider outcome contract"
```

---

### Task 3: Oracle Recovery Schema and Domain Model

**Files:**
- Create: `backend/src/main/resources/db/migration/V505__payout_recovery.sql`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/RecoveryDisposition.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/RecoveryJobAction.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/RecoveryJobStatus.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/RecoveryDecision.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/PaymentRecoveryState.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/PayoutRecoveryJob.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/model/RecoveryEventInbox.java`
- Create: `backend/src/main/java/com/fluxpay/outbox/PaymentEventOutbox.java`
- Create: repositories under `backend/src/main/java/com/fluxpay/recovery/repository/`
- Create: `backend/src/main/java/com/fluxpay/outbox/PaymentEventOutboxRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java`
- Modify: `backend/src/test/java/com/fluxpay/repository/MigrationContractTest.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/RecoveryDomainModelTest.java`

**Interfaces:**
- Produces: durable entities and repositories used by every later task.
- Produces: `PaymentRecoveryStateRepository.findForUpdateByPaymentId(String)`.
- Produces: `PayoutRecoveryJob.pendingRetry(UUID,String,UUID,UUID,int,int,Instant,String,Instant)`, `pendingRefund(UUID,String,UUID,UUID,Instant,String,Instant)`, `claim(String,UUID,Instant)`, `complete(Instant,UUID)`, and `cancel(String,Instant)`.
- Produces: `PaymentEventOutbox.pending(UUID,String,String,String,Instant)` for event ID, topic, payment key, canonical JSON, and creation time.

- [ ] **Step 1: Add a migration contract test for all tables and constraints**

```java
@Test
void payoutRecoveryMigrationDefinesDurableJobsInboxAndOutbox() throws IOException {
  String sql = resource("V505__payout_recovery.sql");
  assertThat(sql)
      .contains("provider_idempotency_key VARCHAR2(150)")
      .contains("CREATE TABLE payment_recovery_state")
      .contains("retry_count NUMBER(2) DEFAULT 0 NOT NULL")
      .contains("CHECK (retry_count BETWEEN 0 AND 5)")
      .contains("CREATE TABLE payout_recovery_jobs")
      .contains("action VARCHAR2(20) NOT NULL")
      .contains("due_at TIMESTAMP WITH TIME ZONE NOT NULL")
      .contains("CONSTRAINT uq_recovery_source_action UNIQUE (source_event_id, action)")
      .contains("CREATE TABLE recovery_event_inbox")
      .contains("payload_hash VARCHAR2(64) NOT NULL")
      .contains("CREATE TABLE payment_event_outbox")
      .contains("envelope_json CLOB NOT NULL CHECK (envelope_json IS JSON)")
      .contains("CREATE INDEX idx_recovery_jobs_due")
      .contains("CREATE INDEX idx_event_outbox_due");
}
```

- [ ] **Step 2: Run the migration test and verify it fails because V505 is absent**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=MigrationContractTest test`

Expected: FAIL because `/db/migration/V505__payout_recovery.sql` is missing.

- [ ] **Step 3: Create the Oracle migration**

Use these exact table identities and checks; include all columns from the specification:

```sql
ALTER TABLE payout_attempts ADD (
  provider_idempotency_key VARCHAR2(150),
  outcome_certainty VARCHAR2(30),
  failure_category VARCHAR2(30),
  CONSTRAINT uq_attempt_provider_key UNIQUE (provider_idempotency_key),
  CONSTRAINT ck_attempt_certainty CHECK (
    outcome_certainty IN ('SUCCEEDED','CONFIRMED_FAILED','AMBIGUOUS')),
  CONSTRAINT ck_attempt_failure_category CHECK (
    failure_category IN ('TRANSIENT','PERMANENT','UNKNOWN'))
);

CREATE TABLE payment_recovery_state (
  payment_id VARCHAR2(50) PRIMARY KEY,
  disposition VARCHAR2(30) NOT NULL,
  retry_count NUMBER(2) DEFAULT 0 NOT NULL CHECK (retry_count BETWEEN 0 AND 5),
  current_route_id RAW(16) REFERENCES payout_routes(id),
  active_attempt_id RAW(16) REFERENCES payout_attempts(id),
  active_job_id RAW(16),
  last_failure_at TIMESTAMP WITH TIME ZONE,
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT ck_recovery_disposition CHECK (disposition IN
    ('IDLE','RETRY_PENDING','RETRY_IN_FLIGHT','RECONCILING',
     'REFUND_PENDING','REFUND_IN_FLIGHT','COMPLETED','REFUNDED'))
);

CREATE TABLE payout_recovery_jobs (
  id RAW(16) PRIMARY KEY,
  action VARCHAR2(20) NOT NULL CHECK (action IN ('RETRY','AUTO_REFUND')),
  payment_id VARCHAR2(50) NOT NULL REFERENCES payment_recovery_state(payment_id),
  source_event_id RAW(16) NOT NULL,
  failed_attempt_id RAW(16) NOT NULL REFERENCES payout_attempts(id),
  retry_sequence NUMBER(2),
  target_attempt_number NUMBER(10),
  status VARCHAR2(40) NOT NULL,
  due_at TIMESTAMP WITH TIME ZONE NOT NULL,
  decision_reason VARCHAR2(200) NOT NULL,
  lease_owner VARCHAR2(100),
  lease_token RAW(16),
  lease_expires_at TIMESTAMP WITH TIME ZONE,
  reserved_attempt_id RAW(16) REFERENCES payout_attempts(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT ck_recovery_job_status CHECK (status IN
    ('PENDING','IN_FLIGHT','SUCCEEDED','FAILED','CANCELLED',
     'EXHAUSTED','RECONCILIATION_REQUIRED')),
  CONSTRAINT uq_recovery_source_action UNIQUE (source_event_id, action),
  CONSTRAINT uq_recovery_failed_action UNIQUE (failed_attempt_id, action),
  CONSTRAINT uq_recovery_payment_sequence UNIQUE (payment_id, retry_sequence)
);

CREATE TABLE recovery_event_inbox (
  event_id RAW(16) PRIMARY KEY,
  topic VARCHAR2(150) NOT NULL,
  partition_number NUMBER(10) NOT NULL,
  offset_value NUMBER(19) NOT NULL,
  payload_hash VARCHAR2(64) NOT NULL,
  decision VARCHAR2(50) NOT NULL,
  processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_recovery_location UNIQUE (topic, partition_number, offset_value)
);

CREATE TABLE payment_event_outbox (
  id RAW(16) PRIMARY KEY,
  topic VARCHAR2(150) NOT NULL,
  payment_key VARCHAR2(50) NOT NULL,
  envelope_json CLOB NOT NULL CHECK (envelope_json IS JSON),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE,
  delivery_attempts NUMBER(10) DEFAULT 0 NOT NULL,
  next_delivery_at TIMESTAMP WITH TIME ZONE NOT NULL,
  lease_owner VARCHAR2(100),
  lease_token RAW(16),
  lease_expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_recovery_jobs_due
  ON payout_recovery_jobs(status, due_at, lease_expires_at);
CREATE INDEX idx_recovery_jobs_payment
  ON payout_recovery_jobs(payment_id, status);
CREATE INDEX idx_event_outbox_due
  ON payment_event_outbox(published_at, next_delivery_at, lease_expires_at);
```

- [ ] **Step 4: Write domain transition tests**

```java
@Test
void retryCountCannotExceedFive() {
  PaymentRecoveryState state = PaymentRecoveryState.create("P-001", ROUTE_ID, NOW);
  for (int sequence = 1; sequence <= 5; sequence++) {
    UUID jobId = UUID.randomUUID();
    state.scheduleRetry(jobId, sequence, NOW, NOW.plusSeconds(600));
    state.beginRetry(jobId, UUID.randomUUID(), sequence, NOW);
    state.confirmFailure(NOW);
  }
  assertThatThrownBy(
          () -> state.scheduleRetry(UUID.randomUUID(), 6, NOW, NOW.plusSeconds(600)))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("retry budget exhausted");
}

@Test
void refundAndCompletionAreTerminal() {
  PaymentRecoveryState state = PaymentRecoveryState.create("P-001", ROUTE_ID, NOW);
  state.scheduleRefund(UUID.randomUUID(), NOW);
  state.beginRefund(NOW);
  state.markRefunded(NOW);
  assertThat(state.disposition()).isEqualTo(RecoveryDisposition.REFUNDED);
  assertThatThrownBy(() -> state.scheduleRefund(UUID.randomUUID(), NOW))
      .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 5: Implement focused recovery entities and enums**

Expose these exact enum values and transition methods:

```java
public enum RecoveryDisposition {
  IDLE, RETRY_PENDING, RETRY_IN_FLIGHT, RECONCILING,
  REFUND_PENDING, REFUND_IN_FLIGHT, COMPLETED, REFUNDED
}

public enum RecoveryJobAction { RETRY, AUTO_REFUND }

public enum RecoveryJobStatus {
  PENDING, IN_FLIGHT, SUCCEEDED, FAILED, CANCELLED,
  EXHAUSTED, RECONCILIATION_REQUIRED
}

public enum RecoveryDecision {
  SCHEDULE_RETRY, SCHEDULE_REFUND, RECONCILE, NO_ACTION,
  STALE_EVENT, LEGACY_EVENT_REVIEW_REQUIRED
}
```

Implement JPA mappings using `UUID` for `RAW(16)`, `String` for payment IDs, `Instant` for timestamp-with-time-zone columns, and `@Version` for `payment_recovery_state.version`. Do not put provider calls, Kafka calls, or repositories inside entity methods.

- [ ] **Step 6: Add repository lock and count APIs**

Create:

```java
public interface PaymentRecoveryStateRepository
    extends JpaRepository<PaymentRecoveryState, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from PaymentRecoveryState s where s.paymentId = :paymentId")
  Optional<PaymentRecoveryState> findForUpdateByPaymentId(@Param("paymentId") String paymentId);

  List<PaymentRecoveryState> findFirst50ByDispositionOrderByUpdatedAtAsc(
      RecoveryDisposition disposition);
}

public interface PayoutRecoveryJobRepository
    extends JpaRepository<PayoutRecoveryJob, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select j from PayoutRecoveryJob j where j.id = :jobId")
  Optional<PayoutRecoveryJob> findByIdForUpdate(@Param("jobId") UUID jobId);

  List<PayoutRecoveryJob> findByPaymentIdAndStatusIn(
      String paymentId, Collection<RecoveryJobStatus> statuses);
  Optional<PayoutRecoveryJob> findBySourceEventIdAndAction(
      UUID sourceEventId, RecoveryJobAction action);
  Optional<PayoutRecoveryJob> findByPaymentIdAndRetrySequence(
      String paymentId, int retrySequence);
  long countByPaymentIdAndAction(String paymentId, RecoveryJobAction action);
}

public interface RecoveryEventInboxRepository
    extends JpaRepository<RecoveryEventInbox, UUID> {}
```

Also add `long countByPaymentId(String paymentId)` to `PayoutAttemptRepository`; acceptance tests use it to assert the six-attempt ceiling.

- [ ] **Step 7: Run migration and model tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=MigrationContractTest,RecoveryDomainModelTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/resources/db/migration/V505__payout_recovery.sql backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java backend/src/main/java/com/fluxpay/recovery backend/src/main/java/com/fluxpay/outbox/PaymentEventOutbox.java backend/src/main/java/com/fluxpay/outbox/PaymentEventOutboxRepository.java backend/src/test/java/com/fluxpay/repository/MigrationContractTest.java backend/src/test/java/com/fluxpay/recovery/RecoveryDomainModelTest.java
git commit -m "feat: add durable payout recovery schema"
```

---

### Task 4: Transactional Payment Event Outbox

**Files:**
- Create: `backend/src/main/java/com/fluxpay/outbox/OutboxEventPublisher.java`
- Create: `backend/src/main/java/com/fluxpay/outbox/KafkaEventSender.java`
- Create: `backend/src/main/java/com/fluxpay/outbox/OutboxLeaseService.java`
- Create: `backend/src/main/java/com/fluxpay/outbox/PaymentEventOutboxRelay.java`
- Remove: `backend/src/main/java/com/fluxpay/config/KafkaEventPublisher.java`
- Remove: `backend/src/test/java/com/fluxpay/config/KafkaEventPublisherTest.java`
- Test: `backend/src/test/java/com/fluxpay/outbox/OutboxEventPublisherTest.java`
- Test: `backend/src/test/java/com/fluxpay/outbox/PaymentEventOutboxRelayTest.java`

**Interfaces:**
- Consumes: `PaymentEventOutboxRepository`, `EventEnvelopeCodec`, `KafkaTemplate<String,String>`, `Clock`, and `PayoutRecoveryProperties`.
- Produces: the single application `EventPublisher` bean, which inserts outbox rows.
- Produces: `KafkaEventSender.send(String topic, String key, String json)` for outbox and DLT code.
- Produces: `OutboxLeaseService.claimNext(Instant,String,UUID,Instant)`, `markPublished(UUID,UUID,Instant)`, and `releaseWithBackoff(UUID,UUID,Instant)`.

- [ ] **Step 1: Write the outbox publisher test**

```java
@Test
void publishStoresCanonicalEnvelopeWithoutCallingKafka() {
  PaymentEventOutboxRepository repository = mock(PaymentEventOutboxRepository.class);
  EventEnvelopeCodec codec = new EventEnvelopeCodec(new ObjectMapper().findAndRegisterModules());
  OutboxEventPublisher publisher =
      new OutboxEventPublisher(repository, codec, Clock.fixed(NOW, ZoneOffset.UTC));
  PaymentEventPayload payload = PaymentEventPayload.random("P-001", NOW, Map.of("attempt", 1));

  publisher.publish(EventTopics.PAYOUT_FAILED, payload, "cid-1");

  ArgumentCaptor<PaymentEventOutbox> row = ArgumentCaptor.forClass(PaymentEventOutbox.class);
  verify(repository).save(row.capture());
  assertThat(row.getValue().id().toString()).isEqualTo(payload.eventId());
  assertThat(codec.read(row.getValue().envelopeJson()).paymentId()).isEqualTo("P-001");
}
```

- [ ] **Step 2: Run the test and verify it fails because outbox services are absent**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=OutboxEventPublisherTest test`

Expected: FAIL at compilation.

- [ ] **Step 3: Replace direct Kafka publication with outbox insertion**

```java
@Component
public class OutboxEventPublisher implements EventPublisher {
  @Override
  public void publish(String topic, Object payload, String correlationId) {
    if (!(payload instanceof PaymentEventPayload eventPayload)) {
      throw new IllegalArgumentException("payload must be PaymentEventPayload");
    }
    PaymentEventEnvelope envelope =
        PaymentEventEnvelope.from(topic, correlationId, eventPayload);
    repository.save(
        PaymentEventOutbox.pending(
            UUID.fromString(eventPayload.eventId()),
            topic,
            eventPayload.paymentId(),
            codec.write(envelope),
            clock.instant()));
  }
}
```

Delete `KafkaEventPublisher` so only `OutboxEventPublisher` implements `EventPublisher`. Keep `InMemoryEventPublisher` as the existing conditional fallback for sliced tests that do not load JPA.

- [ ] **Step 4: Write relay success and failure tests**

```java
@Test
void acknowledgedSendMarksTheClaimedRowPublished() {
  when(leases.claimNext(NOW, "node-1", LEASE_TOKEN, NOW.plusSeconds(60)))
      .thenReturn(Optional.of(outbox));
  relay.relayOne();
  verify(sender).send(outbox.topic(), outbox.paymentKey(), outbox.envelopeJson());
  verify(leases).markPublished(outbox.id(), LEASE_TOKEN, NOW);
}

@Test
void failedSendReleasesWithBackoffAndDoesNotMarkPublished() {
  when(leases.claimNext(any(), anyString(), any(), any())).thenReturn(Optional.of(outbox));
  doThrow(new EventPublishException("send failed", new RuntimeException()))
      .when(sender).send(anyString(), anyString(), anyString());
  relay.relayOne();
  verify(leases).releaseWithBackoff(outbox.id(), LEASE_TOKEN, NOW.plusSeconds(1));
  verify(leases, never()).markPublished(any(), any(), any());
}
```

- [ ] **Step 5: Implement low-level sender and leased relay**

```java
@Component
public class KafkaEventSender {
  public void send(String topic, String key, String json) {
    try {
      kafkaTemplate.send(topic, key, json).join();
    } catch (RuntimeException failure) {
      throw new EventPublishException("Kafka publish failed for " + topic, failure);
    }
  }
}

@Component
public class PaymentEventOutboxRelay {
  @Scheduled(fixedDelayString = "${fluxpay.payout-recovery.worker-poll-interval:5s}")
  public void relay() {
    int sent = 0;
    while (sent < 100 && relayOne()) {
      sent++;
    }
  }

  boolean relayOne() {
    Instant now = clock.instant();
    UUID token = UUID.randomUUID();
    Optional<PaymentEventOutbox> claimed =
        leases.claimNext(now, workerId, token, now.plus(properties.leaseDuration()));
    if (claimed.isEmpty()) {
      return false;
    }
    PaymentEventOutbox row = claimed.get();
    try {
      sender.send(row.topic(), row.paymentKey(), row.envelopeJson());
      leases.markPublished(row.id(), token, clock.instant());
    } catch (EventPublishException failure) {
      leases.releaseWithBackoff(row.id(), token, now.plusSeconds(1));
    }
    return true;
  }
}
```

`OutboxLeaseService` owns `@Transactional` claim/finalization methods. Claim only unpublished rows whose `next_delivery_at <= now` and whose lease is absent or expired. Fence both success and release updates by `id` plus `lease_token`. Start publication backoff at one second, double it per failure, and cap it at one minute; this is independent of payout retry count.

- [ ] **Step 6: Run outbox tests and existing event codec tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=OutboxEventPublisherTest,PaymentEventOutboxRelayTest,EventEnvelopeCodecTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/outbox backend/src/main/java/com/fluxpay/config/KafkaEventPublisher.java backend/src/test/java/com/fluxpay/config/KafkaEventPublisherTest.java backend/src/test/java/com/fluxpay/outbox
git commit -m "feat: publish payment events through transactional outbox"
```

---

### Task 5: Split Payout Transactions From Provider I/O

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/PayoutReservation.java`
- Create: `backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutExecutionService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/RecoveryService.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/PayoutAttempt.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/PayoutOutcome.java`
- Modify: `backend/src/test/java/com/fluxpay/service/PayoutAttemptTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/PayoutAttemptTransactionServiceTest.java`
- Modify: `backend/src/test/java/com/fluxpay/service/PayoutExecutionServiceTest.java`
- Modify: `backend/src/test/java/com/fluxpay/service/RecoveryServiceTest.java`
- Modify: `backend/src/test/java/com/fluxpay/service/RecoveryConcurrencyTest.java`
- Modify: `backend/src/test/java/com/fluxpay/controller/PayoutControllerContractTest.java`

**Interfaces:**
- Produces: `PayoutAttemptTransactionService.reserveInitial(String,String,String)`, `reserveManualRetry(String,String)`, `reserveManualSwitch(String,String,String)`, `reserveRecovery(String,UUID,int,int,String,String,UUID,UUID)`, and `finalizeOutcome(PayoutReservation,PayoutResult)`.
- Produces: `PayoutExecutionService.execute(PayoutReservation)` for the retry worker.
- Consumes: `OutboxEventPublisher` through `EventPublisher`, making attempt and event writes atomic in Oracle.

- [ ] **Step 1: Write reservation and failure-envelope tests**

```java
@Test
void attemptPersistsStableKeyAndConfirmedFailureMetadata() {
  PayoutAttempt attempt = PayoutAttempt.initiated(ATTEMPT_ID, "P-001", 6, ROUTE_ID, NOW);
  attempt.assignProviderKey("payout:" + ATTEMPT_ID);
  attempt.markProcessing();
  attempt.markFailed(
      "TEMPORARY_UNAVAILABLE", "try later", FailureCategory.TRANSIENT, NOW.plusSeconds(2));
  assertThat(attempt.providerIdempotencyKey()).isEqualTo("payout:" + ATTEMPT_ID);
  assertThat(attempt.outcomeCertainty()).isEqualTo(ProviderOutcome.CONFIRMED_FAILED);
  assertThat(attempt.failureCategory()).isEqualTo(FailureCategory.TRANSIENT);
  assertThat(attempt.completedAt()).isEqualTo(NOW.plusSeconds(2));
}

@Test
void reserveRecoveryCreatesAttemptSixWithStableProviderKey() {
  PayoutReservation reservation =
      transactions.reserveRecovery(
          "P-001", ROUTE_ID, 6, 5, "AUTO_RETRY", "cid-6", JOB_ID, LEASE_TOKEN);
  assertThat(reservation.attemptNumber()).isEqualTo(6);
  assertThat(reservation.retrySequence()).isEqualTo(5);
  assertThat(reservation.providerIdempotencyKey())
      .isEqualTo("payout:" + reservation.attemptId());
}

@Test
void confirmedFailurePublishesVersionTwoFailureMetadata() {
  transactions.finalizeOutcome(
      reservation,
      PayoutResult.confirmedFailure(
          "TEMPORARY_UNAVAILABLE", "try later", FEE, FailureCategory.TRANSIENT));
  ArgumentCaptor<PaymentEventPayload> payload =
      ArgumentCaptor.forClass(PaymentEventPayload.class);
  verify(events).publish(eq(EventTopics.PAYOUT_FAILED), payload.capture(), eq("cid-6"));
  assertThat(payload.getValue().details())
      .containsEntry("schemaVersion", 2)
      .containsEntry("attemptId", reservation.attemptId().toString())
      .containsEntry("retrySequence", 5)
      .containsEntry("outcomeCertainty", "CONFIRMED_FAILED")
      .containsEntry("failureCategory", "TRANSIENT")
      .containsEntry("providerIdempotencyKey", reservation.providerIdempotencyKey());
}
```

- [ ] **Step 2: Run focused tests and verify they fail**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=PayoutAttemptTest,PayoutAttemptTransactionServiceTest,PayoutExecutionServiceTest test`

Expected: FAIL because attempt metadata, reservations, and split transactions do not exist.

- [ ] **Step 3: Map provider metadata and deterministic terminal timestamps on attempts**

Add nullable historical fields named `providerIdempotencyKey`, `outcomeCertainty`, and `failureCategory`, then implement:

```java
public void assignProviderKey(String key) {
  String required = "payout:" + id;
  if (!required.equals(key)) {
    throw new IllegalArgumentException("provider key must match attempt id");
  }
  if (providerIdempotencyKey != null && !providerIdempotencyKey.equals(key)) {
    throw new IllegalStateException("provider key is already assigned");
  }
  providerIdempotencyKey = key;
}

public void markAmbiguous(String code, String message) {
  requireProcessing();
  errorCode = Objects.requireNonNull(code);
  errorMessage = Objects.requireNonNull(message);
  outcomeCertainty = ProviderOutcome.AMBIGUOUS;
  failureCategory = FailureCategory.UNKNOWN;
}

public void markCompleted(String providerReference, Instant completedAt) {
  requireProcessing();
  this.providerReference = Objects.requireNonNull(providerReference);
  this.completedAt = Objects.requireNonNull(completedAt);
  this.outcomeCertainty = ProviderOutcome.SUCCEEDED;
  this.failureCategory = null;
  this.errorCode = null;
  this.errorMessage = null;
  this.status = PayoutAttemptStatus.COMPLETED;
}

public void markFailed(
    String code, String message, FailureCategory category, Instant completedAt) {
  requireProcessing();
  if (category == FailureCategory.UNKNOWN) {
    throw new IllegalArgumentException("confirmed failure category must be known");
  }
  this.errorCode = Objects.requireNonNull(code);
  this.errorMessage = Objects.requireNonNull(message);
  this.failureCategory = Objects.requireNonNull(category);
  this.outcomeCertainty = ProviderOutcome.CONFIRMED_FAILED;
  this.completedAt = Objects.requireNonNull(completedAt);
  this.providerReference = null;
  this.status = PayoutAttemptStatus.FAILED;
}

private void requireProcessing() {
  if (status != PayoutAttemptStatus.PROCESSING) {
    throw new IllegalStateException("attempt must be PROCESSING");
  }
}
```

Replace every old `markCompleted(ref)` and `markFailed(code,message)` call in production and tests with the new timestamp/category signatures. Tests use their existing fixed `NOW`; legacy test failures use `FailureCategory.TRANSIENT` unless the fixture explicitly represents a permanent rejection.

- [ ] **Step 4: Add the immutable reservation contract**

```java
public record PayoutReservation(
    UUID attemptId,
    String paymentId,
    UUID routeId,
    String routeCode,
    int attemptNumber,
    int retrySequence,
    BigDecimal amount,
    String sourceCurrency,
    String targetCurrency,
    BigDecimal routeBaseFee,
    String providerIdempotencyKey,
    String correlationId,
    String submittedEventId,
    UUID recoveryJobId,
    UUID recoveryLeaseToken) {}
```

- [ ] **Step 5: Implement short transaction methods**

```java
@Service
@Profile("mock")
public class PayoutAttemptTransactionService {
  @Transactional
  public PayoutReservation reserveInitial(
      String paymentId, String routeCode, String correlationId) {
    PaymentSnapshot payment = paymentReader.get(paymentId);
    requirePayoutEligible(payment);
    if (attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId).isPresent()) {
      throw new IllegalStateException("payment " + paymentId + " already has an attempt");
    }
    PayoutRoute route = requireActiveRouteByCode(routeCode);
    return createReservation(
        payment, route, 1, 0, "SUBMIT", correlationId, null, null);
  }

  @Transactional
  public PayoutReservation reserveRecovery(
      String paymentId,
      UUID routeId,
      int attemptNumber,
      int retrySequence,
      String reason,
      String correlationId,
      UUID recoveryJobId,
      UUID recoveryLeaseToken) {
    PaymentSnapshot payment = paymentReader.get(paymentId);
    PayoutRoute route = requireActiveRouteById(routeId);
    return createReservation(
        payment, route, attemptNumber, retrySequence, reason, correlationId,
        recoveryJobId, recoveryLeaseToken);
  }

  @Transactional
  public PayoutReservation reserveManualRetry(String paymentId, String correlationId) {
    PayoutAttempt latest = lockAndRequireLatestFailed(paymentId);
    return createReservation(
        paymentReader.get(paymentId), requireActiveRouteById(latest.routeId()),
        latest.attemptNumber() + 1, latest.attemptNumber(), "RETRY", correlationId,
        null, null);
  }

  @Transactional
  public PayoutReservation reserveManualSwitch(
      String paymentId, String routeCode, String correlationId) {
    PayoutAttempt latest = lockAndRequireLatestFailed(paymentId);
    PayoutRoute route = requireActiveRouteByCode(routeCode);
    if (route.id().equals(latest.routeId())) {
      throw new IllegalArgumentException("switch route must differ from failed route");
    }
    return createReservation(
        paymentReader.get(paymentId), route, latest.attemptNumber() + 1,
        latest.attemptNumber(), "SWITCH", correlationId, null, null);
  }

  @Transactional
  public PayoutOutcome finalizeOutcome(
      PayoutReservation reservation, PayoutResult result) {
    PayoutAttempt attempt = attempts.findById(reservation.attemptId()).orElseThrow(
        () -> new NoSuchElementException("attempt " + reservation.attemptId() + " not found"));
    return switch (result.outcome()) {
      case SUCCEEDED -> persistSuccess(attempt, reservation, result);
      case CONFIRMED_FAILED -> persistFailure(attempt, reservation, result);
      case AMBIGUOUS -> persistAmbiguous(attempt, reservation, result);
    };
  }

  private PayoutReservation createReservation(
      PaymentSnapshot payment,
      PayoutRoute route,
      int attemptNumber,
      int retrySequence,
      String reason,
      String correlationId,
      UUID recoveryJobId,
      UUID recoveryLeaseToken) {
    UUID attemptId = UUID.randomUUID();
    PayoutAttempt attempt = PayoutAttempt.initiated(
        attemptId, payment.paymentId(), attemptNumber, route.getId(), clock.instant());
    attempt.assignProviderKey("payout:" + attemptId);
    attempts.saveAndFlush(attempt);
    String submittedEventId =
        publishSelectedAndSubmitted(attempt, route, retrySequence, reason, correlationId);
    attempt.markProcessing();
    return toReservation(
        attempt, payment, route, retrySequence, correlationId, submittedEventId,
        recoveryJobId, recoveryLeaseToken);
  }
}
```

`reserveInitial` creates `payment_recovery_state` in `IDLE` disposition if it does not exist, then calls `createReservation` with null recovery job/lease IDs. Reservation creates and flushes `PayoutAttempt`, assigns `payout:<attemptId>`, marks it processing, and queues `payment.route.selected` and `payout.submitted`; `publishSelectedAndSubmitted` returns the submitted event ID. The private helpers `requirePayoutEligible`, `requireActiveRouteByCode`, and `requireActiveRouteById` preserve the existing eligibility and active-route checks; `toReservation` copies every field listed in `PayoutReservation` without rereading mutable state.

Finalization handles all three `ProviderOutcome` values: success marks completed and queues `payout.completed`; confirmed failure marks failed and queues the enriched `payout.failed`; ambiguity stores certainty/error metadata, leaves the attempt nonterminal, and returns `PayoutOutcome.reconciling(submittedEventId)` without publishing `payout.failed`. Add that factory to `PayoutOutcome`; it returns `PayoutAttemptStatus.PROCESSING`, no allowed manual actions, and the submitted event ID.

When `recoveryJobId` is present, `finalizeOutcome` locks `payment_recovery_state` first, then the job, validates `recoveryLeaseToken`, and atomically updates attempt, job, state, and outbox. Success sets job `SUCCEEDED` and state `COMPLETED`; confirmed failure sets job `FAILED`, clears active IDs, and leaves state `IDLE` for the failure consumer; ambiguity sets job `RECONCILIATION_REQUIRED` and state `RECONCILING`.

- [ ] **Step 6: Make provider I/O transaction-free**

```java
@Service
@Profile("mock")
public class PayoutExecutionService {
  public PayoutOutcome submit(String paymentId, String routeCode, String correlationId) {
    return execute(transactions.reserveInitial(paymentId, routeCode, correlationId));
  }

  public PayoutOutcome execute(PayoutReservation reservation) {
    PayoutProvider provider = loadProvider(reservation.routeCode());
    PayoutResult result = provider.submit(toCommand(reservation));
    return transactions.finalizeOutcome(reservation, result);
  }
}
```

Remove class-level `@Transactional` from `PayoutExecutionService`. Add a Spring transaction integration test with a provider fake that asserts `TransactionSynchronizationManager.isActualTransactionActive()` is false inside `submit`.

Remove class-level `@Transactional` from `RecoveryService`. Until Task 9 routes actions through `PaymentRecoveryCoordinator`, implement manual methods as:

```java
public PayoutOutcome retry(String paymentId, String correlationId) {
  return execution.execute(transactions.reserveManualRetry(paymentId, correlationId));
}

public PayoutOutcome switchRoute(
    String paymentId, String routeCode, String correlationId) {
  return execution.execute(
      transactions.reserveManualSwitch(paymentId, routeCode, correlationId));
}

@Transactional
public RecoveryResult refund(String paymentId, String correlationId) {
  return performExistingRefund(paymentId, correlationId);
}
```

Move the current refund body into the private `performExistingRefund` helper. This keeps manual provider I/O outside Oracle transactions while preserving the existing refund transaction until Task 9 coordinates it with recovery jobs.

- [ ] **Step 7: Run payout transaction tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=PayoutAttemptTest,PayoutAttemptTransactionServiceTest,PayoutExecutionServiceTest,PayoutProviderTest,RecoveryServiceTest,RecoveryConcurrencyTest,PayoutControllerContractTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/service/PayoutReservation.java backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java backend/src/main/java/com/fluxpay/service/PayoutExecutionService.java backend/src/main/java/com/fluxpay/service/RecoveryService.java backend/src/main/java/com/fluxpay/beans/PayoutAttempt.java backend/src/main/java/com/fluxpay/dto/PayoutOutcome.java backend/src/test/java/com/fluxpay/service/PayoutAttemptTest.java backend/src/test/java/com/fluxpay/service/PayoutAttemptTransactionServiceTest.java backend/src/test/java/com/fluxpay/service/PayoutExecutionServiceTest.java backend/src/test/java/com/fluxpay/service/RecoveryServiceTest.java backend/src/test/java/com/fluxpay/service/RecoveryConcurrencyTest.java backend/src/test/java/com/fluxpay/controller/PayoutControllerContractTest.java
git commit -m "refactor: isolate payout provider calls from transactions"
```

---

### Task 6: Recovery Policy and Kafka Failure Ingestion

**Files:**
- Create: `backend/src/main/java/com/fluxpay/recovery/service/PayoutFailureDetails.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/LegacyFailureEventException.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/RecoveryPolicy.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/RecoveryEventIngestionService.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/kafka/PayoutFailureConsumer.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/kafka/KafkaDeadLetterPublisher.java`
- Modify: `backend/src/main/java/com/fluxpay/config/PayoutRecoveryConfiguration.java`
- Modify: `backend/src/main/java/com/fluxpay/config/PaymentEventConsumer.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/RecoveryPolicyTest.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/RecoveryEventIngestionServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/PayoutFailureConsumerTest.java`

**Interfaces:**
- Produces: `RecoveryPolicy.decide(PayoutFailureDetails, int retriesReserved, Instant confirmedAt)`.
- Produces: `RecoveryEventIngestionService.ingest(String key, String topic, int partition, long offset, String json)`.
- Consumes: recovery entities/repositories, `EventEnvelopeCodec`, `Clock`, and the approved properties.

- [ ] **Step 1: Write policy tests for the approved counting and delay**

```java
@Test
void transientFailureSchedulesNextSequenceTenMinutesLater() {
  RecoveryPolicy.Decision decision =
      policy.decide(transientFailure(ATTEMPT_ID, 4), 4, NOW);
  assertThat(decision.action()).isEqualTo(RecoveryDecision.SCHEDULE_RETRY);
  assertThat(decision.retrySequence()).isEqualTo(5);
  assertThat(decision.dueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
}

@Test
void fifthRetryFailureSchedulesImmediateRefund() {
  RecoveryPolicy.Decision decision =
      policy.decide(transientFailure(ATTEMPT_ID, 5), 5, NOW);
  assertThat(decision.action()).isEqualTo(RecoveryDecision.SCHEDULE_REFUND);
  assertThat(decision.dueAt()).isEqualTo(NOW);
}

@Test
void permanentFailureSchedulesImmediateRefund() {
  assertThat(policy.decide(permanentFailure(), 0, NOW).action())
      .isEqualTo(RecoveryDecision.SCHEDULE_REFUND);
}
```

- [ ] **Step 2: Run policy tests and verify they fail**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=RecoveryPolicyTest test`

Expected: FAIL because the policy does not exist.

- [ ] **Step 3: Implement strict failure parsing and policy**

```java
public record PayoutFailureDetails(
    UUID eventId,
    String paymentId,
    UUID attemptId,
    int attemptNumber,
    int retrySequence,
    String routeCode,
    ProviderOutcome outcomeCertainty,
    FailureCategory failureCategory,
    String providerIdempotencyKey) {
  public static PayoutFailureDetails from(PaymentEventEnvelope envelope) {
    Object version = envelope.payload().get("schemaVersion");
    if (!(version instanceof Number number) || number.intValue() != 2) {
      throw new LegacyFailureEventException(envelope.eventId());
    }
    return new PayoutFailureDetails(
        UUID.fromString(envelope.eventId()),
        envelope.paymentId(),
        UUID.fromString(requireString(envelope.payload(), "attemptId")),
        requirePositiveInt(envelope.payload(), "attempt"),
        requireNonNegativeInt(envelope.payload(), "retrySequence"),
        requireString(envelope.payload(), "routeCode"),
        ProviderOutcome.valueOf(requireString(envelope.payload(), "outcomeCertainty")),
        FailureCategory.valueOf(requireString(envelope.payload(), "failureCategory")),
        requireString(envelope.payload(), "providerIdempotencyKey"));
  }
}

public record Decision(
    RecoveryDecision action, int retrySequence, Instant dueAt, String reason) {}
```

Policy rules are exact: transient plus `retriesReserved < 5` schedules sequence `retriesReserved + 1` at `confirmedAt + 10m`; transient at 5 and permanent at any count schedule refund at `confirmedAt`; ambiguity returns `RECONCILE`; stale/current-state validation remains the ingestion service's responsibility.

- [ ] **Step 4: Write ingestion tests for deduplication and stale events**

```java
@Test
void duplicateEventReturnsStoredDecisionWithoutSecondJob() {
  when(inbox.findById(EVENT_ID)).thenReturn(Optional.of(existingInbox));
  assertThat(service.ingest(KEY, "payout.failed", 0, 7L, JSON))
      .isEqualTo(existingInbox.decision());
  verify(jobs, never()).save(any());
}

@Test
void currentTransientFailureCreatesOneDueJob() {
  when(states.findForUpdateByPaymentId("P-001")).thenReturn(Optional.of(state));
  when(attempts.findById(ATTEMPT_ID)).thenReturn(Optional.of(failedAttempt));
  service.ingest("P-001", "payout.failed", 0, 8L, JSON);
  verify(jobs).save(argThat(job ->
      job.retrySequence() == 1 && job.dueAt().equals(NOW.plusSeconds(600))));
  verify(inbox).save(argThat(row -> row.decision() == RecoveryDecision.SCHEDULE_RETRY));
}
```

- [ ] **Step 5: Implement transactional ingestion**

`RecoveryEventIngestionService.ingest` must hash the exact JSON with SHA-256, reject event-ID/hash conflicts, lock or create `payment_recovery_state`, compare the event attempt with the latest persisted attempt, call policy, insert one job, update state, and save the inbox decision in one `@Transactional` method. A stale failure saves `STALE_EVENT` without a job. Catch `LegacyFailureEventException` inside the ingestion service and save `LEGACY_EVENT_REVIEW_REQUIRED` without a job; malformed JSON and invalid v2 fields remain poison errors for the listener/DLT path.

- [ ] **Step 6: Add the dedicated listener and DLT publisher**

```java
@KafkaListener(
    topics = EventTopics.PAYOUT_FAILED,
    groupId = "${fluxpay.payout-recovery.consumer-group:fluxpay-payout-recovery}",
    containerFactory = "payoutRecoveryKafkaListenerContainerFactory",
    concurrency = "1")
public void onFailure(
    String json,
    @Header(KafkaHeaders.RECEIVED_KEY) String key,
    @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
    @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
    @Header(KafkaHeaders.OFFSET) long offset) {
  try {
    ingestion.ingest(key, topic, partition, offset, json);
  } catch (IllegalArgumentException poison) {
    deadLetters.publish(topic, partition, offset, json, poison);
  }
}
```

Annotate `PayoutFailureConsumer` with `@Profile("mock")` and `@ConditionalOnProperty(prefix="fluxpay.payout-recovery", name="enabled", havingValue="true")`. Add `spring.kafka.consumer.enable-auto-commit: false` and string key/value deserializers in `application.yml`, then create the dedicated factory:

```java
@Bean("payoutRecoveryKafkaListenerContainerFactory")
ConcurrentKafkaListenerContainerFactory<String, String> payoutRecoveryKafkaListenerContainerFactory(
    ConsumerFactory<String, String> consumerFactory) {
  ConcurrentKafkaListenerContainerFactory<String, String> factory =
      new ConcurrentKafkaListenerContainerFactory<>();
  factory.setConsumerFactory(consumerFactory);
  factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
  DefaultErrorHandler errors =
      new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
  errors.setClassifications(Map.of(), true);
  factory.setCommonErrorHandler(errors);
  return factory;
}
```

Refactor `PaymentEventConsumer` to call the same `KafkaDeadLetterPublisher` instead of manually building JSON.

- [ ] **Step 7: Run policy, ingestion, listener, and timeline tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=RecoveryPolicyTest,RecoveryEventIngestionServiceTest,PayoutFailureConsumerTest,TimelineKafkaConfigurationTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/recovery backend/src/main/java/com/fluxpay/config/PayoutRecoveryConfiguration.java backend/src/main/java/com/fluxpay/config/PaymentEventConsumer.java backend/src/test/java/com/fluxpay/recovery backend/src/test/java/com/fluxpay/config/TimelineKafkaConfigurationTest.java
git commit -m "feat: schedule payout recovery from Kafka failures"
```

---

### Task 7: Leased Retry Worker and Payment Coordinator

**Files:**
- Create: `backend/src/main/java/com/fluxpay/recovery/repository/RecoveryJobClaimRepository.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/RecoveryJobLeaseService.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/PaymentRecoveryCoordinator.java`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/RecoveryJobWorker.java`
- Create: `backend/src/main/java/com/fluxpay/exception/RecoveryConflictException.java`
- Modify: `backend/src/main/java/com/fluxpay/recovery/model/PayoutRecoveryJob.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/RecoveryJobLeaseServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/PaymentRecoveryCoordinatorTest.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/RecoveryJobWorkerTest.java`

**Interfaces:**
- Produces: `RecoveryJobLeaseService.claimDue(String workerId, Instant now)` and fenced completion APIs.
- Produces: `PaymentRecoveryCoordinator.reserveAutomaticRetry(UUID jobId, UUID leaseToken, Instant now)`.
- Produces: `RecoveryJobWorker.runDueJobs()`.
- Consumes: `PayoutExecutionService.execute(PayoutReservation)` from Task 5.

- [ ] **Step 1: Write lease tests**

```java
@Test
void expiredLeaseCanBeClaimedWithANewToken() {
  when(claims.claimDue(NOW, "worker-2", TOKEN_2, NOW.plusSeconds(60)))
      .thenReturn(Optional.of(job));
  assertThat(service.claimDue("worker-2", NOW)).contains(job);
  assertThat(job.leaseToken()).isEqualTo(TOKEN_2);
}

@Test
void staleTokenCannotCompleteJob() {
  assertThatThrownBy(() -> service.markSucceeded(JOB_ID, TOKEN_1, NOW))
      .isInstanceOf(RecoveryConflictException.class)
      .hasMessage("recovery job lease is stale");
}
```

- [ ] **Step 2: Run the lease test and verify it fails**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=RecoveryJobLeaseServiceTest test`

Expected: FAIL because leasing is absent.

- [ ] **Step 3: Implement Oracle due-job claim and fencing**

Use `NamedParameterJdbcTemplate` in `RecoveryJobClaimRepository`. In one transaction, select the oldest eligible row with Oracle `FOR UPDATE SKIP LOCKED`, then update it to `IN_FLIGHT` with worker, token, and expiry. In this task, eligibility is `action='RETRY'`, `status='PENDING'`, `due_at <= :now`, and no live lease; refund jobs remain pending until Task 9 adds their executor. Return the claimed entity by ID. Every finalization update includes `WHERE id=:id AND lease_token=:token AND status='IN_FLIGHT'` and requires an update count of one.

```java
public Optional<PayoutRecoveryJob> claimDue(
    Instant now, String workerId, UUID leaseToken, Instant leaseExpiresAt);

public void requireFencedUpdate(int updated) {
  if (updated != 1) {
    throw new RecoveryConflictException("recovery job lease is stale");
  }
}
```

Create the conflict type and add lease validation to the job entity:

```java
public class RecoveryConflictException extends RuntimeException {
  public RecoveryConflictException(String message) {
    super(message);
  }
}

public void requireActiveLease(UUID token, Instant now) {
  if (status != RecoveryJobStatus.IN_FLIGHT
      || !Objects.equals(leaseToken, token)
      || leaseExpiresAt == null
      || leaseExpiresAt.isBefore(now)) {
    throw new RecoveryConflictException("recovery job lease is stale");
  }
}
```

- [ ] **Step 4: Write coordinator reservation tests**

```java
@Test
void retryFiveReservesAttemptSixWithoutLedgerInteraction() {
  when(states.findForUpdateByPaymentId("P-001")).thenReturn(Optional.of(stateAtFourRetries));
  when(jobs.findByIdForUpdate(JOB_ID)).thenReturn(Optional.of(retryFiveJob));
  PayoutReservation result = coordinator.reserveAutomaticRetry(JOB_ID, TOKEN, NOW);
  assertThat(result.attemptNumber()).isEqualTo(6);
  assertThat(result.retrySequence()).isEqualTo(5);
  verifyNoInteractions(ledger);
}

@Test
void stateChangeAfterSchedulingCancelsTheJob() {
  when(states.findForUpdateByPaymentId("P-001")).thenReturn(Optional.of(completedState));
  assertThat(coordinator.reserveAutomaticRetry(JOB_ID, TOKEN, NOW)).isEmpty();
  assertThat(retryJob.status()).isEqualTo(RecoveryJobStatus.CANCELLED);
}
```

- [ ] **Step 5: Implement coordinator reservation under one payment lock**

```java
@Transactional
public Optional<PayoutReservation> reserveAutomaticRetry(
    UUID jobId, UUID leaseToken, Instant now) {
  PayoutRecoveryJob snapshot = jobs.findById(jobId).orElseThrow(
      () -> new NoSuchElementException("recovery job " + jobId + " not found"));
  PaymentRecoveryState state = states.findForUpdateByPaymentId(snapshot.paymentId()).orElseThrow(
      () -> new NoSuchElementException(
          "recovery state for " + snapshot.paymentId() + " not found"));
  PayoutRecoveryJob job = jobs.findByIdForUpdate(jobId).orElseThrow(
      () -> new NoSuchElementException("recovery job " + jobId + " not found"));
  job.requireActiveLease(leaseToken, now);
  if (!state.mayRun(job, now)) {
    job.cancel("payment state changed", now);
    return Optional.empty();
  }
  PayoutReservation reservation = transactions.reserveRecovery(
      job.paymentId(), state.currentRouteId(), job.targetAttemptNumber(),
      job.retrySequence(), "AUTO_RETRY", "recovery:" + job.id(),
      job.id(), leaseToken);
  state.beginRetry(job.id(), reservation.attemptId(), job.retrySequence(), now);
  job.reserveAttempt(reservation.attemptId(), leaseToken);
  return Optional.of(reservation);
}
```

Make `reserveRecovery` participate in the coordinator transaction with propagation `MANDATORY`; it must not acquire locks in a different order.

- [ ] **Step 6: Write and implement worker orchestration**

```java
@Scheduled(fixedDelayString = "${fluxpay.payout-recovery.worker-poll-interval:5s}")
public void runDueJobs() {
  if (!properties.enabled()) return;
  for (int processed = 0; processed < 50; processed++) {
    Optional<PayoutRecoveryJob> claimed = leases.claimDue(workerId, clock.instant());
    if (claimed.isEmpty()) return;
    runClaimed(claimed.get());
  }
}

void runClaimed(PayoutRecoveryJob job) {
  if (job.action() != RecoveryJobAction.RETRY) return;
  coordinator.reserveAutomaticRetry(job.id(), job.leaseToken(), clock.instant())
      .ifPresent(reservation -> execution.execute(reservation));
}
```

Annotate `RecoveryJobWorker` with `@Profile("mock")` and the same enabled-property condition as the consumer. Outcome finalization marks the source retry job `SUCCEEDED` for provider success, `FAILED` for confirmed failure, or `RECONCILIATION_REQUIRED` for ambiguity using the same lease token. The subsequent `payout.failed` Kafka event schedules the next business retry; the worker never resets the same job to pending. If an exception occurs before reservation commits, release the job with infrastructure backoff. If an exception occurs after reservation commits or during the provider call, mark the job and state `RECONCILIATION_REQUIRED` for that same attempt; never allocate another attempt from the exception path.

- [ ] **Step 7: Run retry-worker tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=RecoveryJobLeaseServiceTest,PaymentRecoveryCoordinatorTest,RecoveryJobWorkerTest,PayoutAttemptTransactionServiceTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/recovery backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java backend/src/main/java/com/fluxpay/exception/RecoveryConflictException.java backend/src/test/java/com/fluxpay/recovery backend/src/test/java/com/fluxpay/service/PayoutAttemptTransactionServiceTest.java
git commit -m "feat: execute leased automatic payout retries"
```

---

### Task 8: Ambiguous Outcome Reconciliation

**Files:**
- Create: `backend/src/main/java/com/fluxpay/recovery/service/ProviderReconciliationService.java`
- Modify: `backend/src/main/java/com/fluxpay/recovery/service/PaymentRecoveryCoordinator.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/ProviderReconciliationServiceTest.java`

**Interfaces:**
- Produces: `ProviderReconciliationService.reconcileDue()` and `reconcile(String paymentId)`.
- Consumes: `PayoutProvider.lookup(String)`, payment recovery state, active attempt, and transaction finalization.

- [ ] **Step 1: Write reconciliation tests**

```java
@Test
void unresolvedStatusKeepsPaymentInReconciliation() {
  when(provider.lookup("payout:" + ATTEMPT_ID))
      .thenReturn(PayoutResult.ambiguous("STATUS_UNKNOWN", "not settled", FEE));
  service.reconcile("P-001");
  verify(coordinator).keepReconciling("P-001", ATTEMPT_ID, NOW);
  verifyNoInteractions(refunds);
}

@Test
void confirmedSuccessCancelsPendingRecovery() {
  when(provider.lookup("payout:" + ATTEMPT_ID))
      .thenReturn(PayoutResult.succeeded("SB-77", FEE));
  service.reconcile("P-001");
  verify(coordinator).finalizeReconciledOutcome(
      eq("P-001"), eq(ATTEMPT_ID), argThat(PayoutResult::success), eq(NOW));
}

@Test
void confirmedFailurePublishesFailureForNormalScheduling() {
  PayoutResult failed = PayoutResult.confirmedFailure(
      "TEMPORARY_UNAVAILABLE", "try later", FEE, FailureCategory.TRANSIENT);
  when(provider.lookup("payout:" + ATTEMPT_ID)).thenReturn(failed);
  service.reconcile("P-001");
  verify(coordinator).finalizeReconciledOutcome("P-001", ATTEMPT_ID, failed, NOW);
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=ProviderReconciliationServiceTest test`

Expected: FAIL because reconciliation is absent.

- [ ] **Step 3: Implement reconciliation polling without holding a DB transaction during provider lookup**

```java
@Scheduled(fixedDelayString =
    "${fluxpay.payout-recovery.reconciliation-poll-interval:1m}")
public void reconcileDue() {
  if (!properties.enabled()) return;
  states.findFirst50ByDispositionOrderByUpdatedAtAsc(RecoveryDisposition.RECONCILING)
      .forEach(state -> reconcile(state.paymentId()));
}

public void reconcile(String paymentId) {
  ReconciliationSnapshot snapshot = coordinator.snapshotForReconciliation(paymentId);
  PayoutProvider provider = providers.require(snapshot.routeCode());
  PayoutResult result = provider.lookup(snapshot.providerIdempotencyKey());
  if (result.outcome() == ProviderOutcome.AMBIGUOUS) {
    coordinator.keepReconciling(paymentId, snapshot.attemptId(), clock.instant());
  } else {
    coordinator.finalizeReconciledOutcome(
        paymentId, snapshot.attemptId(), result, clock.instant());
  }
}
```

Annotate `ProviderReconciliationService` with `@Profile("mock")` and the enabled-property condition. `snapshotForReconciliation` and `finalizeReconciledOutcome` are separate `@Transactional` methods. Finalization locks the payment row, verifies that the active attempt is unchanged, and fences late results. Confirmed success cancels active/pending jobs and queues `payout.completed`; confirmed failure marks the attempt failed and queues `payout.failed`, allowing Task 6 to schedule the next action.

- [ ] **Step 4: Run reconciliation and payout tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=ProviderReconciliationServiceTest,PaymentRecoveryCoordinatorTest,PayoutExecutionServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/recovery/service/ProviderReconciliationService.java backend/src/main/java/com/fluxpay/recovery/service/PaymentRecoveryCoordinator.java backend/src/main/java/com/fluxpay/service/PayoutAttemptTransactionService.java backend/src/test/java/com/fluxpay/recovery/ProviderReconciliationServiceTest.java backend/src/test/java/com/fluxpay/recovery/PaymentRecoveryCoordinatorTest.java
git commit -m "feat: reconcile ambiguous payout outcomes"
```

---

### Task 9: Automatic Refund and Manual Action Coordination

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/recovery/service/PaymentRecoveryCoordinator.java`
- Modify: `backend/src/main/java/com/fluxpay/recovery/service/RecoveryJobWorker.java`
- Modify: `backend/src/main/java/com/fluxpay/service/RecoveryService.java`
- Modify: `backend/src/main/java/com/fluxpay/controller/PayoutController.java`
- Modify: `backend/src/main/java/com/fluxpay/config/M4ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/fluxpay/recovery/AutomaticRefundTest.java`
- Modify: `backend/src/test/java/com/fluxpay/service/RecoveryServiceTest.java`
- Modify: `backend/src/test/java/com/fluxpay/controller/PayoutControllerContractTest.java`

**Interfaces:**
- Produces: `PaymentRecoveryCoordinator.executeAutomaticRefund(UUID,UUID,Instant)`, `requestManualRefund(String,String,Instant)`, `reserveManualRouteSwitch(String,UUID,String,Instant)`, and `allowedActions(String)`.
- Consumes: existing `RefundJournalService`, `LedgerWriter`, event outbox, payment reader, and route/attempt repositories.

- [ ] **Step 1: Write the six-failures-to-one-refund test**

```java
@Test
void exhaustedRetryJobCreditsSenderExactlyOnce() {
  MockLedgerWriter ledger = new MockLedgerWriter();
  RefundJournalService journal = new RefundJournalService(ledger);
  PaymentRecoveryCoordinator coordinator = fixture(journal, exhaustedState(), refundJob());

  coordinator.executeAutomaticRefund(REFUND_JOB_ID, LEASE_TOKEN, NOW);
  coordinator.executeAutomaticRefund(REFUND_JOB_ID, LEASE_TOKEN, NOW);

  assertThat(ledger.balancesSnapshot().get(MockLedgerWriter.P001_SENDER_WALLET))
      .isEqualByComparingTo("10000.00");
  assertThat(ledger.entriesSnapshot())
      .containsKeys("refund:P-001:clearing:debit", "refund:P-001:sender:credit");
  verify(events, times(1)).publish(eq(EventTopics.PAYMENT_REFUNDED), any(), anyString());
  assertThat(state.disposition()).isEqualTo(RecoveryDisposition.REFUNDED);
}
```

- [ ] **Step 2: Run automatic refund tests and verify they fail**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=AutomaticRefundTest test`

Expected: FAIL because refund jobs are not executed.

- [ ] **Step 3: Implement replay-safe automatic refund**

```java
@Transactional
public RecoveryResult executeAutomaticRefund(UUID jobId, UUID leaseToken, Instant now) {
  PayoutRecoveryJob snapshot = jobs.findById(jobId).orElseThrow(
      () -> new NoSuchElementException("recovery job " + jobId + " not found"));
  PaymentRecoveryState state = states.findForUpdateByPaymentId(snapshot.paymentId()).orElseThrow(
      () -> new NoSuchElementException(
          "recovery state for " + snapshot.paymentId() + " not found"));
  PayoutRecoveryJob job = jobs.findByIdForUpdate(jobId).orElseThrow(
      () -> new NoSuchElementException("recovery job " + jobId + " not found"));
  if (state.disposition() == RecoveryDisposition.REFUNDED) {
    String eventId = PaymentEventPayload.refund(job.paymentId(), now, Map.of()).eventId();
    return new RecoveryResult(job.paymentId(), eventId, true);
  }
  job.requireActiveLease(leaseToken, now);
  requireNoActivePayout(state);
  PaymentSnapshot payment = paymentReader.get(job.paymentId());
  PayoutAttempt failedAttempt = attempts.findById(job.failedAttemptId()).orElseThrow(
      () -> new NoSuchElementException(
          "failed attempt " + job.failedAttemptId() + " not found"));
  if (!refundJournal.isAlreadyRefunded(payment)) {
    refundJournal.refund(payment);
  }
  PaymentEventPayload payload = PaymentEventPayload.refund(
      payment.paymentId(), now,
      Map.of("attempt", failedAttempt.attemptNumber(), "amount", payment.amount(),
          "currency", payment.sourceCurrency(),
          "summary", "Automatic refund after confirmed terminal failure"));
  events.publish(EventTopics.PAYMENT_REFUNDED, payload, "auto-refund:" + job.id());
  state.markRefunded(now);
  job.complete(now, leaseToken);
  cancelOtherActiveJobs(payment.paymentId(), job.id(), now);
  return new RecoveryResult(payment.paymentId(), payload.eventId(), false);
}
```

The mock ledger write is not Oracle-atomic. If a later database operation fails, replay first checks `isAlreadyRefunded` and persists state/outbox without issuing another in-process credit.

- [ ] **Step 4: Route refund jobs from the worker**

```java
switch (job.action()) {
  case RETRY -> runRetry(job);
  case AUTO_REFUND ->
      coordinator.executeAutomaticRefund(job.id(), job.leaseToken(), clock.instant());
}
```

Expand `RecoveryJobClaimRepository` eligibility from `action='RETRY'` to `action IN ('RETRY','AUTO_REFUND')` in the same change, so no refund job can be leased before the refund executor exists.

- [ ] **Step 5: Write manual action conflict tests**

```java
@Test
void manualRetryIsRejectedWhenAutomaticRecoveryIsEnabled() {
  when(properties.enabled()).thenReturn(true);
  assertThatThrownBy(() -> recovery.retry("P-001", "cid"))
      .isInstanceOf(RecoveryConflictException.class)
      .hasMessage("manual retry is disabled while automatic recovery is enabled");
}

@Test
void manualRefundCancelsPendingRetryBeforeCreditingSender() {
  coordinator.requestManualRefund("P-001", "cid", NOW);
  assertThat(retryJob.status()).isEqualTo(RecoveryJobStatus.CANCELLED);
  verify(refundJournal).refund(payment);
}

@Test
void manualSwitchConflictsWithAnActiveProviderCall() {
  assertThatThrownBy(
          () -> coordinator.reserveManualRouteSwitch("P-001", NEW_ROUTE_ID, "cid", NOW))
      .isInstanceOf(RecoveryConflictException.class)
      .hasMessage("payout recovery is already in progress");
}
```

- [ ] **Step 6: Delegate manual actions and update HTTP behavior**

When recovery is enabled, `RecoveryService.retry` throws `RecoveryConflictException`; `refund` and `switchRoute` call coordinator methods that lock state and cancel pending jobs. A switch increments the reserved retry count, rejects a sixth retry, reserves the next attempt, and then calls `PayoutExecutionService.execute` outside the lock transaction.

Map `RecoveryConflictException` to HTTP 409 with code `RECOVERY_CONFLICT`. Add this read-only coordinator method:

```java
@Transactional(readOnly = true)
public List<RecoveryAction> allowedActions(String paymentId) {
  return states.findById(paymentId)
      .map(state -> switch (state.disposition()) {
        case IDLE, RETRY_PENDING -> state.retryCount() < 5
            ? List.of(RecoveryAction.SWITCH, RecoveryAction.REFUND)
            : List.of(RecoveryAction.REFUND);
        case RETRY_IN_FLIGHT, RECONCILING, REFUND_PENDING, REFUND_IN_FLIGHT,
            COMPLETED, REFUNDED -> List.<RecoveryAction>of();
      })
      .orElse(List.of());
}
```

Inject `PayoutRecoveryProperties` and `PaymentRecoveryCoordinator` into `PayoutController`. In `build`, when automatic recovery is enabled, use `coordinator.allowedActions(paymentId)` instead of the static `PayoutOutcome.failed()` list. This removes `RETRY`, exposes `SWITCH`/`REFUND` only while no action is in flight, and exposes no recovery action after completion or refund.

- [ ] **Step 7: Run refund, service, concurrency, and controller tests**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=AutomaticRefundTest,RefundJournalServiceTest,RecoveryServiceTest,RecoveryConcurrencyTest,PayoutControllerContractTest test`

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/fluxpay/recovery backend/src/main/java/com/fluxpay/service/RecoveryService.java backend/src/main/java/com/fluxpay/controller/PayoutController.java backend/src/main/java/com/fluxpay/exception/RecoveryConflictException.java backend/src/main/java/com/fluxpay/config/M4ApiExceptionHandler.java backend/src/test/java/com/fluxpay/recovery backend/src/test/java/com/fluxpay/service backend/src/test/java/com/fluxpay/controller/PayoutControllerContractTest.java
git commit -m "feat: refund payouts after five failed retries"
```

---

### Task 10: Recovery Metrics and Operational Signals

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/fluxpay/recovery/service/RecoveryMetrics.java`
- Modify: recovery consumer, ingestion, worker, reconciliation, refund, and outbox relay files created above.
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/fluxpay/recovery/RecoveryMetricsTest.java`

**Interfaces:**
- Produces: counters and timers prefixed `fluxpay.payout.recovery` and `fluxpay.payment.outbox`.
- Consumes: Micrometer `MeterRegistry` supplied by Spring Boot Actuator.

- [ ] **Step 1: Write metric registration tests**

```java
@Test
void recordsScheduledRetryAndAutomaticRefund() {
  SimpleMeterRegistry registry = new SimpleMeterRegistry();
  RecoveryMetrics metrics = new RecoveryMetrics(registry);
  metrics.recordDecision(RecoveryDecision.SCHEDULE_RETRY);
  metrics.recordRefund("success");
  assertThat(registry.counter(
      "fluxpay.payout.recovery.decisions", "decision", "SCHEDULE_RETRY").count())
      .isEqualTo(1.0);
  assertThat(registry.counter(
      "fluxpay.payout.recovery.refunds", "result", "success").count())
      .isEqualTo(1.0);
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvnw.cmd -f backend/pom.xml -Dtest=RecoveryMetricsTest test`

Expected: FAIL because Actuator and `RecoveryMetrics` are absent.

- [ ] **Step 3: Add Actuator and focused metrics**

Add `spring-boot-starter-actuator` to `backend/pom.xml`. Implement:

```java
@Component
public class RecoveryMetrics {
  private final MeterRegistry registry;

  public RecoveryMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void recordDecision(RecoveryDecision decision) {
    registry.counter(
        "fluxpay.payout.recovery.decisions", "decision", decision.name()).increment();
  }

  public Timer.Sample startProviderCall() {
    return Timer.start(registry);
  }

  public void stopProviderCall(Timer.Sample sample, String provider, String result) {
    sample.stop(Timer.builder("fluxpay.payout.recovery.provider")
        .tag("provider", provider).tag("result", result).register(registry));
  }

  public void recordJob(String action, String result) {
    registry.counter(
        "fluxpay.payout.recovery.jobs", "action", action, "result", result).increment();
  }

  public void recordRefund(String result) {
    registry.counter("fluxpay.payout.recovery.refunds", "result", result).increment();
  }

  public void recordDuplicateEvent() {
    registry.counter("fluxpay.payout.recovery.duplicate.events").increment();
  }

  public void recordFencedWrite() {
    registry.counter("fluxpay.payout.recovery.fenced.writes").increment();
  }

  public void recordOutboxSend(String result) {
    registry.counter("fluxpay.payment.outbox.sends", "result", result).increment();
  }
}
```

Instrument state transitions once at their authoritative service boundary; do not increment the same event in both listener and ingestion service. Log payment ID, attempt ID, retry sequence, event ID, job ID, correlation ID, route, policy decision, and state transition. Never log beneficiary details, credentials, complete event payloads, or JWTs.

- [ ] **Step 4: Expose health and metrics without exposing environment values**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
  endpoint:
    health:
      show-details: never
```

- [ ] **Step 5: Run metrics and full unit suite**

Run: `mvnw.cmd -f backend/pom.xml test`

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/pom.xml backend/src/main/java/com/fluxpay/recovery backend/src/main/java/com/fluxpay/outbox backend/src/main/resources/application.yml backend/src/test/java/com/fluxpay/recovery/RecoveryMetricsTest.java
git commit -m "feat: expose payout recovery metrics"
```

---

### Task 11: Kafka/Oracle Acceptance Tests and Runbook

**Files:**
- Create: `backend/src/test/java/com/fluxpay/recovery/PayoutRecoveryPersistenceIT.java`
- Create: `backend/src/test/java/com/fluxpay/recovery/MutableTestClock.java`
- Modify: `backend/src/test/java/com/fluxpay/repository/PaymentEventPersistenceIT.java`
- Modify: `scripts/test-all.py`
- Modify: `.env.example`
- Modify: `docs/04-member4-backend-demo.md`
- Modify: `docs/04-member4-baremetal-kafka.md`

**Interfaces:**
- Consumes: real Kafka and Oracle selected by `KAFKA_BOOTSTRAP_SERVERS` and `ORACLE_JDBC_URL`.
- Verifies: complete approved behavior without waiting fifty wall-clock minutes by advancing an injected test clock and invoking workers directly.

- [ ] **Step 1: Write the gated end-to-end exhaustion test**

```java
@SpringBootTest(properties = {
    "fluxpay.payout-recovery.enabled=true",
    "fluxpay.payout-recovery.max-retries=5",
    "fluxpay.payout-recovery.retry-delay=10m",
    "fluxpay.mock.standard-bank-mode=STANDARD_BANK:6"
})
@ActiveProfiles("mock")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ORACLE_JDBC_URL", matches = ".+")
class PayoutRecoveryPersistenceIT {
  @MockBean TaskScheduler taskScheduler;
  @Autowired PayoutExecutionService execution;
  @Autowired RecoveryJobWorker worker;
  @Autowired PaymentEventOutboxRelay relay;
  @Autowired PayoutAttemptRepository attempts;
  @Autowired PayoutRecoveryJobRepository jobs;
  @Autowired PaymentRecoveryStateRepository states;
  @Autowired LedgerWriter ledger;
  @Autowired MutableTestClock clock;

  @Test
  void fiveConfirmedRetriesProduceSixAttemptsAndOneRefund() throws Exception {
    submitInitialConfirmedFailure("P-001");
    awaitRecoveryJob("P-001", 1);
    for (int retry = 1; retry <= 5; retry++) {
      clock.advance(Duration.ofMinutes(10));
      worker.runDueJobs();
      relay.relay();
      awaitAttempt("P-001", retry + 1);
      if (retry < 5) awaitRecoveryJob("P-001", retry + 1);
    }
    awaitRefundJob("P-001");
    worker.runDueJobs();
    assertThat(attempts.countByPaymentId("P-001")).isEqualTo(6);
    assertThat(((MockLedgerWriter) ledger).entriesSnapshot())
        .containsKeys("refund:P-001:clearing:debit", "refund:P-001:sender:credit");
    assertThat(states.findById("P-001").orElseThrow().disposition())
        .isEqualTo(RecoveryDisposition.REFUNDED);
  }

  private void submitInitialConfirmedFailure(String paymentId) {
    PayoutOutcome outcome = execution.submit(paymentId, "STANDARD_BANK", "it-initial");
    assertThat(outcome.status()).isEqualTo(PayoutAttemptStatus.FAILED);
    relay.relay();
  }

  private PayoutRecoveryJob awaitRecoveryJob(String paymentId, int retrySequence)
      throws InterruptedException {
    for (int poll = 0; poll < 60; poll++) {
      Optional<PayoutRecoveryJob> found =
          jobs.findByPaymentIdAndRetrySequence(paymentId, retrySequence);
      if (found.isPresent()) return found.get();
      Thread.sleep(250);
    }
    throw new AssertionError("retry job " + retrySequence + " was not scheduled");
  }

  private PayoutRecoveryJob awaitRefundJob(String paymentId) throws InterruptedException {
    for (int poll = 0; poll < 60; poll++) {
      Optional<PayoutRecoveryJob> found = jobs.findAll().stream()
          .filter(job -> job.paymentId().equals(paymentId))
          .filter(job -> job.action() == RecoveryJobAction.AUTO_REFUND)
          .findFirst();
      if (found.isPresent()) return found.get();
      Thread.sleep(250);
    }
    throw new AssertionError("automatic refund job was not scheduled");
  }

  private void awaitAttempt(String paymentId, int attemptNumber) throws InterruptedException {
    for (int poll = 0; poll < 60; poll++) {
      Optional<PayoutAttempt> found =
          attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId);
      if (found.isPresent() && found.get().attemptNumber() >= attemptNumber) return;
      Thread.sleep(250);
    }
    throw new AssertionError("attempt " + attemptNumber + " was not persisted");
  }
}
```

Use a test-only `@Primary Clock` backed by `MutableTestClock`; its `advance(Duration)` changes the instant atomically and its zone remains UTC. The `fluxpay.mock.standard-bank-mode` property makes the real mock adapter return confirmed transient failure for six unique attempt keys. Do not weaken the production ten-minute configuration to make tests fast.

- [ ] **Step 2: Add the remaining acceptance scenarios**

Add these named tests with the exact setup and terminal assertions:

| Test method | Setup and action | Required assertions |
| --- | --- | --- |
| `successOnRetryCancelsLaterJobsAndDoesNotRefund` | Initial failure, then provider success for retry 1; advance ten minutes and run worker/relay | Two attempts, state `COMPLETED`, zero active jobs, no refund keys |
| `permanentFailureRefundsWithoutCreatingRetryJob` | Provider returns confirmed `PERMANENT` failure; relay and consume event; run refund job | One attempt, zero `RETRY` jobs, one successful `AUTO_REFUND`, state `REFUNDED` |
| `ambiguousOutcomeDoesNotRetryOrRefundUntilLookupConfirms` | Submit returns `AMBIGUOUS`; run retry and refund workers; then make lookup return confirmed failure | Before lookup: one attempt and no refund; after confirmation: one retry job due at confirmation plus ten minutes |
| `duplicateKafkaFailureCreatesOneInboxDecisionAndOneJob` | Send identical event twice and wait for both deliveries | One inbox row, one job, matching payload hash |
| `staleFailureAfterSuccessIsRecordedAsNoAction` | Complete attempt 2, then send failure event for attempt 1 | Inbox decision `STALE_EVENT`, no active job |
| `pendingOracleJobSurvivesSpringContextRestart` | Persist a due-future job, close and reopen Spring context against the same Oracle schema | Same job ID and due time remain, status `PENDING` |
| `manualRefundCancelsPendingRetry` | Schedule retry 1, call owner refund endpoint | Retry job `CANCELLED`, state `REFUNDED`, one sender credit |
| `manualRouteSwitchConsumesBudgetWithoutResettingIt` | State has retry count 2 and a pending job; switch route | Pending job `CANCELLED`, next attempt number 4, retry count 3, new route retained |
| `twoWorkerClaimsExecuteOneProviderAttempt` | Run two worker threads against one due job using a latch | One provider key observed, one reserved attempt, one terminal job update |
| `retriesNeverAppendASenderDebit` | Run initial plus five retries and refund with a spy ledger | No `LedgerWriter.append` call whose wallet is the sender and entry type is `DEBIT`; exactly one sender `CREDIT` |
| `mockLedgerRestartLosesRefundStateAndIsMarkedNonProduction` | Refund with one `MockLedgerWriter`, then construct a second instance | Second instance has seeded sender balance and lacks both refund keys |

Use concrete assertions such as:

```java
assertThat(attempts.countByPaymentId("P-001")).isEqualTo(6);
assertThat(jobs.countByPaymentIdAndAction("P-001", RecoveryJobAction.AUTO_REFUND)).isEqualTo(1);
verify(ledger, never()).append(
    eq(payment.senderWalletId()), eq("DEBIT"), any(), anyString(), anyString());
verify(ledger, times(1)).append(
    eq(payment.senderWalletId()), eq("CREDIT"), eq(payment.amount()),
    eq(payment.sourceCurrency()), eq("refund:P-001:sender:credit"));
```

- [ ] **Step 3: Run the integration test and inspect the first failure**

Run with configured infrastructure:

```powershell
mvnw.cmd -f backend/pom.xml -Dtest=PayoutRecoveryPersistenceIT test
```

Expected: PASS. If a scenario fails, correct the wiring or transaction boundary without reducing the assertion set.

- [ ] **Step 4: Add the integration suite to the repository gate**

In `scripts/test-all.py`, when both Oracle and Kafka variables are set, run:

```python
if run(maven_command(
        "-f", "backend/pom.xml",
        "-Dtest=PaymentEventPersistenceIT,PayoutRecoveryPersistenceIT", "test")):
    print("persistence IT FAIL")
    return 3
```

- [ ] **Step 5: Document controlled mock enablement**

Add to `.env.example`:

```dotenv
PAYOUT_RECOVERY_ENABLED=false
PAYOUT_RECOVERY_CONSUMER_GROUP=fluxpay-payout-recovery
PAYOUT_RECOVERY_MAX_RETRIES=5
PAYOUT_RECOVERY_RETRY_DELAY=10m
PAYOUT_RECOVERY_POLL_INTERVAL=5s
PAYOUT_RECOVERY_LEASE_DURATION=60s
PAYOUT_RECOVERY_RECONCILIATION_INTERVAL=1m
PAYOUT_RECOVERY_AUTO_REFUND=true
```

Update the backend demo with `SIMULATE_FAILURE=STANDARD_BANK:6`, the six-attempt timeline, automatic refund result, and a warning that the demonstration takes fifty minutes with real time. Document the `MutableTestClock` integration test as the fast verification route. Update the Kafka runbook to state that no delay topic is required and `payout.recovery.dlt` remains the only recovery-related topic.

- [ ] **Step 6: Run every verification gate**

Run:

```powershell
mvnw.cmd -f backend/pom.xml clean verify
python -m unittest discover -s tests -v
```

With Oracle and Kafka configured, also run:

```powershell
mvnw.cmd -f backend/pom.xml -Dtest=PaymentEventPersistenceIT,PayoutRecoveryPersistenceIT test
```

Expected: all commands exit 0; the integration output proves six attempts, ten-minute due-time calculations, one refund, and no extra sender debit.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/test/java/com/fluxpay/recovery/PayoutRecoveryPersistenceIT.java backend/src/test/java/com/fluxpay/recovery/MutableTestClock.java backend/src/test/java/com/fluxpay/repository/PaymentEventPersistenceIT.java scripts/test-all.py .env.example docs/04-member4-backend-demo.md docs/04-member4-baremetal-kafka.md
git commit -m "test: verify automatic payout recovery end to end"
```

---

## Final Verification Checklist

- [ ] `mvnw.cmd -f backend/pom.xml clean verify` exits 0.
- [ ] `python -m unittest discover -s tests -v` exits 0.
- [ ] Real Kafka/Oracle integration tests exit 0 when their environment variables are configured.
- [ ] Exactly six payout attempts exist after initial failure plus five failed retries.
- [ ] Every retry due time equals the preceding confirmed-failure time plus ten minutes.
- [ ] Confirmed permanent failures bypass retries and create one refund job.
- [ ] Ambiguous outcomes neither retry nor refund before provider confirmation.
- [ ] A successful payout cancels every pending retry/refund job.
- [ ] Manual refund and route switch cancel pending work under the payment lock.
- [ ] Manual retry returns `409 RECOVERY_CONFLICT` while automatic recovery is enabled.
- [ ] Retry and route-switch paths never call `LedgerWriter.append` with a sender debit.
- [ ] Refund writes one clearing debit and one sender credit using deterministic keys.
- [ ] Duplicate Kafka delivery, worker claims, and late results create no duplicate money movement.
- [ ] Logs and metrics contain identifiers and decisions but no credentials or beneficiary data.
- [ ] Documentation labels `MockLedgerWriter` restart behavior as non-production.
