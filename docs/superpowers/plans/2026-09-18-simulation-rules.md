# Deterministic Simulation Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the trust-backend simulation task with recipient-aware sanctions screening, preserved payout chaos triggers, and a synthetic QA recipient.

**Architecture:** Add a compatibility-preserving `ComplianceScreeningInput` to the compliance port, pass the locked recipient through payment confirmation, and keep all deterministic rules inside development-only simulator classes. Add the QA recipient in the next valid Flyway migration while leaving every simulation flag disabled by default.

**Tech Stack:** Java 17, Spring Boot 3.2, JUnit 5, AssertJ, Mockito, Flyway, Oracle SQL, Maven, Spotless

**Spec:** `docs/superpowers/specs/2026-09-18-simulation-rules-design.md`

## Global Constraints

- Preserve the existing `ComplianceAssessor` three-argument API for current adapters and tests.
- Match `SANCTIONED_ACME` against recipient names case-insensitively with `Locale.ROOT`.
- A sanctions hit must produce verdict `BLOCK`, risk `HIGH`, reason `SANCTIONS_HIT`, and must stop confirmation before ledger posting.
- Keep simulation synchronous and confined to classes guarded by `fluxpay.development.*-enabled` properties.
- Keep both simulation properties disabled by default and in production-like configuration.
- Preserve `LIMIT_EXCEEDED`, `PROVIDER_TIMEOUT`, `STANDARD_BANK`, and `STANDARD_BANK:N` behavior.
- Use Flyway version `V607`; `V009` is invalid because the current migration head is `V606`.
- Do not change refund, retry, reconciliation, wallet, ledger, frontend, or copilot behavior.
- Use Java 17 and run `spotless:apply` before each commit.

---

### Task 1: Recipient-aware compliance contract and deterministic simulator rules

**Files:**
- Create: `backend/src/main/java/com/fluxpay/common/contracts/ComplianceScreeningInput.java`
- Modify: `backend/src/main/java/com/fluxpay/common/contracts/ComplianceAssessor.java`
- Modify: `backend/src/main/java/com/fluxpay/development/SimulatedComplianceAssessor.java`
- Create: `backend/src/test/java/com/fluxpay/development/SimSimulationTest.java`
- Modify: `backend/src/test/java/com/fluxpay/development/SimulatedComplianceAssessorTest.java`

**Interfaces:**
- Consumes: existing `ComplianceAssessment`, `ComplianceRisk`, `ScreeningVerdict`, `PayoutCmd`, and simulated provider APIs.
- Produces: `ComplianceScreeningInput(UUID userId, String recipientName, BigDecimal amount, String currency)`, `ComplianceAssessor.assess(ComplianceScreeningInput)`, and `ComplianceAssessor.assessDetailed(ComplianceScreeningInput)` for Task 2.

- [ ] **Step 1: Write the failing combined simulation test**

Create `SimSimulationTest.java` with the sanctions assertions and explicit coverage of the two already-supported payout triggers:

```java
package com.fluxpay.development;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.contracts.ComplianceScreeningInput;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimSimulationTest {
  private final SimulatedComplianceAssessor assessor = new SimulatedComplianceAssessor();

  @Test
  void blocklistedRecipientProducesHighRiskSanctionsHit() {
    var input =
        new ComplianceScreeningInput(
            UUID.randomUUID(), "Vendor SANCTIONED_ACME Ltd", new BigDecimal("10.00"), "USD");

    var assessment = assessor.assessDetailed(input);

    assertThat(assessment.verdict()).isEqualTo(ScreeningVerdict.BLOCK);
    assertThat(assessment.risk()).isEqualTo(ComplianceRisk.HIGH);
    assertThat(assessment.reasons()).containsExactly("SANCTIONS_HIT");
    assertThat(assessment.suggestedAction()).contains("Stop payment");
    assertThat(assessor.assess(input)).isEqualTo(ScreeningVerdict.BLOCK);
  }

  @Test
  void sanctionsNameMatchIsCaseInsensitiveAndOrdinaryNamesApprove() {
    assertThat(
            assessor.assess(
                new ComplianceScreeningInput(
                    UUID.randomUUID(), "sanctioned_acme", BigDecimal.TEN, "USD")))
        .isEqualTo(ScreeningVerdict.BLOCK);
    assertThat(
            assessor.assess(
                new ComplianceScreeningInput(
                    UUID.randomUUID(), "Ordinary Recipient", BigDecimal.TEN, "USD")))
        .isEqualTo(ScreeningVerdict.APPROVE);
  }

  @Test
  void payoutSimulatorsExposeLimitAndUncertainTriggers() {
    var local = new SimulatedLocalPartnerProvider().submit(command("LOCAL_PARTNER", "50000.01"));
    var uncertain =
        new SimulatedStandardBankProvider(() -> "STANDARD_BANK")
            .submit(command("STANDARD_BANK", "100.00"));

    assertThat(local.outcome()).isEqualTo(PayoutResult.Outcome.FAILED);
    assertThat(local.errorCode()).isEqualTo("LIMIT_EXCEEDED");
    assertThat(uncertain.outcome()).isEqualTo(PayoutResult.Outcome.UNCERTAIN);
    assertThat(uncertain.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
  }

  private PayoutCmd command(String route, String amount) {
    UUID attempt = UUID.randomUUID();
    return new PayoutCmd(
        "P-001",
        new BigDecimal(amount),
        "USD",
        "KES",
        route,
        BigDecimal.ZERO,
        1,
        new BigDecimal("80"),
        new BigDecimal("8000"),
        attempt,
        "payout:" + attempt);
  }
}
```

- [ ] **Step 2: Run the new test to verify it fails for the missing input contract**

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=SimSimulationTest' test
```

Expected: test compilation fails because `ComplianceScreeningInput` and the input-based assessor methods do not exist.

- [ ] **Step 3: Add the immutable screening input**

Create `ComplianceScreeningInput.java`:

```java
package com.fluxpay.common.contracts;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Immutable facts required to screen one payment and its selected recipient. */
public record ComplianceScreeningInput(
    UUID userId, String recipientName, BigDecimal amount, String currency) {
  public ComplianceScreeningInput {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    if (currency.isBlank()) throw new IllegalArgumentException("currency must not be blank");
    recipientName = recipientName == null ? "" : recipientName;
  }
}
```

- [ ] **Step 4: Extend the compliance port without breaking current adapters**

Add these default methods to `ComplianceAssessor` while keeping the existing abstract method and existing three-argument detailed method unchanged:

```java
default ScreeningVerdict assess(ComplianceScreeningInput input) {
  Objects.requireNonNull(input, "input must not be null");
  return assess(input.userId(), input.amount(), input.currency());
}

default ComplianceAssessment assessDetailed(ComplianceScreeningInput input) {
  Objects.requireNonNull(input, "input must not be null");
  return assessDetailed(input.userId(), input.amount(), input.currency());
}
```

Add `import java.util.Objects;`. Delegating the detailed call to the existing detailed overload preserves the specific risks and reasons produced by `AmountComplianceAssessor`.

- [ ] **Step 5: Implement the development-only sanctions rule**

Replace the always-approve-only body in `SimulatedComplianceAssessor` with:

```java
private static final String SANCTIONS_FRAGMENT = "SANCTIONED_ACME";

@Override
public ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency) {
  return ScreeningVerdict.APPROVE;
}

@Override
public ScreeningVerdict assess(ComplianceScreeningInput input) {
  return assessDetailed(input).verdict();
}

@Override
public ComplianceAssessment assessDetailed(ComplianceScreeningInput input) {
  Objects.requireNonNull(input, "input must not be null");
  if (input.recipientName().toUpperCase(Locale.ROOT).contains(SANCTIONS_FRAGMENT)) {
    return new ComplianceAssessment(
        ScreeningVerdict.BLOCK,
        ComplianceRisk.HIGH,
        List.of("SANCTIONS_HIT"),
        "Stop payment and escalate the sanctions hit for investigation.");
  }
  return new ComplianceAssessment(
      ScreeningVerdict.APPROVE,
      ComplianceRisk.LOW,
      List.of(),
      "Proceed with payment processing.");
}
```

Import `ComplianceAssessment`, `ComplianceScreeningInput`, `ComplianceRisk`, `List`, `Locale`, and `Objects`. Keep the existing `@ConditionalOnProperty` guard exactly as written.

- [ ] **Step 6: Correct the legacy test description and retain the legacy approval contract**

In `SimulatedComplianceAssessorTest`, change the class comment to `Development-only deterministic assessor; never active in the default context.` Keep `approvesAnyConfirmedPayout` unchanged: it proves the legacy three-argument path still approves when no recipient context is available.

- [ ] **Step 7: Format and run all simulator/adapter compatibility tests**

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply spotless:check
.\mvnw.cmd -f backend/pom.xml '-Dtest=SimSimulationTest,SimulatedComplianceAssessorTest,AmountComplianceAssessorTest,UnavailableComplianceAssessorTest,PayoutProviderTest,DevelopmentDefaultsTest' test
```

Expected: both commands exit 0; sanctions, normal approval, provider limit/uncertainty, default-bean selection, and existing assessor behavior all pass.

- [ ] **Step 8: Commit the contract and simulator slice**

```powershell
git add -- backend/src/main/java/com/fluxpay/common/contracts/ComplianceScreeningInput.java backend/src/main/java/com/fluxpay/common/contracts/ComplianceAssessor.java backend/src/main/java/com/fluxpay/development/SimulatedComplianceAssessor.java backend/src/test/java/com/fluxpay/development/SimSimulationTest.java backend/src/test/java/com/fluxpay/development/SimulatedComplianceAssessorTest.java
git commit -m "feat(sim): add recipient-aware sanctions trigger"
```

### Task 2: Carry the locked recipient through payment confirmation

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/service/PaymentConfirmationService.java:146-166,262-310`
- Modify: `backend/src/test/java/com/fluxpay/service/PaymentConfirmationQuoteTest.java:46-118`

**Interfaces:**
- Consumes: `ComplianceScreeningInput` and the two input-based `ComplianceAssessor` methods from Task 1.
- Produces: payment confirmation that screens the locked `Recipient.name()` and preserves the existing approve/review/block state transitions.

- [ ] **Step 1: Update the test fixture to accept an explicit assessor**

In `PaymentConfirmationQuoteTest`, keep `service(Instant)` as the default approving helper, but have it delegate to a new overload:

```java
PaymentConfirmationService service(Instant time) {
  var compliance = mock(ComplianceAssessor.class);
  when(compliance.assess(any(ComplianceScreeningInput.class)))
      .thenReturn(ScreeningVerdict.APPROVE);
  return service(time, compliance);
}

PaymentConfirmationService service(Instant time, ComplianceAssessor compliance) {
  payment.quoted(1, NOW);
  when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
  when(quotes.findByIdAndPaymentId(quote.id(), payment.id())).thenReturn(Optional.of(quote));
  when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(route));
  var recipients = mock(RecipientRepository.class);
  when(recipients.lockOwned(recipient.id(), user)).thenReturn(Optional.of(recipient));
  when(kyc.isVerified(user)).thenReturn(true);
  when(posting.postApprovedPayment(any(), any(), any(), any(), any(), any(), any()))
      .thenReturn(
          new PostingAccounts(payment.sourceWalletId(), UUID.randomUUID(), UUID.randomUUID()));
  return new PaymentConfirmationService(
      payments,
      quotes,
      recipients,
      kyc,
      compliance,
      posting,
      Clock.fixed(time, ZoneOffset.UTC),
      new PaymentOperationService(
          mock(PaymentOperationRepository.class),
          new ObjectMapper().findAndRegisterModules(),
          Clock.systemUTC(),
          mock(org.springframework.transaction.PlatformTransactionManager.class)),
      new PayoutOutboxService(
          mock(OutboxEventRepository.class),
          mock(OutboxDeliveryRepository.class),
          new ObjectMapper().findAndRegisterModules(),
          Clock.fixed(time, ZoneOffset.UTC)),
      new ObjectMapper().findAndRegisterModules(),
      routes,
      complianceCases);
}
```

- [ ] **Step 2: Write failing confirmation tests for recipient propagation and blocking**

Add these tests:

```java
@Test
void confirmationScreensTheLockedRecipientName() {
  var compliance = mock(ComplianceAssessor.class);
  when(compliance.assess(any(ComplianceScreeningInput.class)))
      .thenReturn(ScreeningVerdict.APPROVE);

  service(NOW, compliance)
      .confirm(user, payment.id(), new ConfirmPaymentRequest(quote.id()), "key");

  var input = org.mockito.ArgumentCaptor.forClass(ComplianceScreeningInput.class);
  verify(compliance).assess(input.capture());
  assertThat(input.getValue().userId()).isEqualTo(user);
  assertThat(input.getValue().recipientName()).isEqualTo(recipient.name());
  assertThat(input.getValue().amount()).isEqualByComparingTo(payment.sourceAmount());
  assertThat(input.getValue().currency()).isEqualTo("USD");
}

@Test
void sanctionedRecipientIsRejectedBeforePosting() {
  recipient.update(
      "SANCTIONED_ACME", "acct", "Bank", "KE", "KES", RecipientStatus.ACTIVE, NOW);

  assertThatThrownBy(
          () ->
              service(NOW, new com.fluxpay.development.SimulatedComplianceAssessor())
                  .confirm(user, payment.id(), new ConfirmPaymentRequest(quote.id()), "key"))
      .isInstanceOfSatisfying(
          com.fluxpay.exception.PaymentBlockedException.class,
          error -> assertThat(error.code()).isEqualTo("PAYMENT_BLOCKED"));
  assertThat(payment.status()).isEqualTo(PaymentStatus.REJECTED);
  verifyNoInteractions(posting);
}
```

Also update `reviewConfirmationCreatesACaseBoundToThePaymentReviewReference` to stub `assess(ComplianceScreeningInput)` and `assessDetailed(ComplianceScreeningInput)` rather than the three-argument overloads.

- [ ] **Step 3: Run the confirmation test to verify recipient propagation fails**

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=PaymentConfirmationQuoteTest' test
```

Expected: FAIL because `PaymentConfirmationService` still calls the legacy three-argument method, so the input captor sees no invocation and the real simulator never receives `recipient.name()`.

- [ ] **Step 4: Build and reuse one screening input in confirmation**

Import `ComplianceScreeningInput`. Immediately after recipient/KYC validation, create and use the input:

```java
var screeningInput =
    new ComplianceScreeningInput(
        userId, recipient.name(), payment.sourceAmount(), payment.sourceCurrency());
ScreeningVerdict verdict = assessWithTimeout(screeningInput);
```

In the review branch, call:

```java
ComplianceAssessment assessment = assessDetailedWithTimeout(screeningInput);
```

Replace the two timeout helpers with input-based signatures:

```java
private ScreeningVerdict assessWithTimeout(ComplianceScreeningInput input) {
  try {
    return CompletableFuture.supplyAsync(() -> compliance.assess(input))
        .get(3, TimeUnit.SECONDS);
  } catch (TimeoutException e) {
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "Compliance assessment timed out.");
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "Compliance assessment was interrupted.");
  } catch (java.util.concurrent.ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof BusinessException businessException) throw businessException;
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "Compliance assessment failed.");
  }
}

private ComplianceAssessment assessDetailedWithTimeout(ComplianceScreeningInput input) {
  try {
    return CompletableFuture.supplyAsync(() -> compliance.assessDetailed(input))
        .get(3, TimeUnit.SECONDS);
  } catch (TimeoutException e) {
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "Compliance assessment timed out.");
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "Compliance assessment was interrupted.");
  } catch (java.util.concurrent.ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof BusinessException businessException) throw businessException;
    throw new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMPLIANCE_UNAVAILABLE",
        "Compliance assessment failed.");
  }
}
```

- [ ] **Step 5: Format and run confirmation plus compliance regressions**

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply spotless:check
.\mvnw.cmd -f backend/pom.xml '-Dtest=PaymentConfirmationQuoteTest,PaymentAccountingTest,SimSimulationTest,AmountComplianceAssessorTest,UnavailableComplianceAssessorTest' test
```

Expected: both commands exit 0; the locked name is propagated, sanctions reject without posting, review facts remain specific, and unavailable compliance still returns 503.

- [ ] **Step 6: Commit the confirmation integration slice**

```powershell
git add -- backend/src/main/java/com/fluxpay/service/PaymentConfirmationService.java backend/src/test/java/com/fluxpay/service/PaymentConfirmationQuoteTest.java
git commit -m "feat(compliance): screen selected recipient during confirmation"
```

### Task 3: Synthetic sanctions recipient and completion verification

**Files:**
- Create: `backend/src/main/resources/db/migration/V607__simulation_seed.sql`
- Modify: `backend/src/test/java/com/fluxpay/repository/SeedMigrationContractTest.java`
- Modify: `backend/src/test/java/com/fluxpay/repository/FreshBaselineOracleTest.java`
- Modify: `backend/src/test/java/com/fluxpay/repository/PayoutReservationMigrationTest.java`

**Interfaces:**
- Consumes: the fixture user `00000000-0000-0000-0000-000000005A01` created by `V603__m5_seed_data.sql` and the `recipients` schema from `V003__routing_payments_and_quotes.sql`.
- Produces: synthetic recipient `00000000-0000-0000-0000-000000005C04`, named `SANCTIONED_ACME`, active and profile-complete for manual QA.

- [ ] **Step 1: Write the failing migration resource contract**

Extract the existing resource-reading code in `SeedMigrationContractTest` into this helper:

```java
private String resource(String name) throws IOException {
  try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
    assertThat(input).as(name).isNotNull();
    return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
  }
}
```

Use it in the existing tests, then add:

```java
@Test
void seedsAnActiveSyntheticSanctionsRecipientWithoutEnablingSimulation() throws IOException {
  String sql = resource("V607__simulation_seed.sql");

  assertThat(sql)
      .contains("INSERT INTO recipients")
      .contains("00000000000000000000000000005C04")
      .contains("00000000000000000000000000005A01")
      .contains("'SANCTIONED_ACME'")
      .contains("'ACTIVE'")
      .contains("'USD'");
  assertThat(sql)
      .doesNotContain("simulated-compliance-enabled=true")
      .doesNotContain("simulated-payouts-enabled=true");
}
```

- [ ] **Step 2: Add the isolated-Oracle seed assertion**

Add this environment-gated test to `FreshBaselineOracleTest`:

```java
@Test
void freshMigrationIncludesSyntheticSanctionsRecipient() throws Exception {
  migrateIsolatedSchema();
  try (Connection connection = connect();
      var statement = connection.prepareStatement(
          "SELECT name, status, profile_complete, currency FROM recipients "
              + "WHERE id=HEXTORAW('00000000000000000000000000005C04')");
      ResultSet result = statement.executeQuery()) {
    assertEquals(true, result.next());
    assertEquals("SANCTIONED_ACME", result.getString("name"));
    assertEquals("ACTIVE", result.getString("status"));
    assertEquals(1, result.getInt("profile_complete"));
    assertEquals("USD", result.getString("currency"));
  }
}
```

- [ ] **Step 3: Run the portable seed contract to verify it fails**

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=SeedMigrationContractTest' test
```

Expected: FAIL because `V607__simulation_seed.sql` is not present.

- [ ] **Step 4: Add the next ordered Flyway migration**

Create `V607__simulation_seed.sql`:

```sql
-- Synthetic manual-QA recipient. Simulation remains disabled unless the
-- development-only compliance property is explicitly enabled.
INSERT INTO recipients (
  id, user_id, name, account_ref, bank_name, country, currency,
  status, profile_complete, version, created_at, updated_at
) VALUES (
  HEXTORAW('00000000000000000000000000005C04'),
  HEXTORAW('00000000000000000000000000005A01'),
  'SANCTIONED_ACME',
  'SIM-SANCTIONS-0001',
  'FluxPay Simulation Bank',
  'US',
  'USD',
  'ACTIVE',
  1,
  0,
  SYSTIMESTAMP,
  SYSTIMESTAMP
);

COMMIT;
```

- [ ] **Step 5: Format and run the portable migration and simulation tests**

Before running the migration tests, keep `PayoutReservationMigrationTest` scoped to the upgrade it owns. Add `.target("606")` after `.baselineVersion("605")` in its Flyway builder:

```java
var upgrade =
    org.flywaydb.core.Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("605")
        .target("606")
        .load()
        .migrate();
```

This fixture creates only `payment_operations` and must continue to prove the isolated `V605 → V606` snapshot upgrade; the clean-schema Oracle test owns execution of `V607`.

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply spotless:check
.\mvnw.cmd -f backend/pom.xml '-Dtest=SeedMigrationContractTest,MigrationContractTest,PayoutReservationMigrationTest,SimSimulationTest,PaymentConfirmationQuoteTest,PayoutProviderTest,DevelopmentDefaultsTest' test
```

Expected: both commands exit 0; the migration resource, ordering upgrade, simulation rules, confirmation flow, provider behavior, and opt-in bean selection pass.

- [ ] **Step 6: Run the complete backend unit suite**

Run:

```powershell
.\mvnw.cmd -f backend/pom.xml test
```

Expected: `BUILD SUCCESS` with zero failures and zero errors.

- [ ] **Step 7: Run the required Oracle/Kafka acceptance gate when its dedicated environment is active**

First inspect only whether activation and required variable names are present; do not print their values:

```powershell
@('ORACLE_TESTS_ACTIVE','ORACLE_TEST_JDBC_URL','ORACLE_TEST_USERNAME','ORACLE_TEST_PASSWORD','KAFKA_BOOTSTRAP_SERVERS') | ForEach-Object { "$_=$(-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)))" }
```

When all five report `True` and `ORACLE_TEST_USERNAME` is the dedicated `FLUXPAY_TEST` schema, run:

```powershell
.\mvnw.cmd -f backend/pom.xml -Pintegration verify
```

Expected: `FreshBaselineOracleTest`, `BackendAcceptanceIT`, and the remaining integration tests run rather than skip, and Maven exits 0. If the dedicated services or variables are unavailable, record acceptance as not run; do not describe a skipped suite as passing acceptance.

- [ ] **Step 8: Commit the migration slice**

```powershell
git add -- backend/src/main/resources/db/migration/V607__simulation_seed.sql backend/src/test/java/com/fluxpay/repository/SeedMigrationContractTest.java backend/src/test/java/com/fluxpay/repository/FreshBaselineOracleTest.java backend/src/test/java/com/fluxpay/repository/PayoutReservationMigrationTest.java
git commit -m "feat(sim): seed sanctions QA recipient"
```

- [ ] **Step 9: Audit the original trust-backend task against current evidence**

Run:

```powershell
git status --short
rg -n "SANCTIONED_ACME|SANCTIONS_HIT|LIMIT_EXCEEDED|PROVIDER_TIMEOUT|V607__simulation_seed" backend/src/main backend/src/test
```

Expected: the worktree is clean; every Task 3 trigger and its test evidence are present. Combine this evidence with the already-green Task 1–2 security/refund/retry/reconciliation suite before claiming the full trust-backend plan complete.
