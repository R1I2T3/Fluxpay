# Plan 2 — Trust Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden authz/refund/retry plus deterministic screening/settlement simulation, sync-only.

**Architecture:** Annotate-gate compliance/policy writes, move refund to ADMIN-only plus Kafka 5x2min auto-retry and reconcile endpoint, extend existing sim assessor/providers in `development/` with rule triggers and chaos envs.

**Tech Stack:** Spring Boot / Kafka outbox + consumer, JUnit + MockMvc, Oracle/Kafka acceptance via `BackendAcceptanceIT`.

**Spec:** `docs/superpowers/specs/2026-09-17-backend-hardening-design.md` and `docs/superpowers/specs/2026-09-17-simulation-design.md`

## Global Constraints

- Java 17, `spotless:apply`; never enable sim flags in prod-like envs.
- OWNERSHIP: this track alone edits `ComplianceCaseController.java`, `PolicyController.java`, `PayoutController.java` refund block, `RouteAdminController.java`, `RecipientService.java` status lines, `RecoveryService.java`, `development/Simulated*` files, and creates `V009`, `PayoutRetryConsumer`, `PayoutReconciler`. Do NOT edit `WalletController.java`, `WalletPostingService.java`, `LedgerJournalService.java`, `CopilotController.java`, any frontend file.
- Contract: exposes `POST /api/admin/payments/{id}/refund`, `POST /api/admin/payments/{id}/reconcile`, topics `payout.retry`/`payout.refund`; Plan 3 consumes refund journal interface only.

---

### Task 1: Authz + recipient + admin annotation fixes

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/controller/ComplianceCaseController.java:38-53`
- Modify: `backend/src/main/java/com/fluxpay/controller/PolicyController.java:43-68`
- Modify: `backend/src/main/java/com/fluxpay/controller/RouteAdminController.java:30-42`
- Modify: `backend/src/main/java/com/fluxpay/service/RecipientService.java:46,85`
- Test: `backend/src/test/java/com/fluxpay/controller/ComplianceCaseControllerSecurityContractTest.java`

**Interfaces:**
- Consumes: `@PreAuthorize` + `MethodSecurityConfig`. Produces: all writes ADMIN-gated; `RecipientService` forces `ACTIVE`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void userCannotCreatePolicy() throws Exception {
  mockMvc.perform(post("/api/policies").header("Authorization", "Bearer user-token")
    .contentType(APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=ComplianceCaseControllerSecurityContractTest -v`
Expected: FAIL with 200 instead of 403

- [ ] **Step 3: Write minimal fix**

```java
@PostMapping @PreAuthorize("hasRole('ADMIN')")
public ApiResponse<PolicyResponse> create(@Valid @RequestBody PolicyDocumentRequest r) { ... }
```

```java
// RecipientService.java: force ACTIVE, ignore client status
var status = RecipientStatus.ACTIVE;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=ComplianceCaseControllerSecurityContractTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/ComplianceCaseController.java backend/src/main/java/com/fluxpay/controller/PolicyController.java backend/src/main/java/com/fluxpay/service/RecipientService.java
git commit -m "fix(authz): gate compliance/policy writes, force recipient active"
```

### Task 2: Admin refund + Kafka 5x2min + reconcile

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/controller/PayoutController.java:70-91`
- Create: `backend/src/main/java/com/fluxpay/messaging/PayoutRetryConsumer.java`
- Create: `backend/src/main/java/com/fluxpay/service/PayoutReconciler.java`
- Test: `backend/src/test/java/com/fluxpay/service/RefundGateTest.java`

**Interfaces:**
- Consumes: `RecoveryService.refundFunded`, `PaymentOperationService.execute`. Produces: `POST /api/admin/payments/{id}/refund`, retry event `{paymentId, attemptCount, nextRun}`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void userRefundIsForbidden() throws Exception {
  mockMvc.perform(post("/api/payments/" + id + "/refund").header("Authorization", "Bearer user")
    .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=RefundGateTest -v`
Expected: FAIL with 200/404 instead of 403

- [ ] **Step 3: Write minimal implementation**

```java
@PostMapping("/api/admin/payments/{paymentId}/refund")
@PreAuthorize("hasRole('ADMIN')")
public ApiResponse<RecoveryResult> adminRefund(@PathVariable String paymentId,
  @RequestHeader("Idempotency-Key") String key, HttpServletRequest req) {
  var id = UUID.fromString(paymentId);
  var payment = reader.get(paymentId);
  return new ApiResponse<>(cid(req), operations.execute(currentAdminId(req), key,
    "REFUND", id, Map.of(), RecoveryResult.class,
    () -> new PaymentOperationService.Result<>(200, recovery.refundFunded(payment.senderUserId(), id, cid(req)), id)));
}
```

```java
// PayoutRetryConsumer: on payout.failed, if attemptCount < 5 republish with nextRun = now + 2min
// else recovery.refundFunded(senderId, paymentId, cid)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=RefundGateTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/PayoutController.java backend/src/main/java/com/fluxpay/messaging/PayoutRetryConsumer.java
git commit -m "feat(refund): admin-only refund with kafka retry"
```

### Task 3: Simulation rules + chaos (sync-only)

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/development/SimulatedComplianceAssessor.java:18-27`
- Modify: `backend/src/main/java/com/fluxpay/development/SimulatedStandardBankProvider.java:20-84`
- Modify: `backend/src/main/java/com/fluxpay/development/SimulatedLocalPartnerProvider.java:17-39`
- Create: `backend/src/main/resources/db/migration/V009__sim_seed.sql`
- Test: `backend/src/test/java/com/fluxpay/development/SimSimulationTest.java`

**Interfaces:**
- Consumes: existing provider contracts. Produces: `BLOCK/HIGH/SANCTIONS_HIT`, `LIMIT_EXCEEDED`, `UNCERTAIN` triggers for QA.

- [ ] **Step 1: Write the failing test**

```java
@Test
void blocklistedNameBlocks() {
  var r = assessor.assessDetailed(new ScreenInput("SANCTIONED_ACME", "USD", new BigDecimal("10")));
  assertEquals(ScreeningVerdict.BLOCK, r.verdict());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=SimSimulationTest -v`
Expected: FAIL with APPROVE instead of BLOCK

- [ ] **Step 3: Write minimal implementation**

```java
if (CLAUDE_BLOCKLIST.contains(nameFragment)) return Assessment.block("SANCTIONS_HIT");
if (System.getenv("SIMULATE_FAILURE") != null && key.contains("STANDARD_BANK"))
  throw new ProviderUncertainException("PROVIDER_TIMEOUT");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=SimSimulationTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/development/SimulatedComplianceAssessor.java backend/src/main/java/com/fluxpay/development/SimulatedStandardBankProvider.java backend/src/main/resources/db/migration/V009__sim_seed.sql
git commit -m "feat(sim): add screening and settlement triggers"
```
