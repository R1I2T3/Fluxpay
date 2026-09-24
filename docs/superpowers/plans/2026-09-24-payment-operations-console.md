# Payment Operations Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a read-only admin operations console and deterministic local failure simulation that visibly demonstrates payment confirmation, outbox/Kafka delivery, payout retry, completion, retry exhaustion, and automatic refund.

**Architecture:** Keep the existing transactional outbox, Kafka consumers, payment operation idempotency, and bounded recovery workflow unchanged. Add validated development-only route policies and an admin read model over existing tables, then render the correlated evidence in the active JET/Knockout frontend. The demo reuses `BANK_STANDARD` and `BANK_EXPRESS`; no provider, route, or database migration is created.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Spring Kafka, Jackson, Oracle, Python 3 `unittest`/`urllib`, TypeScript 5.3, Knockout, Oracle JET, Node `node:test`, CSS.

**Spec:** `docs/superpowers/specs/2026-09-24-payment-operations-console-design.md`

## Global Constraints

- Simulation is disabled by default and must require `FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true`.
- `BANK_STANDARD` fails the first two distinct attempts and then completes.
- `BANK_EXPRESS` fails the first six distinct attempts; the initial attempt plus five automatic retries exhaust recovery and trigger refund.
- The default recovery delay is exactly `120` seconds; the local demo may set it to `5` seconds.
- Terminal `payout.refund` is due immediately after the final failed attempt.
- Route-code matching is case-sensitive and uses `TransferRouteSnapshot.code()`.
- Repeating the same `payout:{attemptId}` provider key must replay the cached result and must not consume another simulated failure.
- `SIMULATE_FAILURE=BANK_NETWORK[:n]` remains available for uncertain-timeout/reconciliation demonstrations; a matching route-specific definitive policy takes precedence.
- The admin operations page is read-only and available only to `ADMIN` users.
- A `SENT` outbox row proves Kafka publication only; a timeline row proves timeline-consumer persistence.
- No new Flyway migration, provider, route, customer-facing Kafka UI, SSE, WebSocket, or DLT console is permitted.
- Ordinary seed runs remain insert-only and do not change active flags.
- Demo route activation changes only the selected inactive seeded provider/routes, increments their JPA versions, and leaves unrelated records untouched.
- The customer frontend must not call the forbidden `POST /api/payments/{id}/refund` endpoint.
- Payload and error values must be rendered with text bindings, never `html:`.
- Do not edit generated `frontend/fluxpay-ui/web-dev` output.
- Every task uses TDD, runs its targeted tests, and commits only its own files.

---

## File Structure

### Backend configuration and simulation

- Create `backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationProperties.java` — validated environment-bound development policies.
- Create `backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationConfiguration.java` — registers the properties bean.
- Modify `backend/src/main/resources/application.yml` — maps `FLUXPAY_DEVELOPMENT_*` variables.
- Modify `backend/src/main/java/com/fluxpay/development/SimulatedBankNetworkRail.java` — route-specific definitive failure policy with legacy uncertain fallback.
- Modify `backend/src/main/java/com/fluxpay/messaging/PayoutRetryConsumer.java` — configurable nonterminal recovery delay.
- Modify `backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java` — ordered attempt reads and configured-policy attempt counts.

### Admin operations read model

- Create `backend/src/main/java/com/fluxpay/dto/PaymentOperationsResponse.java` — immutable API contract.
- Create `backend/src/main/java/com/fluxpay/service/PaymentOperationsService.java` — batch read model and recovery-state derivation.
- Create `backend/src/main/java/com/fluxpay/controller/PaymentOperationsAdminController.java` — admin-only GET endpoint.
- Modify `backend/src/main/java/com/fluxpay/beans/OutboxDelivery.java` — expose existing `sentAt` and `lastError` fields.
- Modify `PaymentOperationRepository`, `PayoutAttemptRepository`, `LedgerJournalRepository`, and `LedgerEntryRepository` — deterministic read methods.
- Modify `backend/src/main/java/com/fluxpay/web/advice/PayoutApiExceptionHandler.java` — register the new controller for existing payout error mappings.

### Local scripts and topics

- Modify `scripts/seed-local.py` — validate and activate selected seeded bank routes.
- Modify `scripts/start-infra.py` — provision `payout.retry` and `payout.refund`.
- Modify `scripts/reset-local-db.py` — reset/recreate both recovery topics.
- Create `scripts/demo-payment-operations.py` — create the two real payments through public APIs.
- Modify `.env.example` — document the safe, disabled demo block.

### Active frontend

- Modify `frontend/fluxpay-ui/src/ts/services/flux-api.ts` — typed admin operations API and customer-refund removal.
- Modify `frontend/fluxpay-ui/src/ts/appController.ts` — add `admin-payment-operations` under Operations.
- Create `frontend/fluxpay-ui/src/ts/viewModels/admin-payment-operations.ts` — lookup, polling, correlation, grouping, and formatting.
- Create `frontend/fluxpay-ui/src/ts/views/admin-payment-operations.html` — accessible read-only evidence UI.
- Modify `frontend/fluxpay-ui/src/css/admin-console.css` — scoped lifecycle-flow and evidence styles.
- Modify `frontend/fluxpay-ui/src/ts/views/tracking.html` — replace refund with support navigation.
- Modify `frontend/fluxpay-ui/src/ts/services/page.ts` — remove the customer refund action branch.
- Modify `frontend/fluxpay-ui/src/ts/viewModels/payments-new.ts` — remove copy promising a customer refund.

### Tests and documentation

- Create/modify backend tests listed in Tasks 1–6.
- Create/modify Python tests listed in Tasks 7 and 11.
- Create/modify frontend tests listed in Tasks 8–10.
- Modify `README.md` — configuration, setup prerequisites, presenter runbook, and verification commands.

---

### Task 1: Bind and validate development simulation properties

**Files:**
- Create: `backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationProperties.java`
- Create: `backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationConfiguration.java`
- Create: `backend/src/test/java/com/fluxpay/config/DevelopmentPayoutSimulationPropertiesTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.env.example`

**Interfaces:**
- Consumes: Spring Boot environment properties under `fluxpay.development`.
- Produces: `DevelopmentPayoutSimulationProperties.failurePolicyFor(String): Optional<RouteFailurePolicy>` and `recoveryDelaySeconds(): int`.

- [ ] **Step 1: Write failing property and binding tests**

Create `DevelopmentPayoutSimulationPropertiesTest.java` with these concrete cases:

```java
package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevelopmentPayoutSimulationPropertiesTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(DevelopmentPayoutSimulationConfiguration.class);

  @Test
  void defaultsAreSafeAndRecoveryDelayIs120Seconds() {
    runner.run(
        context -> {
          var properties = context.getBean(DevelopmentPayoutSimulationProperties.class);
          assertThat(properties.simulatedPayoutsEnabled()).isFalse();
          assertThat(properties.retrySuccessFailureAttempts()).isZero();
          assertThat(properties.refundFailureAttempts()).isZero();
          assertThat(properties.recoveryDelaySeconds()).isEqualTo(120);
        });
  }

  @Test
  void bindsTwoCaseSensitiveRoutePolicies() {
    runner
        .withPropertyValues(
            "fluxpay.development.simulated-payouts-enabled=true",
            "fluxpay.development.retry-success-route-code= BANK_STANDARD ",
            "fluxpay.development.retry-success-failure-attempts=2",
            "fluxpay.development.refund-route-code=BANK_EXPRESS",
            "fluxpay.development.refund-failure-attempts=6",
            "fluxpay.development.recovery-delay-seconds=5")
        .run(
            context -> {
              var properties = context.getBean(DevelopmentPayoutSimulationProperties.class);
              assertThat(properties.failurePolicyFor("BANK_STANDARD"))
                  .contains(
                      new DevelopmentPayoutSimulationProperties.RouteFailurePolicy(
                          "BANK_STANDARD", 2));
              assertThat(properties.failurePolicyFor("bank_standard")).isEmpty();
              assertThat(properties.failurePolicyFor("BANK_EXPRESS"))
                  .contains(
                      new DevelopmentPayoutSimulationProperties.RouteFailurePolicy(
                          "BANK_EXPRESS", 6));
              assertThat(properties.recoveryDelaySeconds()).isEqualTo(5);
            });
  }

  @Test
  void rejectsUnsafeCrossFieldConfiguration() {
    assertThatThrownBy(
            () ->
                new DevelopmentPayoutSimulationProperties(
                    false, "BANK_STANDARD", 2, null, 0, 120))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("simulated-payouts-enabled");
    assertThatThrownBy(
            () ->
                new DevelopmentPayoutSimulationProperties(
                    true, null, -1, null, 0, 120))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("failure attempts");
    assertThatThrownBy(
            () -> new DevelopmentPayoutSimulationProperties(true, null, 0, null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recovery delay");
  }
}
```

- [ ] **Step 2: Run the test and confirm the missing types fail compilation**

Run:

```bash
./mvnw -f backend/pom.xml -Dtest=DevelopmentPayoutSimulationPropertiesTest test
```

Expected: compilation failure because the two configuration classes do not exist.

- [ ] **Step 3: Implement the validated properties record**

Create `DevelopmentPayoutSimulationProperties.java`:

```java
package com.fluxpay.config;

import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fluxpay.development")
public record DevelopmentPayoutSimulationProperties(
    @DefaultValue("false") boolean simulatedPayoutsEnabled,
    String retrySuccessRouteCode,
    @DefaultValue("0") int retrySuccessFailureAttempts,
    String refundRouteCode,
    @DefaultValue("0") int refundFailureAttempts,
    @DefaultValue("120") int recoveryDelaySeconds) {

  public DevelopmentPayoutSimulationProperties {
    retrySuccessRouteCode = normalizedRoute(retrySuccessRouteCode);
    refundRouteCode = normalizedRoute(refundRouteCode);
    if (retrySuccessFailureAttempts < 0 || refundFailureAttempts < 0)
      throw new IllegalArgumentException("Failure attempts must not be negative");
    if (recoveryDelaySeconds < 1)
      throw new IllegalArgumentException("Recovery delay must be at least one second");
    if (!simulatedPayoutsEnabled
        && (retrySuccessRouteCode != null
            || refundRouteCode != null
            || recoveryDelaySeconds != 120))
      throw new IllegalArgumentException(
          "Development payout policies require simulated-payouts-enabled=true");
    if (retrySuccessRouteCode != null
        && retrySuccessRouteCode.equals(refundRouteCode))
      throw new IllegalArgumentException("Demo failure route codes must be distinct");
  }

  public Optional<RouteFailurePolicy> failurePolicyFor(String routeCode) {
    if (routeCode == null) return Optional.empty();
    if (retrySuccessRouteCode != null
        && retrySuccessRouteCode.equals(routeCode)
        && retrySuccessFailureAttempts > 0)
      return Optional.of(
          new RouteFailurePolicy(routeCode, retrySuccessFailureAttempts));
    if (refundRouteCode != null
        && refundRouteCode.equals(routeCode)
        && refundFailureAttempts > 0)
      return Optional.of(new RouteFailurePolicy(routeCode, refundFailureAttempts));
    return Optional.empty();
  }

  private static String normalizedRoute(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  public record RouteFailurePolicy(String routeCode, int failureAttempts) {}
}
```

Create `DevelopmentPayoutSimulationConfiguration.java`:

```java
package com.fluxpay.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DevelopmentPayoutSimulationProperties.class)
public class DevelopmentPayoutSimulationConfiguration {}
```

- [ ] **Step 4: Map the safe environment defaults**

Append these entries under the existing `fluxpay.development` block in `application.yml`:

```yaml
  retry-success-route-code: ${FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE:}
  retry-success-failure-attempts: ${FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS:0}
  refund-route-code: ${FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE:}
  refund-failure-attempts: ${FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS:0}
  recovery-delay-seconds: ${FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS:120}
```

Add the approved commented demo block immediately after the existing simulation switch in `.env.example`; keep the active assignment `false`.

- [ ] **Step 5: Run focused tests and formatting**

```bash
./mvnw -f backend/pom.xml -Dtest=DevelopmentPayoutSimulationPropertiesTest test
./mvnw -f backend/pom.xml spotless:check
```

Expected: both commands succeed.

- [ ] **Step 6: Commit**

```bash
git add .env.example backend/src/main/resources/application.yml \
  backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationProperties.java \
  backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationConfiguration.java \
  backend/src/test/java/com/fluxpay/config/DevelopmentPayoutSimulationPropertiesTest.java
git commit -m "feat: bind payout demo simulation policy"
```

---

### Task 2: Fail only configured seeded bank routes

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/development/SimulatedBankNetworkRail.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java`
- Modify: `backend/src/test/java/com/fluxpay/development/TransferRailSimulationTest.java`

**Interfaces:**
- Consumes: `DevelopmentPayoutSimulationProperties.failurePolicyFor(String)`.
- Produces: route-specific definitive `TransferRailResult.failed(...)` while preserving existing constructors and legacy uncertain behavior.

- [ ] **Step 1: Add failing route-policy tests**

Extend the test command helper to accept a route code and route UUID, then add:

```java
@Test
void configuredRouteFailsDefinitiveCountThenCompletes() {
  var attempts = mock(PayoutAttemptRepository.class);
  var properties =
      new DevelopmentPayoutSimulationProperties(true, "BANK_STANDARD", 2, null, 0, 120);
  var rail = new SimulatedBankNetworkRail(properties, attempts, () -> null);
  var routeId = UUID.randomUUID();
  when(attempts.countByPaymentIdAndRouteId(anyString(), eq(routeId)))
      .thenReturn(1L, 2L, 3L);

  var first = rail.execute(command(UUID.randomUUID(), "BANK_STANDARD", routeId, 1));
  var second = rail.execute(command(UUID.randomUUID(), "BANK_STANDARD", routeId, 2));
  var third = rail.execute(command(UUID.randomUUID(), "BANK_STANDARD", routeId, 3));

  assertThat(first.outcome()).isEqualTo(TransferRailResult.Outcome.FAILED);
  assertThat(second.outcome()).isEqualTo(TransferRailResult.Outcome.FAILED);
  assertThat(third.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
  assertThat(first.errorCode()).isEqualTo("SIMULATED_PROVIDER_FAILURE");
  assertThat(first.errorMessage()).contains("BANK_STANDARD");
  assertThat(first.providerFee()).isEqualByComparingTo("5.00");
}

@Test
void configuredDefinitivePolicyPrecedesLegacyUncertainProbe() {
  var attempts = mock(PayoutAttemptRepository.class);
  var routeId = UUID.randomUUID();
  when(attempts.countByPaymentIdAndRouteId(anyString(), eq(routeId))).thenReturn(1L);
  var properties =
      new DevelopmentPayoutSimulationProperties(true, "BANK_EXPRESS", 6, null, 0, 5);
  var rail = new SimulatedBankNetworkRail(properties, attempts, () -> "BANK_NETWORK");

  var result = rail.execute(command(UUID.randomUUID(), "BANK_EXPRESS", routeId, 1));

  assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.FAILED);
}

@Test
void sameProviderKeyReplaysWithoutConsumingAnotherFailure() {
  var attempts = mock(PayoutAttemptRepository.class);
  var routeId = UUID.randomUUID();
  when(attempts.countByPaymentIdAndRouteId(anyString(), eq(routeId))).thenReturn(1L);
  var rail =
      new SimulatedBankNetworkRail(
          new DevelopmentPayoutSimulationProperties(true, "BANK_STANDARD", 2, null, 0, 120),
          attempts,
          () -> null);
  var command = command(UUID.randomUUID(), "BANK_STANDARD", routeId, 1);

  var first = rail.execute(command);
  var replay = rail.execute(command);

  assertThat(replay).isEqualTo(first);
  verify(attempts, times(1)).countByPaymentIdAndRouteId(anyString(), eq(routeId));
}

@Test
void nonmatchingBankRouteSucceeds() {
  var attempts = mock(PayoutAttemptRepository.class);
  var rail =
      new SimulatedBankNetworkRail(
          new DevelopmentPayoutSimulationProperties(true, "BANK_STANDARD", 2, null, 0, 120),
          attempts,
          () -> null);

  var result = rail.execute(command(UUID.randomUUID(), "BANK_OTHER", UUID.randomUUID(), 1));

  assertThat(result.outcome()).isEqualTo(TransferRailResult.Outcome.COMPLETED);
  verifyNoInteractions(attempts);
}
```

Import `PayoutAttemptRepository`, `DevelopmentPayoutSimulationProperties`, and the existing Mockito static helpers.

- [ ] **Step 2: Run the simulation tests and confirm constructor/count failures**

```bash
./mvnw -f backend/pom.xml -Dtest=TransferRailSimulationTest,SimSimulationTest test
```

Expected: compilation failure for the new constructor and repository method.

- [ ] **Step 3: Add the repository count method**

```java
long countByPaymentIdAndRouteId(String paymentId, UUID routeId);
```

- [ ] **Step 4: Add route-policy-aware rail execution**

Add these fields and constructors while retaining the existing one-argument and no-argument source compatibility:

```java
private final DevelopmentPayoutSimulationProperties properties;
private final PayoutAttemptRepository attempts;
private final Supplier<String> failureProbe;

@org.springframework.beans.factory.annotation.Autowired
public SimulatedBankNetworkRail(
    DevelopmentPayoutSimulationProperties properties,
    PayoutAttemptRepository attempts) {
  this(properties, attempts, () -> System.getenv("SIMULATE_FAILURE"));
}

public SimulatedBankNetworkRail(
    DevelopmentPayoutSimulationProperties properties,
    PayoutAttemptRepository attempts,
    Supplier<String> failureProbe) {
  this.properties = properties;
  this.attempts = attempts;
  this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe must not be null");
}

public SimulatedBankNetworkRail(Supplier<String> failureProbe) {
  this(null, null, failureProbe);
}

public SimulatedBankNetworkRail() {
  this(() -> System.getenv("SIMULATE_FAILURE"));
}
```

Move the existing `failureProbe` field into this constructor set. The policy branch in `deliver(TransferRailCommand command)` must be:

```java
private TransferRailResult deliver(TransferRailCommand command) {
  if (properties != null) {
    var policy = properties.failurePolicyFor(command.route().code());
    if (policy.isPresent()) {
      long attemptCount =
          attempts.countByPaymentIdAndRouteId(
              command.transferId().toString(), command.route().id());
      if (attemptCount <= policy.get().failureAttempts()) {
        return TransferRailResult.failed(
            "SIMULATED_PROVIDER_FAILURE",
            "Simulated definitive failure for " + command.route().code(),
            command.customerFee());
      }
      return TransferRailResult.completed(
          "BANK-" + command.attemptId(), command.customerFee());
    }
  }
  return legacyDelivery(command);
}
```

Keep `execute(...)` unchanged so the idempotency-key cache is checked first:

```java
return outcomes.computeIfAbsent(command.idempotencyKey(), key -> deliver(command));
```

Move the existing probe behavior into:

```java
private TransferRailResult legacyDelivery(TransferRailCommand command) {
  String probe = failureProbe.get();
  if (probe == null)
    return TransferRailResult.completed("BANK-" + command.attemptId(), command.customerFee());
  if ("BANK_NETWORK".equals(probe))
    return TransferRailResult.uncertain(
        "PROVIDER_TIMEOUT", "Simulated bank timeout", command.customerFee());
  if (probe.startsWith("BANK_NETWORK:")) {
    try {
      int failTimes = Integer.parseInt(probe.substring("BANK_NETWORK:".length()));
      int seen =
          failureCounts
              .computeIfAbsent(
                  command.transferId(),
                  key -> new java.util.concurrent.atomic.AtomicInteger())
              .incrementAndGet();
      if (seen <= failTimes)
        return TransferRailResult.uncertain(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", command.customerFee());
    } catch (NumberFormatException ignored) {
      return TransferRailResult.uncertain(
          "PROVIDER_TIMEOUT", "Simulated bank timeout", command.customerFee());
    }
  }
  return TransferRailResult.completed("BANK-" + command.attemptId(), command.customerFee());
}
```

The legacy constructors use a no-policy rail and retain the existing `BANK_NETWORK[:n]` behavior.

- [ ] **Step 5: Run tests and formatting**

```bash
./mvnw -f backend/pom.xml -Dtest=TransferRailSimulationTest,SimSimulationTest test
./mvnw -f backend/pom.xml spotless:check
```

Expected: all simulation tests pass, including existing uncertain-timeout tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fluxpay/development/SimulatedBankNetworkRail.java \
  backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java \
  backend/src/test/java/com/fluxpay/development/TransferRailSimulationTest.java
git commit -m "feat: simulate configured bank route failures"
```

---

### Task 3: Make only the demo recovery interval configurable

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/messaging/PayoutRetryConsumer.java`
- Modify: `backend/src/test/java/com/fluxpay/service/PayoutLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: `DevelopmentPayoutSimulationProperties.recoveryDelaySeconds()`.
- Produces: five-second `payout.retry.nextRun` in demo mode; immediate `payout.refund.nextRun`; default unchanged.

- [ ] **Step 1: Add a failing configured-delay assertion**

Update the integration-test `recoveryConsumer()` helper to accept an integer delay, defaulting to `120`, and instantiate properties with simulation enabled when the delay differs. Add this assertion to the existing automatic-retry scenario:

```java
assertThat(retryCommand.payload())
    .containsEntry("nextRun", failedAt.plusSeconds(5).toString());
```

After the fifth automated retry fails, assert:

```java
assertThat(refundCommand.payload())
    .containsEntry("nextRun", finalFailureAt.toString());
```

- [ ] **Step 2: Run the focused integration test and confirm hard-coded delay failure**

```bash
./mvnw -f backend/pom.xml -Dtest=PayoutLifecycleIntegrationTest test
```

Expected: the five-second assertion fails because the code still adds `120` seconds.

- [ ] **Step 3: Inject and use the validated delay**

Add `DevelopmentPayoutSimulationProperties development` as the final constructor argument and store:

```java
private final int recoveryDelaySeconds;
```

Initialize it with `development.recoveryDelaySeconds()`. Replace the `nextRun` calculation with:

```java
long nextRunDelaySeconds = terminal ? 0 : recoveryDelaySeconds;
var nextRun = latest.completedAt().plusSeconds(nextRunDelaySeconds);
```

- [ ] **Step 4: Run the lifecycle test and formatting**

```bash
./mvnw -f backend/pom.xml -Dtest=PayoutLifecycleIntegrationTest test
./mvnw -f backend/pom.xml spotless:check
```

Expected: default two-minute, five-second demo, and immediate terminal-refund assertions all pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/messaging/PayoutRetryConsumer.java \
  backend/src/test/java/com/fluxpay/service/PayoutLifecycleIntegrationTest.java
git commit -m "feat: configure demo payout retry delay"
```

---

### Task 4: Define the admin operations read-model contract

**Files:**
- Create: `backend/src/main/java/com/fluxpay/dto/PaymentOperationsResponse.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/OutboxDelivery.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/PaymentOperationRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/LedgerJournalRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/LedgerEntryRepository.java`
- Test: `backend/src/test/java/com/fluxpay/dto/PaymentOperationsResponseTest.java`

**Interfaces:**
- Produces: `PaymentOperationsService.get(String): PaymentOperationsResponse`.
- Consumes: no new persisted columns or migrations.

- [ ] **Step 1: Write DTO immutability and null-normalization tests**

```java
import com.fluxpay.dto.PaymentOperationsResponse.RecoveryDecision;

class PaymentOperationsResponseTest {
  @Test
  void nullCollectionsAndMapsBecomeImmutableEmptyValues() {
    var response =
        new PaymentOperationsResponse(
            new PaymentOperationsResponse.Payment(
                UUID.randomUUID(),
                PaymentStatus.PROCESSING,
                null,
                0,
                Instant.EPOCH,
                Instant.EPOCH),
            null,
            null,
            null,
            null,
            null,
            new PaymentOperationsResponse.Recovery(0, RecoveryDecision.NOT_REQUIRED, null));

    assertThat(response.attempts()).isEmpty();
    assertThat(response.outboxEvents()).isEmpty();
    assertThat(response.timelineEvents()).isEmpty();
    assertThat(response.operations()).isEmpty();
    assertThat(response.ledgerEntries()).isEmpty();
    assertThatThrownBy(() -> response.attempts().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
```

- [ ] **Step 2: Run the test and confirm the DTO is missing**

```bash
./mvnw -f backend/pom.xml -Dtest=PaymentOperationsResponseTest test
```

Expected: compilation failure for `PaymentOperationsResponse`.

- [ ] **Step 3: Create the complete immutable DTO**

```java
package com.fluxpay.dto;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PaymentOperationsResponse(
    Payment payment,
    List<Attempt> attempts,
    List<OutboxEvent> outboxEvents,
    List<TimelineEvent> timelineEvents,
    List<Operation> operations,
    List<LedgerEntry> ledgerEntries,
    Recovery recovery) {

  public PaymentOperationsResponse {
    attempts = immutable(attempts);
    outboxEvents = immutable(outboxEvents);
    timelineEvents = immutable(timelineEvents);
    operations = immutable(operations);
    ledgerEntries = immutable(ledgerEntries);
  }

  public record Payment(
      UUID id,
      PaymentStatus status,
      UUID selectedQuoteId,
      int eventSequence,
      Instant createdAt,
      Instant updatedAt) {}

  public record Attempt(
      UUID id,
      int attemptNumber,
      PayoutAttemptStatus status,
      String routeCode,
      String providerCode,
      String providerReference,
      String errorCode,
      String errorMessage,
      Instant initiatedAt,
      Instant completedAt) {}

  public record OutboxEvent(
      UUID eventId,
      String eventType,
      int aggregateSequence,
      Instant createdAt,
      Map<String, Object> payload,
      Delivery delivery) {
    public OutboxEvent {
      payload = immutableMap(payload);
    }
  }

  public record Delivery(
      String state,
      int attemptCount,
      Instant nextAttemptAt,
      Instant sentAt,
      String lastError) {}

  public record TimelineEvent(
      UUID eventId,
      String eventType,
      String kafkaTopic,
      String correlationId,
      Map<String, Object> payload,
      Instant occurredAt) {
    public TimelineEvent {
      payload = immutableMap(payload);
    }
  }

  public record Operation(
      UUID id,
      PaymentOperation.Namespace namespace,
      String operationType,
      String clientKey,
      String status,
      Integer outcomeStatus,
      Map<String, Object> response,
      Instant createdAt) {
    public Operation {
      response = immutableMap(response);
    }
  }

  public record LedgerEntry(
      UUID id,
      String journalReference,
      String idempotencyKey,
      String entryType,
      String amount,
      String currency,
      String narration,
      Instant createdAt) {}

  public record Recovery(
      long automatedRetryCount,
      RecoveryDecision decision,
      Instant nextRun) {}

  public enum RecoveryDecision {
    NOT_REQUIRED,
    RETRY_SCHEDULED,
    REFUND_SCHEDULED,
    REFUNDED,
    RECONCILIATION_REQUIRED,
    STALE
  }

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static Map<String, Object> immutableMap(Map<String, Object> values) {
    return values == null
        ? Map.of()
        : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
  }
}
```

- [ ] **Step 4: Add deterministic repository and entity accessors**

Add:

```java
// PayoutAttemptRepository
List<PayoutAttempt> findByPaymentIdOrderByAttemptNumberAsc(String paymentId);

// PaymentOperationRepository
List<PaymentOperation> findByPaymentIdOrderByCreatedAtAscIdAsc(UUID paymentId);

// LedgerJournalRepository
List<LedgerJournal> findByJournalReferenceIn(Collection<String> references);

// LedgerEntryRepository
List<LedgerEntry> findByJournalReferenceInOrderByCreatedAtAscIdAsc(
    Collection<String> references);
```

Add to `OutboxDelivery`:

```java
public Instant sentAt() {
  return sentAt;
}

public String lastError() {
  return lastError;
}
```

- [ ] **Step 5: Run DTO, entity, and repository contract tests**

```bash
./mvnw -f backend/pom.xml \
  -Dtest=PaymentOperationsResponseTest,PayoutLifecycleIntegrationTest,OutboxEndToEndTest test
./mvnw -f backend/pom.xml spotless:check
```

Expected: tests pass and no migration is created.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fluxpay/dto/PaymentOperationsResponse.java \
  backend/src/main/java/com/fluxpay/beans/OutboxDelivery.java \
  backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java \
  backend/src/main/java/com/fluxpay/repository/PaymentOperationRepository.java \
  backend/src/main/java/com/fluxpay/repository/LedgerJournalRepository.java \
  backend/src/main/java/com/fluxpay/repository/LedgerEntryRepository.java \
  backend/src/test/java/com/fluxpay/dto/PaymentOperationsResponseTest.java
git commit -m "feat: define payment operations read model"
```

---

### Task 5: Build the correlated admin read model

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/PaymentOperationsService.java`
- Create: `backend/src/test/java/com/fluxpay/service/PaymentOperationsServiceTest.java`

**Interfaces:**
- Consumes: repositories and accessors from Task 4.
- Produces: `PaymentOperationsResponse get(String paymentId)` with no writes.

- [ ] **Step 1: Write failing service tests**

Create tests for these exact outcomes:

```java
import com.fluxpay.dto.PaymentOperationsResponse.RecoveryDecision;
import com.fluxpay.dto.PaymentOperationsResponse.TimelineEvent;

@Test
void rejectsUnknownPaymentId() {
  when(payments.findById(id)).thenReturn(Optional.empty());
  assertThatThrownBy(() -> service.get(id.toString()))
      .isInstanceOf(NoSuchElementException.class);
}

@Test
void rejectsMalformedPaymentIdWithoutCallingRepositories() {
  assertThatThrownBy(() -> service.get("not-a-uuid"))
      .isInstanceOf(NoSuchElementException.class);
  verifyNoInteractions(payments, attempts, deliveries, outboxEvents, timelineEvents, operations);
}

@Test
void correlatesTimelineOnlyByExactEventId() {
  var response = service.get(id.toString());
  assertThat(response.timelineEvents()).extracting(TimelineEvent::eventId)
      .containsExactly(firstEventId, secondEventId);
  verify(outboxEvents, times(1)).findAllById(Set.of(firstEventId, secondEventId));
}

@Test
void sentDeliveryIsNotPresentedAsConsumed() {
  assertThat(service.get(id.toString()).outboxEvents().get(0).delivery().state())
      .isEqualTo("SENT");
  assertThat(response.timelineEvents()).isEmpty();
}

@Test
void derivesRetryAndRefundDecisionsFromPersistedCommands() {
  assertThat(service.get(id.toString()).recovery().decision())
      .isEqualTo(RecoveryDecision.RETRY_SCHEDULED);
  assertThat(refundedService.get(id.toString()).recovery().decision())
      .isEqualTo(RecoveryDecision.REFUNDED);
}

@Test
void derivesReconciliationForCurrentProcessingAttempt() {
  assertThat(service.get(id.toString()).recovery().decision())
      .isEqualTo(RecoveryDecision.RECONCILIATION_REQUIRED);
}
```

Use real `OutboxEvent`, `OutboxDelivery`, `PaymentEvent`, `PaymentOperation`, and `LedgerEntry` objects, mocked repositories, real `ObjectMapper`, and a real `EventEnvelopeCodec`. Verify no `save`, `delete`, or modifying repository method is called.

- [ ] **Step 2: Run the service test and confirm the class is missing**

```bash
./mvnw -f backend/pom.xml -Dtest=PaymentOperationsServiceTest test
```

Expected: compilation failure because `PaymentOperationsService` does not exist.

- [ ] **Step 3: Implement the read service with batch queries**

Create the service with this exact constructor:

```java
public PaymentOperationsService(
    PaymentRepository payments,
    PayoutAttemptRepository attempts,
    OutboxDeliveryRepository deliveries,
    OutboxEventRepository outboxEvents,
    PaymentEventRepository timelineEvents,
    PaymentOperationRepository operations,
    LedgerJournalRepository journals,
    LedgerEntryRepository ledgerEntries,
    TransferRouteRepository routes,
    ObjectMapper objectMapper,
    EventEnvelopeCodec eventCodec) {
  this.payments = payments;
  this.attempts = attempts;
  this.deliveries = deliveries;
  this.outboxEvents = outboxEvents;
  this.timelineEvents = timelineEvents;
  this.operations = operations;
  this.journals = journals;
  this.ledgerEntries = ledgerEntries;
  this.routes = routes;
  this.objectMapper = objectMapper;
  this.eventCodec = eventCodec;
}
```

Annotate the class `@Service` and `@Transactional(readOnly = true)`. The public method must:

```java
@Transactional(readOnly = true)
public PaymentOperationsResponse get(String paymentId) {
  var id = parsePaymentId(paymentId);
  var payment =
      payments.findById(id).orElseThrow(() -> new NoSuchElementException("payment not found"));

  var attemptEntities = attempts.findByPaymentIdOrderByAttemptNumberAsc(id.toString());
  var deliveryEntities = deliveries.findByPaymentIdOrderByAggregateSequenceAsc(id);
  var eventEntities = outboxEvents.findAllById(
      deliveryEntities.stream()
          .map(OutboxDelivery::eventId)
          .collect(java.util.stream.Collectors.toSet()));
  var timelineEntities =
      timelineEvents.findByPaymentIdOrderByOccurredAtAscEventIdAsc(id.toString());
  var operationEntities =
      operations.findByPaymentIdOrderByCreatedAtAscIdAsc(id);
  var journalReferences = ledgerReferences(id);
  journals.findByJournalReferenceIn(journalReferences);
  var ledgerEntities =
      ledgerEntries.findByJournalReferenceInOrderByCreatedAtAscIdAsc(journalReferences);
  var routeCatalogue = routes.findAllByOrderByRouteCodeAsc();

  return assemble(
      payment,
      attemptEntities,
      deliveryEntities,
      eventEntities,
      timelineEntities,
      operationEntities,
      ledgerEntities,
      routeCatalogue);
}
```

Use these exact helpers for malformed input and JSON-object decoding:

```java
private UUID parsePaymentId(String value) {
  try {
    return UUID.fromString(value);
  } catch (IllegalArgumentException exception) {
    throw new NoSuchElementException("payment " + value + " not found");
  }
}

private Map<String, Object> decodeObject(String json) {
  if (json == null || json.isBlank()) return Map.of();
  try {
    return objectMapper.readValue(
        json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
    throw new IllegalStateException("persisted operations JSON is invalid", exception);
  }
}
```

Use this exact ledger-reference set, preserving this order before sorting returned entries by time and ID:

```java
private List<String> ledgerReferences(UUID id) {
  return List.of(
      "payment:" + id,
      "refund:" + id + ":clearing:debit",
      "refund:" + id + ":sender:credit",
      "refund:" + id + ":fee:debit");
}
```

Decode full outbox envelopes with `eventCodec.read(event.payload())` and expose only `envelope.payload()`. Decode timeline and operation JSON with the existing `ObjectMapper` `readerFor(new TypeReference<Map<String,Object>>() {})` pattern. Derive nullable attempt error messages from `payout.failed` payload attempt numbers. Resolve route/provider codes from the batch route catalogue.

Implement recovery precedence exactly:

```java
if (payment.status() == PaymentStatus.REFUNDED
    || hasEvent("payment.refunded")) return REFUNDED;
if (payment.status() == PaymentStatus.PROCESSING
    && latestAttempt.status() == PayoutAttemptStatus.PROCESSING
    && !hasTerminalPayoutFor(latestAttempt.attemptNumber()))
  return RECONCILIATION_REQUIRED;
if (hasStaleRecoveryCommand(latestAttempt.attemptNumber())) return STALE;
if (hasCurrentCommand(EventTopics.PAYOUT_REFUND, latestAttempt.attemptNumber()))
  return REFUND_SCHEDULED;
if (hasCurrentCommand(EventTopics.PAYOUT_RETRY, latestAttempt.attemptNumber()))
  return RETRY_SCHEDULED;
return NOT_REQUIRED;
```

Count `AUTO_RETRY` operations in namespace `INTERNAL`; take `nextRun` from the highest-sequence `payout.retry` or `payout.refund` command.

- [ ] **Step 4: Run service and related tests**

```bash
./mvnw -f backend/pom.xml \
  -Dtest=PaymentOperationsServiceTest,TimelineServiceTest,PayoutLifecycleIntegrationTest test
./mvnw -f backend/pom.xml spotless:check
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/service/PaymentOperationsService.java \
  backend/src/test/java/com/fluxpay/service/PaymentOperationsServiceTest.java
git commit -m "feat: assemble payment operations evidence"
```

---

### Task 6: Expose the read-only admin endpoint

**Files:**
- Create: `backend/src/main/java/com/fluxpay/controller/PaymentOperationsAdminController.java`
- Modify: `backend/src/main/java/com/fluxpay/web/advice/PayoutApiExceptionHandler.java`
- Create: `backend/src/test/java/com/fluxpay/controller/PaymentOperationsAdminControllerMvcTest.java`
- Modify: `backend/src/test/java/com/fluxpay/controller/AuthorizationContractTest.java`

**Interfaces:**
- Produces: `GET /api/admin/payments/{paymentId}/operations`.
- Consumes: `PaymentOperationsService.get(String)`.

- [ ] **Step 1: Write MVC authorization and response tests**

Cover these exact requests:

```java
mockMvc.perform(get("/api/admin/payments/{id}/operations", id)
        .with(jwt().roles("ADMIN")))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.payment.id").value(id.toString()));

mockMvc.perform(get("/api/admin/payments/{id}/operations", id)
        .with(jwt().roles("CUSTOMER")))
    .andExpect(status().isForbidden());

mockMvc.perform(get("/api/admin/payments/{id}/operations", id))
    .andExpect(status().isUnauthorized());

when(operations.get("not-a-uuid")).thenThrow(new NoSuchElementException("payment not found"));
mockMvc.perform(get("/api/admin/payments/not-a-uuid/operations")
        .with(jwt().roles("ADMIN")))
    .andExpect(status().isNotFound());
```

Also assert the controller has no POST, PUT, PATCH, or DELETE mapping.

- [ ] **Step 2: Run the MVC test and confirm the controller is missing**

```bash
./mvnw -f backend/pom.xml \
  -Dtest=PaymentOperationsAdminControllerMvcTest,AuthorizationContractTest test
```

Expected: compilation failure for the new controller.

- [ ] **Step 3: Implement the admin-only controller**

```java
@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentOperationsAdminController {
  private final PaymentOperationsService operations;

  public PaymentOperationsAdminController(PaymentOperationsService operations) {
    this.operations = operations;
  }

  @GetMapping("/{paymentId}/operations")
  public ApiResponse<PaymentOperationsResponse> get(
      @PathVariable String paymentId, HttpServletRequest request) {
    return new ApiResponse<>(
        ControllerSupport.correlationId(request), operations.get(paymentId));
  }
}
```

Add the controller class to `PayoutApiExceptionHandler.assignableTypes`.

- [ ] **Step 4: Run API and security tests**

```bash
./mvnw -f backend/pom.xml \
  -Dtest=PaymentOperationsAdminControllerMvcTest,AuthorizationContractTest,TimelineControllerContractTest test
./mvnw -f backend/pom.xml spotless:check
```

Expected: admin succeeds, customer/anonymous fail, malformed payment returns 404.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/PaymentOperationsAdminController.java \
  backend/src/main/java/com/fluxpay/web/advice/PayoutApiExceptionHandler.java \
  backend/src/test/java/com/fluxpay/controller/PaymentOperationsAdminControllerMvcTest.java \
  backend/src/test/java/com/fluxpay/controller/AuthorizationContractTest.java
git commit -m "feat: expose admin payment operations API"
```

---

### Task 7: Activate configured seeded routes and provision recovery topics

**Files:**
- Modify: `scripts/seed-local.py`
- Modify: `scripts/start-infra.py`
- Modify: `scripts/reset-local-db.py`
- Modify: `tests/test_seed_local.py`
- Modify: `tests/test_scripts.py`
- Modify: `tests/test_reset_local_db.py`

**Interfaces:**
- Consumes: `.env` route policy variables and the existing `ROUTES`/`PROVIDERS` catalogue.
- Produces: `DemoRoutePolicy` validation, conditional active-flag updates, and an explicit eleven-topic inventory.

- [ ] **Step 1: Write failing seed-policy tests**

Add tests that assert:

```python
def test_no_demo_configuration_keeps_seed_insert_only(self):
    self.assertEqual(script.load_demo_route_policies({}), ())
    self.assertFalse(any(
        "UPDATE transfer_providers" in call.args[0]
        or "UPDATE transfer_routes" in call.args[0]
        for call in self.cursor.execute.call_args_list
    ))

def test_configured_bank_routes_activate_only_selected_inactive_records(self):
    environment = {
        "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
        "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
        "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "2",
        "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE": "BANK_EXPRESS",
        "FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS": "6",
    }
    policies = script.load_demo_route_policies(environment)
    script.activate_demo_routes(self.cursor, policies)
    statements = [call.args[0] for call in self.cursor.execute.call_args_list]
    self.assertEqual(sum("UPDATE transfer_providers" in sql for sql in statements), 1)
    self.assertIn("BANK_ALPHA", str(self.cursor.execute.call_args_list))
    self.assertEqual(sum("UPDATE transfer_routes" in sql for sql in statements), 2)
```

Use subtests to reject unknown, duplicate, internal, non-bank, negative, non-integer, and simulation-disabled policies before any database mutation. Add a persisted-state test that archived or rebound records raise `RuntimeError` before updates.

- [ ] **Step 2: Run seed tests and confirm missing policy behavior**

```bash
python -B -m unittest tests.test_seed_local -v
```

Expected: failures for missing `DemoRoutePolicy` and activation helpers.

- [ ] **Step 3: Implement static policy parsing and database activation**

Add:

```python
@dataclass(frozen=True)
class DemoRoutePolicy:
    scenario: str
    route_code: str
    failure_attempts: int
    provider_code: str
```

Implement `load_demo_route_policies(environ)` with exact environment pairs, trimming, integer parsing, case-sensitive catalogue lookup, duplicate rejection, external-account and `BANK_NETWORK` validation, and simulation-enabled validation. A nonblank route variable requires the simulation switch even when its count is zero; a positive count requires a route. Return a policy only when the route is nonblank and the count is greater than zero.

`activate_demo_routes(cursor, policies)` must validate every persisted route/provider before updates. Query each policy with:

```sql
SELECT r.route_code,
       r.provider_id,
       r.destination_type,
       r.active AS route_active,
       r.archived_at AS route_archived_at,
       p.provider_code,
       p.rail_type,
       p.active AS provider_active,
       p.archived_at AS provider_archived_at
  FROM transfer_routes r
  JOIN transfer_providers p ON p.id = r.provider_id
 WHERE r.route_code = :route_code
```

Raise before any update if the row is missing, archived, rebound from the static catalogue, non-external, or non-bank. After every row passes, issue one update per unique provider and one per route:

```sql
UPDATE transfer_providers
   SET active = 1, version = version + 1, updated_at = SYSTIMESTAMP
 WHERE provider_code = :provider_code AND active = 0 AND archived_at IS NULL
```

```sql
UPDATE transfer_routes
   SET active = 1, version = version + 1, updated_at = SYSTIMESTAMP
 WHERE route_code = :route_code AND provider_id = :provider_id
   AND active = 0 AND archived_at IS NULL
```

Call activation after catalogue merges and before the existing commit. Return safe `demoRoutes` metadata and print one line per policy plus `demo-routes=none active-flags-unchanged` when empty.

- [ ] **Step 4: Write and run failing topic-list tests**

Update the script tests to require exactly eleven topics and both recovery topics. Run:

```bash
python -B -m unittest tests.test_scripts tests.test_reset_local_db -v
```

Expected: failures showing nine-topic expectations.

- [ ] **Step 5: Add recovery topics to both explicit inventories**

Insert in this order in both files:

```python
"payout.retry",
"payout.refund",
```

between `payout.failed` and `payout.completed`.

- [ ] **Step 6: Run focused Python tests and formatting**

```bash
python -B -m unittest tests.test_seed_local tests.test_scripts tests.test_reset_local_db -v
python -m ruff check scripts/seed-local.py scripts/start-infra.py scripts/reset-local-db.py \
  tests/test_seed_local.py tests/test_scripts.py tests/test_reset_local_db.py
python -m ruff format --check scripts/seed-local.py scripts/start-infra.py \
  scripts/reset-local-db.py tests/test_seed_local.py tests/test_scripts.py \
  tests/test_reset_local_db.py
```

Expected: all commands succeed.

- [ ] **Step 7: Commit**

```bash
git add scripts/seed-local.py scripts/start-infra.py scripts/reset-local-db.py \
  tests/test_seed_local.py tests/test_scripts.py tests/test_reset_local_db.py
git commit -m "feat: prepare seeded payout demo routes"
```

---

### Task 8: Add the frontend API route and remove the forbidden customer refund action

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/services/flux-api.ts`
- Modify: `frontend/fluxpay-ui/src/ts/appController.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/tracking.html`
- Modify: `frontend/fluxpay-ui/src/ts/services/page.ts`
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/payments-new.ts`
- Create: `frontend/fluxpay-ui/tests/admin-payment-operations.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-navigation.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/wallet-api.test.cjs`

**Interfaces:**
- Produces: `fluxApi.adminPaymentOperations(id: string): Promise<PaymentOperationsResponse>`.
- Removes: all frontend calls to `POST /api/payments/{id}/refund`.

- [ ] **Step 1: Write failing API/navigation/refund tests**

Add assertions that the API source contains:

```js
adminPaymentOperations: (id: string) =>
  request<PaymentOperationsResponse>(
    `/api/admin/payments/${encodeURIComponent(id)}/operations`,
  ),
```

Assert the request contains no body and no idempotency flag. Add static assertions:

```js
assert.match(tracking, /Contact support ↗/);
assert.match(tracking, /data-route="tickets"/);
assert.doesNotMatch(tracking, /askAction\('refund'\)/);
assert.doesNotMatch(page, /action==='refund'/);
assert.doesNotMatch(api, /refund:\s*\(id:\s*string\)/);
```

Update the Operations navigation expectation to include `admin-payment-operations`.

- [ ] **Step 2: Run the focused frontend tests and confirm failures**

```bash
cd frontend/fluxpay-ui
node --test tests/admin-payment-operations.test.cjs \
  tests/admin-navigation.test.cjs tests/wallet-api.test.cjs
```

Expected: missing API method, route, and support-link failures.

- [ ] **Step 3: Add the typed read API**

Add these exact interfaces to `flux-api.ts`:

```ts
export type PaymentOutboxState = 'PENDING' | 'SENDING' | 'SENT';
export type RecoveryDecision =
  | 'NOT_REQUIRED'
  | 'RETRY_SCHEDULED'
  | 'REFUND_SCHEDULED'
  | 'REFUNDED'
  | 'RECONCILIATION_REQUIRED'
  | 'STALE';

export interface PaymentOperationsPayment {
  id: string;
  status: string;
  selectedQuoteId: string | null;
  eventSequence: number;
  createdAt: string;
  updatedAt: string;
}
export interface PaymentOperationsAttempt {
  id: string;
  attemptNumber: number;
  status: 'INITIATED' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  routeCode: string | null;
  providerCode: string | null;
  providerReference: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  initiatedAt: string;
  completedAt: string | null;
}
export interface PaymentOperationsDelivery {
  state: PaymentOutboxState;
  attemptCount: number;
  nextAttemptAt: string;
  sentAt: string | null;
  lastError: string | null;
}
export interface PaymentOperationsOutboxEvent {
  eventId: string;
  eventType: string;
  aggregateSequence: number;
  createdAt: string;
  payload: unknown;
  delivery: PaymentOperationsDelivery;
}
export interface PaymentOperationsTimelineEvent {
  eventId: string;
  eventType: string;
  kafkaTopic: string;
  correlationId: string;
  payload: unknown;
  occurredAt: string;
}
export interface PaymentOperationsOperation {
  id: string;
  namespace: 'PUBLIC' | 'INTERNAL' | string;
  operationType: string;
  clientKey: string;
  status: 'IN_PROGRESS' | 'COMPLETED' | string;
  outcomeStatus: number | null;
  response: unknown;
  createdAt: string;
}
export interface PaymentOperationsLedgerEntry {
  id: string;
  journalReference: string;
  idempotencyKey: string;
  entryType: string;
  amount: string;
  currency: string;
  narration: string;
  createdAt: string;
}
export interface PaymentOperationsRecovery {
  automatedRetryCount: number;
  decision: RecoveryDecision;
  nextRun: string | null;
}
export interface PaymentOperationsResponse {
  payment: PaymentOperationsPayment;
  attempts: PaymentOperationsAttempt[];
  outboxEvents: PaymentOperationsOutboxEvent[];
  timelineEvents: PaymentOperationsTimelineEvent[];
  operations: PaymentOperationsOperation[];
  ledgerEntries: PaymentOperationsLedgerEntry[];
  recovery: PaymentOperationsRecovery;
}
```

Add the read-only method to the exported `fluxApi` object. Remove `refund` from the payment-action regexes, refund-specific celebration logic, and the exported method.

- [ ] **Step 4: Register the admin route and remove customer refund UI**

Append this item to the Operations group in `appController.ts`:

```ts
{path:'admin-payment-operations',label:'Payment Operations',icon:'fa-wave-square'}
```

Replace the failed-payment refund button in `tracking.html` with:

```html
<a class="pill soft" href="?ojr=tickets" data-route="tickets">Contact support ↗</a>
```

Remove the refund branch from `Page.executeAction` and change the `payments-new.ts` failed-payment copy to:

```text
Open transfer details for retry options or to contact support.
```

- [ ] **Step 5: Run frontend tests and type checking**

```bash
cd frontend/fluxpay-ui
node --test tests/admin-payment-operations.test.cjs \
  tests/admin-navigation.test.cjs tests/wallet-api.test.cjs
npm run typecheck
```

Expected: all commands succeed.

- [ ] **Step 6: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/services/flux-api.ts \
  frontend/fluxpay-ui/src/ts/appController.ts \
  frontend/fluxpay-ui/src/ts/views/tracking.html \
  frontend/fluxpay-ui/src/ts/services/page.ts \
  frontend/fluxpay-ui/src/ts/viewModels/payments-new.ts \
  frontend/fluxpay-ui/tests/admin-payment-operations.test.cjs \
  frontend/fluxpay-ui/tests/admin-navigation.test.cjs \
  frontend/fluxpay-ui/tests/wallet-api.test.cjs
git commit -m "feat: add admin payment operations navigation"
```

---

### Task 9: Implement event correlation and lifecycle polling

**Files:**
- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-payment-operations.ts`
- Modify: `frontend/fluxpay-ui/tests/admin-payment-operations.test.cjs`

**Interfaces:**
- Consumes: `PaymentOperationsResponse` and `fluxApi.adminPaymentOperations`.
- Produces: a dedicated JET view model with `eventRows`, `lifecycleGroups`, `lookup`, `refresh`, and `disconnected`.

- [ ] **Step 1: Add failing VM tests with controllable timers**

Use `load-typescript.cjs` to inject mocked `knockout`, `session`, and `fluxApi`, plus a fake interval scheduler. Assert:

```js
assert.equal(vm.pollDelay({payment:{status:'PROCESSING'},outboxEvents:[],operations:[]}),1000);
assert.equal(vm.pollDelay({payment:{status:'FAILED'},outboxEvents:[{delivery:{state:'PENDING'}}],operations:[]}),1000);
assert.equal(vm.pollDelay({payment:{status:'UNDER_REVIEW'},outboxEvents:[],operations:[]}),5000);
assert.equal(vm.pollDelay({payment:{status:'COMPLETED'},outboxEvents:[],operations:[]}),undefined);
```

Build two `payout.failed` rows with different event IDs and assert the timeline mapping uses exact event IDs. Assert a transient refresh error preserves `snapshot()` and sets `refreshWarning()`. Change payment IDs while the first request is pending and assert the late response cannot overwrite the second lookup. Assert `disconnected()` clears the timer.

- [ ] **Step 2: Run the VM tests and confirm the module is missing**

```bash
cd frontend/fluxpay-ui
node --test tests/admin-payment-operations.test.cjs
```

Expected: failure resolving `viewModels/admin-payment-operations.ts`.

- [ ] **Step 3: Implement the view-model state and lifecycle derivation**

Create a standalone JET/Knockout class, not a `Page` subclass. Its constructor must restore the session before any lookup:

```ts
constructor(params: any) {
  const initialId = String(params?.params?.paymentId || '').trim();
  if (initialId) this.paymentId(initialId);
  void this.restoreSession();
}
private async restoreSession() {
  if (!session.user()) await session.restore();
  if (this.paymentId()) void this.lookup();
}
```

Define the stage and evidence types before the class:

```ts
type LifecycleStageId = 'confirmation' | 'payout' | 'recovery' | 'refund';
```

Define the four stage constants in this exact order:

```ts
const STAGES = [
  {id:'confirmation',label:'Payment confirmation'},
  {id:'payout',label:'Payout execution'},
  {id:'recovery',label:'Recovery / retry'},
  {id:'refund',label:'Refund'},
] as const;
```

Use exact event-ID indexing:

```ts
const timelineById = new Map(
  response.timelineEvents.map(event => [event.eventId, event]),
);
const rows = response.outboxEvents.map(event => ({
  key: event.eventId,
  eventId: event.eventId,
  eventType: event.eventType,
  stage: stageFor(event.eventType),
  aggregateSequence: event.aggregateSequence,
  createdAt: event.createdAt,
  occurredAt: timelineById.get(event.eventId)?.occurredAt ?? null,
  outbox: event,
  delivery: event.delivery,
  timeline: timelineById.get(event.eventId) ?? null,
  timelinePersisted: timelineById.has(event.eventId),
  command: event.eventType === 'payout.retry' || event.eventType === 'payout.refund',
  payload: event.payload,
  kafkaTopic: event.eventType,
  correlationId: timelineById.get(event.eventId)?.correlationId ?? null,
  consumptionLabel: consumptionLabel(event, timelineById.has(event.eventId)),
}));
```

Append timeline-only rows, sort by aggregate sequence, time, and event ID, and expose the computed values used by the template:

```ts
eventRows = ko.pureComputed(() => buildEventRows(this.snapshot()));
unclassifiedEvents = ko.pureComputed(() =>
  this.eventRows().filter(row => !row.stage),
);
lifecycleGroups = ko.pureComputed(() =>
  STAGES.map(stage => ({
    ...stage,
    status: stageStatus(stage.id, this.snapshot()),
    description: stageDescription(stage.id),
    tone: stageTone(stage.id, this.snapshot()),
    events: this.eventRows().filter(row => row.stage === stage.id),
  })),
);
```

Implement `consumptionLabel(event, timelinePersisted)` as:

```ts
function consumptionLabel(
  event: PaymentOperationsOutboxEvent,
  timelinePersisted: boolean,
): string {
  if (event.eventType === 'payout.retry' || event.eventType === 'payout.refund')
    return 'Operational recovery command; not a customer timeline event';
  if (timelinePersisted) return 'Persisted to customer timeline';
  if (event.delivery.state === 'SENT')
    return 'Published to Kafka; timeline persistence is not yet recorded';
  return 'Committed to the outbox; delivery is not yet published';
}
```

Implement `stageFor(eventType)` with exactly these mappings:

```ts
function stageFor(eventType: string): LifecycleStageId | null {
  if (['payment.initiated', 'payment.route.selected', 'payment.screening.completed', 'payment.review.requested'].includes(eventType))
    return 'confirmation';
  if (['payout.submitted', 'payout.completed', 'payout.failed'].includes(eventType))
    return 'payout';
  if (eventType === 'payout.retry') return 'recovery';
  if (['payout.refund', 'payment.refunded'].includes(eventType)) return 'refund';
  return null;
}
```

Implement `stageStatus`, `stageTone`, and `stageDescription` as pure functions over `PaymentOperationsResponse` using these rules: confirmation is complete after `payment.initiated`; payout is complete after `payout.completed`, warning for reconciliation/failed, and active while processing; recovery is active for `RETRY_SCHEDULED`, complete for refund decisions, warning for `STALE`, otherwise neutral; refund is active for `REFUND_SCHEDULED` and complete for `REFUNDED` or `payment.refunded`.

Implement polling exactly:

```ts
pollDelay(response?: PaymentOperationsResponse): number | undefined {
  if (!response) return undefined;
  const active =
    response.payment.status === 'PROCESSING' ||
    response.outboxEvents.some(event =>
      ['PENDING', 'SENDING'].includes(event.delivery.state)) ||
    response.operations.some(operation => operation.status === 'IN_PROGRESS');
  if (active) return 1000;
  if (response.payment.status === 'UNDER_REVIEW') return 5000;
  return undefined;
}
```

Use a generation token, clear the prior timer on every lookup/payment change/disconnect, skip hidden or busy refreshes, preserve the last successful snapshot on refresh failure, and clear the timer when a terminal response no longer has active work. Expose these exact template helpers:

```ts
label = (value: unknown): string =>
  String(value ?? '')
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/^./, character => character.toUpperCase());
dateTime = (value: string | null | undefined): string => {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};
json = (value: unknown): string => JSON.stringify(value, null, 2);
deliveryLabel = (value?: string): string =>
  value === 'PENDING'
    ? 'Committed — pending publication'
    : value === 'SENDING'
      ? 'Publishing to Kafka'
      : value === 'SENT'
        ? 'Published to Kafka'
        : 'Delivery state unavailable';
attempts = ko.pureComputed(() => this.snapshot()?.attempts || []);
operations = ko.pureComputed(() => this.snapshot()?.operations || []);
ledgerEntries = ko.pureComputed(() => this.snapshot()?.ledgerEntries || []);
recovery = ko.pureComputed(() => this.snapshot()?.recovery);
```

- [ ] **Step 4: Run VM tests and type checking**

```bash
cd frontend/fluxpay-ui
node --test tests/admin-payment-operations.test.cjs
npm run typecheck
```

Expected: polling, correlation, stale-response, and cleanup tests pass.

- [ ] **Step 5: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/viewModels/admin-payment-operations.ts \
  frontend/fluxpay-ui/tests/admin-payment-operations.test.cjs
git commit -m "feat: correlate payment operations events"
```

---

### Task 10: Render and style the read-only operations console

**Files:**
- Create: `frontend/fluxpay-ui/src/ts/views/admin-payment-operations.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-integration.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-payment-operations.test.cjs`

**Interfaces:**
- Consumes: the Task 9 view model.
- Produces: an accessible admin-only page with lookup, four stages, evidence, attempts, operations, and ledger sections.

- [ ] **Step 1: Add failing template and CSS contract tests**

Add `admin-payment-operations` to the admin route inventory and loading-message map. Assert:

```js
assert.match(html, /Administrator access required/);
assert.match(html, /session\.isAdmin\(\)/);
assert.match(html, /role="alert"/);
assert.match(html, /role="status"[^>]*aria-live="polite"/);
assert.match(html, /<details>/);
assert.match(html, /<summary>/);
assert.match(html, /class="event-payload" data-bind="text:\$parent\.json\(payload\)"/);
assert.doesNotMatch(html, /data-bind="[^"]*\bhtml\s*:/);
assert.doesNotMatch(html, /Simulate|Trigger retry|Trigger refund|Reconcile payout/);
assert.match(css, /\.operations-flow\s*\{[^}]*display:\s*grid/);
assert.match(css, /\[data-state='PENDING'\]/);
assert.match(css, /\[data-state='SENDING'\]/);
assert.match(css, /\[data-state='SENT'\]/);
assert.doesNotMatch(css, /linear-gradient|radial-gradient/);
```

- [ ] **Step 2: Run the template tests and confirm the missing page**

```bash
cd frontend/fluxpay-ui
node --test tests/admin-integration.test.cjs \
  tests/admin-payment-operations.test.cjs
```

Expected: missing template and CSS selector failures.

- [ ] **Step 3: Create the accessible admin template**

Use the existing three-part structure: signed-out gate, `Administrator access required` customer gate, and the admin section. The page body must follow this concrete structure:

```html
<!-- ko if: !session.user() -->
<section class="admin-access-gate">
  <h2>Administrator sign-in required</h2>
  <a class="pill dark" href="?ojr=login" data-route="login">Log in</a>
</section>
<!-- /ko -->
<!-- ko if: session.user() && !session.isAdmin() -->
<section class="admin-access-gate">
  <h2>Administrator access required</h2>
  <p>Payment operations evidence is restricted to platform administrators.</p>
</section>
<!-- /ko -->
<!-- ko if: session.isAdmin() -->
<section class="admin-page payment-operations-page">
  <header class="admin-page-heading">
    <div><span class="eyebrow">PAYMENT OPERATIONS</span><h1>Event-flow evidence</h1>
      <p>Read-only evidence from confirmation through payout, recovery, and refund.</p></div>
  </header>
  <p class="subtle-note">Read-only operations evidence. Outbox SENT means published to Kafka; a timeline row means the timeline consumer persisted the event.</p>
  <div class="alert error" role="alert" data-bind="visible:error,text:error"></div>
  <div class="alert warning" role="status" aria-live="polite" data-bind="visible:refreshWarning,text:refreshWarning"></div>
  <div class="admin-loading-status" role="status" aria-live="polite" data-bind="visible:busy">Loading payment operations</div>

  <form class="payment-operations-lookup" data-bind="submit:lookup">
    <label>Payment ID<input required data-bind="value:paymentId,attr:{placeholder:'Payment UUID'}"></label>
    <button class="pill dark" data-bind="disable:busy">Inspect operations ↗</button>
    <button type="button" class="pill soft" data-bind="click:refresh,disable:busy">Refresh</button>
  </form>

  <!-- ko if: snapshot -->
  <section class="payment-operations-summary" data-bind="with:snapshot">
    <div><span>Status</span><strong data-bind="text:$parent.label(payment().status)"></strong></div>
    <div><span>Payment</span><strong class="mono" data-bind="text:payment().id"></strong></div>
    <div><span>Event sequence</span><strong data-bind="text:payment().eventSequence"></strong></div>
    <div><span>Recovery</span><strong data-bind="text:recovery().decision"></strong></div>
  </section>

  <section class="operations-flow" data-bind="foreach:lifecycleGroups">
    <article class="operations-stage" data-bind="attr:{'data-tone':tone}">
      <span class="eyebrow" data-bind="text:label"></span>
      <h2 data-bind="text:status"></h2>
      <p data-bind="text:description"></p>
      <strong data-bind="text:events.length + ' evidence rows'"></strong>
    </article>
  </section>

  <section class="event-evidence-list">
    <h2>Correlated lifecycle evidence</h2>
    <div data-bind="foreach:eventRows">
      <details class="event-evidence-row" data-bind="attr:{'data-command':command}">
        <summary>
          <strong data-bind="text:eventType"></strong>
          <span data-bind="text:'#' + aggregateSequence"></span>
          <span class="event-state" data-bind="attr:{'data-state':delivery() ? delivery().state : 'UNKNOWN'},text:$parent.deliveryLabel(delivery() ? delivery().state : undefined)"></span>
          <time data-bind="text:$parent.dateTime(occurredAt || createdAt)"></time>
          <small data-bind="text:consumptionLabel"></small>
        </summary>
        <div class="event-metadata">
          <div><span>Event ID</span><code data-bind="text:eventId"></code></div>
          <div><span>Topic</span><code data-bind="text:kafkaTopic || '—'"></code></div>
          <div><span>Correlation</span><code data-bind="text:correlationId || '—'"></code></div>
          <div><span>Delivery attempts</span><strong data-bind="text:delivery() ? delivery().attemptCount : 0"></strong></div>
          <div><span>Next attempt</span><strong data-bind="text:delivery() && delivery().nextAttemptAt ? $parent.dateTime(delivery().nextAttemptAt) : '—'"></strong></div>
          <div><span>Last delivery error</span><strong data-bind="text:delivery() && delivery().lastError ? delivery().lastError : 'None'"></strong></div>
        </div>
        <pre class="event-payload" data-bind="text:$parent.json(payload)"></pre>
      </details>
    </div>
    <div class="empty-state" data-bind="visible:!busy() && eventRows().length===0"><h3>No event evidence yet</h3><p>Enter a payment ID to inspect committed outbox records.</p></div>
  </section>

  <section class="admin-list-surface">
    <h2>Payout attempts</h2>
    <div class="table-scroll"><table class="dense-table attempts-table"><thead><tr><th>Attempt</th><th>Route</th><th>Provider</th><th>Status</th><th>Provider reference</th><th>Error</th></tr></thead>
      <tbody data-bind="foreach:attempts"><tr><td data-bind="text:attemptNumber"></td><td data-bind="text:routeCode"></td><td data-bind="text:providerCode"></td><td data-bind="text:$parent.label(status)"></td><td data-bind="text:providerReference || '—'"></td><td data-bind="text:errorCode || '—'"></td></tr></tbody>
    </table></div>
  </section>

  <section class="recovery-summary admin-list-surface" data-bind="with:recovery">
    <h2>Recovery decisions</h2>
    <p><strong data-bind="text:decision"></strong></p>
    <p>Automated retries: <strong data-bind="text:automatedRetryCount"></strong></p>
    <p>Next run: <strong data-bind="text:nextRun ? $parent.dateTime(nextRun) : 'Not scheduled'"></strong></p>
  </section>

  <section class="admin-list-surface">
    <h2>Payment and recovery operations</h2>
    <div class="table-scroll"><table class="dense-table"><thead><tr><th>Namespace</th><th>Type</th><th>Key</th><th>Status</th><th>HTTP</th><th>Response</th></tr></thead>
      <tbody data-bind="foreach:operations"><tr><td data-bind="text:namespace"></td><td data-bind="text:operationType"></td><td class="mono" data-bind="text:clientKey"></td><td data-bind="text:status"></td><td data-bind="text:outcomeStatus || '—'"></td><td><details><summary>View</summary><pre class="event-payload" data-bind="text:$parent.json(response)"></pre></details></td></tr></tbody>
    </table></div>
  </section>

  <section class="admin-list-surface">
    <h2>Funding and refund ledger evidence</h2>
    <div class="table-scroll"><table class="dense-table operations-ledger-table"><thead><tr><th>Journal</th><th>Entry</th><th>Amount</th><th>Currency</th><th>Narration</th><th>Time</th></tr></thead>
      <tbody data-bind="foreach:ledgerEntries"><tr><td class="mono" data-bind="text:journalReference"></td><td data-bind="text:entryType"></td><td data-bind="text:amount"></td><td data-bind="text:currency"></td><td data-bind="text:narration"></td><td data-bind="text:$parent.dateTime(createdAt)"></td></tr></tbody>
    </table></div>
  </section>
</section>
<!-- /ko -->
```

Keep the view-model names from Task 9 unchanged; do not add HTML bindings or write controls.

- [ ] **Step 4: Add scoped responsive CSS**

Append selectors for:

```css
.payment-operations-lookup
.payment-operations-summary
.operations-flow
.operations-stage
.event-evidence-list
.event-evidence-row
.event-state[data-state='PENDING']
.event-state[data-state='SENDING']
.event-state[data-state='SENT']
.event-payload
.attempts-table
.recovery-summary
.operations-ledger-table
```

Use four columns above `900px`, two at `900px`, and one at `640px`; allow event summaries and payload content to wrap without widening the page.

- [ ] **Step 5: Run frontend tests, type checking, and the real JET build**

```bash
cd frontend/fluxpay-ui
node --test tests/admin-payment-operations.test.cjs \
  tests/admin-integration.test.cjs tests/admin-navigation.test.cjs
npm run typecheck
npm run build
```

Expected: tests pass, TypeScript succeeds, and `web-dev/js/viewModels/admin-payment-operations.js` plus `web-dev/js/views/admin-payment-operations.html` are generated.

- [ ] **Step 6: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/views/admin-payment-operations.html \
  frontend/fluxpay-ui/src/css/admin-console.css \
  frontend/fluxpay-ui/tests/admin-integration.test.cjs \
  frontend/fluxpay-ui/tests/admin-payment-operations.test.cjs
git commit -m "feat: render payment operations console"
```

Do not add generated `web-dev` files.

---

### Task 11: Add the two-payment demo script

**Files:**
- Create: `scripts/demo-payment-operations.py`
- Create: `tests/test_demo_payment_operations.py`

**Interfaces:**
- Consumes: seeded customer credentials plus existing wallet, recipient, draft, quote, confirm, and payout APIs.
- Produces: one real payment ID per scenario; no direct database, Kafka, or refund calls.

- [ ] **Step 1: Write failing Python script tests**

Cover:

```python
def test_create_demo_payment_uses_exact_quote_and_unique_idempotency_keys(self):
    result = script.create_demo_payment(
        self.base_url, "token", "retry-success", "BANK_STANDARD"
    )
    self.assertEqual(result["routeCode"], "BANK_STANDARD")
    self.assertEqual(result["scenario"], "retry-success")
    self.assertEqual(len(set(self.idempotency_keys)), 4)
    self.assertNotIn("/refund", [request["path"] for request in self.requests])

def test_missing_requested_route_fails_clearly(self):
    with self.assertRaisesRegex(RuntimeError, "BANK_STANDARD.*BANK_EXPRESS"):
        script.find_quote(
            {"quotes": [{"routeCode": "BANK_EXPRESS", "id": "q-2"}]},
            "BANK_STANDARD",
        )

def test_wallet_selection_skips_zero_balance_wallets(self):
    wallet = script.select_wallet(
        [
            {"walletId": "eur", "currency": "EUR", "availableBalance": "0.0000"},
            {"walletId": "usd", "currency": "USD", "availableBalance": "50.0000"},
        ],
        "10.0000",
    )
    self.assertEqual(wallet["walletId"], "usd")
```

Also test bearer/content/idempotency headers, selected customer login, missing route/password before network calls, token redaction on errors, and no refund path.

- [ ] **Step 2: Run the script test and confirm the module is missing**

```bash
python -B -m unittest tests.test_demo_payment_operations -v
```

Expected: failure loading `scripts/demo-payment-operations.py`.

- [ ] **Step 3: Implement the bounded API client and selectors**

Use `urllib.request`, `load_env`, and exact CLI choices `retry-success` and `refund-exhaustion`. Implement:

```python
SCENARIOS = {
    "retry-success": "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE",
    "refund-exhaustion": "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE",
}
```

`api_request` must set `Accept`, bearer authorization, JSON content type only for a body, and `Idempotency-Key`; accept any 2xx response; require top-level `data`; and raise an error containing status/code/message but never the token.

`select_wallet` must choose the first wallet whose `availableBalance` covers the source amount. `select_recipient` must choose the first active recipient. `find_quote` must require an exact case-sensitive route match and list available route codes in the error.

- [ ] **Step 4: Implement the real payment call sequence**

Use a run UUID and separate keys for draft, quotes, confirm, and payout:

```python
run_id = uuid.uuid4()
keys = {
    "draft": f"demo-{scenario}-draft-{run_id}",
    "quotes": f"demo-{scenario}-quotes-{run_id}",
    "confirm": f"demo-{scenario}-confirm-{run_id}",
    "payout": f"demo-{scenario}-payout-{run_id}",
}
```

Call `GET /api/wallets`, `GET /api/recipients`, `POST /api/payments/draft`, `POST /api/payments/{id}/quotes`, `POST /api/payments/{id}/confirm`, and `POST /api/payments/{id}/submit-payout`. Use amount `10.0000`, purpose `FAMILY_SUPPORT`, preference `BALANCED`, and the exact selected route. Print only scenario, payment ID, route, and expected attempt count.

- [ ] **Step 5: Add actionable prerequisite errors**

If no funded wallet, active recipient, verified KYC-compatible session, or requested quote exists, fail before the affected call with a message naming the prerequisite. Do not fabricate KYC, insert recipients, alter balances, or call refund. The README will require these existing customer prerequisites before running the script after a clean reset.

- [ ] **Step 6: Run focused and full Python tests**

```bash
python -B -m unittest tests.test_demo_payment_operations -v
python -B -m unittest discover -s tests -v
python -m ruff check scripts/demo-payment-operations.py tests/test_demo_payment_operations.py
python -m ruff format --check scripts/demo-payment-operations.py \
  tests/test_demo_payment_operations.py
```

Expected: all commands succeed.

- [ ] **Step 7: Commit**

```bash
git add scripts/demo-payment-operations.py tests/test_demo_payment_operations.py
git commit -m "feat: add payout recovery demo scenarios"
```

---

### Task 12: Document and execute the full local-stack acceptance flow

**Files:**
- Modify: `README.md`
- Modify: `docs/api-catalog.md`

**Interfaces:**
- Produces: a repeatable 5–8 minute evaluator runbook and public API documentation.
- Consumes: all backend, frontend, script, and topic work from Tasks 1–11.

- [ ] **Step 1: Add documentation contract assertions to the demo-script test**

Add this documentation contract test to `tests/test_demo_payment_operations.py`:

```python
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]


def test_readme_contains_complete_payment_operations_runbook(self):
    readme = (ROOT / "README.md").read_text()
    required = (
        "scripts/demo-payment-operations.py retry-success",
        "scripts/demo-payment-operations.py refund-exhaustion",
        "FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS=5",
        "BANK_STANDARD",
        "BANK_EXPRESS",
        "Admin → Payment Operations",
        "5–8 minute",
        "verified KYC",
        "funded USD wallet",
        "active INR recipient",
        "skipped integration tests are not acceptance",
    )
    for text in required:
        self.assertIn(text, readme)
```

- [ ] **Step 2: Run the documentation assertions and confirm failure**

```bash
python -B -m unittest tests.test_demo_payment_operations -v
```

Expected: missing runbook text failure.

- [ ] **Step 3: Update the API catalog and README**

Document:

```http
GET /api/admin/payments/{paymentId}/operations
```

as `ADMIN`-only and read-only. Add the eleven-topic inventory including `payout.retry` and `payout.refund`. Replace the old manual activation text with the selected-seed-route activation behavior. Add a **Payment operations event-flow demonstration** section with:

1. safe demo `.env` block;
2. infrastructure/reset/Flyway/seed sequence;
3. one-time customer prerequisite preparation through existing UI/API: verified KYC, funded USD wallet, and active INR recipient;
4. backend and frontend startup;
5. both demo commands;
6. exact expected Payment A and Payment B flows;
7. evidence distinctions between committed outbox, `SENT`, timeline persistence, and recovery operations;
8. 5–8 minute presenter target;
9. targeted, frontend, full-suite, and non-skipped Oracle/Kafka commands.

Use the correct executable filename `scripts/reset-local-db.py` everywhere.

- [ ] **Step 4: Run all non-infrastructure verification**

```bash
git diff --check
./mvnw -f backend/pom.xml spotless:check
./mvnw -f backend/pom.xml test
python -B -m unittest discover -s tests -v
cd frontend/fluxpay-ui
npm test
npm run typecheck
npm run build
```

Expected: every command succeeds. Return to the repository root after the frontend commands.

- [ ] **Step 5: Run genuine Oracle/Kafka integration verification**

With `ORACLE_TESTS_ACTIVE=true`, `ORACLE_TEST_USERNAME=FLUXPAY_TEST`, Oracle test JDBC values, and `KAFKA_BOOTSTRAP_SERVERS` loaded from `.env`, stop the application backend and run:

```bash
python -B scripts/reset-local-db.py --schema FLUXPAY_TEST --execute
set -a
. ./.env
set +a
./mvnw -f backend/pom.xml -Pintegration verify
```

Expected: integration tests execute rather than skip. If any Oracle/Kafka integration test is skipped, acceptance is not complete.

- [ ] **Step 6: Execute the real two-payment demo**

Start the application stack with the demo block in `.env`, then run:

```bash
python -B scripts/demo-payment-operations.py retry-success
python -B scripts/demo-payment-operations.py refund-exhaustion
```

Open each printed payment ID under **Admin → Payment Operations** and verify:

- Payment A: two failed attempts, one successful automatic retry, `payout.completed`, final `COMPLETED`.
- Payment B: six failed attempts, five `AUTO_RETRY` operations, immediate `payout.refund`, refund ledger entries, `payment.refunded`, final `REFUNDED`.
- `payout.retry` and `payout.refund` outbox rows reach `SENT`.
- Lifecycle rows appear in the timeline read model.
- Outbox and timeline evidence are not conflated.
- No simulation or financial write controls appear in the admin page.
- No customer refund control remains in the tracking page.

- [ ] **Step 7: Commit documentation and final contract adjustments**

```bash
git add README.md docs/api-catalog.md \
  tests/test_demo_payment_operations.py
git commit -m "docs: add payment operations demo runbook"
```

- [ ] **Step 8: Request final code review**

Invoke the `requesting-code-review` skill against the complete diff from commit `4576dd30` through HEAD, explicitly checking the approved spec, admin authorization, outbox/timeline truthfulness, route-specific failure counts, recovery timing, refund idempotency, and the two live scenarios.
