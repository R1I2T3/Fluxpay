# FluxPay M2 Demo Funding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an authenticated, disabled-by-default demo-funding endpoint that posts one balanced Oracle journal and replays completed requests exactly once.

**Architecture:** `DemoFundingService` validates and resolves replay outside a transaction, then calls the separately proxied transactional `WalletPostingService`. The posting service claims `WalletOperation`, finds or creates the customer wallet, posts `DEMO_CLEARING` debit/customer credit through the existing ledger engine, stores the response snapshot, and commits all state atomically.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring MVC/Security, Spring Data JPA, Oracle 23ai, Jackson, JUnit 5, Mockito, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-11-demo-funding-design.md`

## Global Constraints

- Continue on `member2-wallet-ledger`; do not create a worktree or another branch.
- Preserve `LedgerWriter.append(UUID, String, BigDecimal, String, String)`.
- Do not edit existing migrations, `pom.xml`, shared security/API classes, Kafka, or frontend files.
- Demo funding defaults to disabled and all monetary values use exact scale-4 decimal strings.
- Production code follows a witnessed RED test; Oracle tests use only `FLUXPAY_M2_TEST`.

---

### Task 1: Request normalization, response snapshot, and replay boundary

**Files:**
- Create: `backend/src/test/java/com/fluxpay/service/DemoFundingServiceTest.java`
- Create: `backend/src/main/java/com/fluxpay/dto/WalletReceiveRequest.java`
- Create: `backend/src/main/java/com/fluxpay/dto/WalletResponse.java`
- Create: `backend/src/main/java/com/fluxpay/config/M2DemoFundingConfig.java`
- Create: `backend/src/main/java/com/fluxpay/service/DemoFundingService.java`
- Create: `backend/src/main/java/com/fluxpay/service/DemoFundingDisabledException.java`
- Create: `backend/src/main/java/com/fluxpay/service/DemoFundingRetryException.java`
- Create: `backend/src/main/java/com/fluxpay/service/OperationRaceException.java`

**Interfaces:**
- Consumes: `WalletOperationRepository.findByUserIdAndOperationTypeAndClientKey(...)` and a separately injected `WalletPostingService.receiveDemo(...)`.
- Produces: `WalletResponse receiveDemo(UUID userId, WalletReceiveRequest request, String clientKey)` and an immutable normalized request passed to the posting service.

- [x] **Step 1: Write failing service tests**

  Cover disabled behavior, uppercase/four-place normalization, invalid currency/amount/key, exact completed replay from a literal JSON snapshot, changed-payload conflict, and a retry after `OperationRaceException`. Expected results use literal decimal strings.

- [x] **Step 2: Run the focused test and verify RED**

  Run `./mvnw.cmd -f backend/pom.xml -Dtest=DemoFundingServiceTest test`.
  Expected: test compilation fails because the new service/DTO/config types do not exist.

- [x] **Step 3: Implement the minimal replay boundary**

  Add immutable request/response DTO records. `DemoFundingService` checks the feature flag first, validates a 1-255 character key, parses the amount without rounding, canonicalizes `{"currency":"USD","amount":"500.0000"}`, resolves completed replay/conflict, and delegates new work. Catch an `OperationRaceException` or database uniqueness race only after the inner transaction exits; reload a committed winner or retry the whole transaction once.

- [x] **Step 4: Run focused tests and verify GREEN**

  Run the same Maven command and require zero failures/errors.

- [x] **Step 5: Commit with the completed Step 4 change set**

  Commit Task 1 as `feat(m2): add demo funding replay boundary`.

### Task 2: Atomic Oracle posting and operation completion

**Files:**
- Create: `backend/src/test/java/com/fluxpay/service/WalletPostingServiceOracleTest.java`
- Create: `backend/src/main/java/com/fluxpay/service/WalletPostingService.java`
- Create: `backend/src/main/java/com/fluxpay/service/DemoClearingWalletNotFoundException.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/WalletRepository.java`

**Interfaces:**
- Consumes: `LedgerJournalService.post(String, List<LedgerJournalLine>)`, configured system user UUID, wallet and operation repositories.
- Produces: `WalletResponse receiveDemo(UUID userId, String currency, BigDecimal amount, String normalizedRequest, String clientKey)` inside one transaction.

- [x] **Step 1: Write failing isolated-Oracle tests**

  Use fixed non-login fixture users in `FLUXPAY_M2_TEST`. Prove a missing customer wallet is created, demo clearing is debited, customer is credited, two ledger rows share one journal reference, the completed operation stores its response, replay changes no counts/balances, changed payload conflicts, a forced missing-clearing failure rolls back wallet/operation/ledger state, and concurrent identical requests apply once.

- [x] **Step 2: Run focused Oracle tests and verify RED**

  Run the isolated runner with Maven selector `WalletPostingServiceOracleTest`.
  Expected: compilation fails because `WalletPostingService` is absent.

- [x] **Step 3: Implement transactional posting**

  In one `@Transactional` method: reject an already-visible operation with `OperationRaceException`; create and flush `IN_PROGRESS`; find/create the authenticated user's `CUSTOMER` wallet; find the configured system user's `DEMO_CLEARING` wallet; form `M2-DEMO-<operation UUID>`; post deterministic `:clearing` and `:customer` lines; build/serialize `WalletResponse`; complete and flush the operation. Do not mutate balances directly.

- [x] **Step 4: Run focused Oracle tests and verify GREEN**

  Require successful posting, replay, rollback, and concurrency cases with unchanged state on rejected attempts.

- [x] **Step 5: Commit with the completed Step 4 change set**

  Commit Task 2 as `feat(m2): post demo funding atomically`.

### Task 3: Authenticated controller and M2 error mapping

**Files:**
- Create: `backend/src/test/java/com/fluxpay/controller/WalletControllerTest.java`
- Create: `backend/src/main/java/com/fluxpay/controller/WalletController.java`
- Create: `backend/src/main/java/com/fluxpay/config/M2ApiExceptionHandler.java`

**Interfaces:**
- Consumes: `CurrentUser`, `DemoFundingService.receiveDemo(...)`, `ApiResponse`, and MDC correlation ID.
- Produces: authenticated `POST /api/wallets/receive-demo`.

- [x] **Step 1: Write failing MockMvc tests**

  Use `TestAuthHelper.mockJwt(...)` and mock `JwtUtil.parse(...)` to a complete `CurrentUser`. Verify unauthenticated rejection, signed request success, missing/invalid header/body `400`, disabled/missing-clearing `404`, idempotency/retry `409`, response envelope, correlation ID, and literal decimal strings.

- [x] **Step 2: Run focused tests and verify RED**

  Run `./mvnw.cmd -f backend/pom.xml -Dtest=WalletControllerTest test`.
  Expected: compilation fails because controller and advice do not exist.

- [x] **Step 3: Implement controller and scoped advice**

  `WalletController` extracts `@AuthenticationPrincipal CurrentUser`, accepts a nullable `Idempotency-Key` for service-level validation, delegates only to the service, and wraps the result in `ApiResponse`. `M2ApiExceptionHandler` is scoped to this controller and maps only M2/argument errors without editing the shared handler.

- [x] **Step 4: Run focused tests and verify GREEN**

  Require all controller/security/error-envelope cases to pass.

- [x] **Step 5: Commit with the completed Step 4 change set**

  Commit Task 3 as `feat(m2): expose authenticated demo funding`.

### Task 4: Regression verification, documentation, and PR update

**Files:**
- Modify: `docs/02-member2-wallet-ledger.md`
- Modify: `docs/superpowers/plans/2026-09-11-demo-funding-implementation-plan.md`

**Interfaces:**
- Produces: verified Step 4 code and an updated PR #3 branch.

- [x] **Step 1: Update progress documentation**

  Record implemented endpoint/configuration/idempotency/transaction behavior and exact test counts. Keep FX, remaining wallet APIs, reconciliation scripts, and frontend as future work.

- [x] **Step 2: Format only M2-owned Java files**

  Apply Google Java Format without changing other members' files.

- [x] **Step 3: Run full verification**

  Run `python backend/src/test/resources/m2/run_oracle_tests.py`, `git diff --check`, inspect changed paths, and perform a backend startup smoke test. Require zero test failures/errors and `/v3/api-docs` HTTP 200.

  The 94-test Oracle-backed suite, formatting and diff checks pass. The automated
  shell reaches Oracle and completes the Spring context but cannot bind Tomcat
  because its Windows sandbox blocks Java's internal selector loopback socket;
  repeat the HTTP smoke check from the normal VS Code terminal.

- [x] **Step 4: Commit and push**

  Commit documentation/format-only changes as needed, verify a clean working tree, and push `member2-wallet-ledger` so PR #3 updates automatically.
