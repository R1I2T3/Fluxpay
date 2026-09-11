# FluxPay M2 Ledger Posting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the transactional Member 2 ledger engine that validates and posts balanced journals, updates wallet balances exactly once, and rolls back every partial financial change.

**Architecture:** `LedgerJournalService` owns complete-journal validation and the transaction. It binds explicit per-line metadata in `LedgerPostingContext`, then calls the frozen `LedgerWriter` contract for each line. `PersistentLedgerWriter` implements that contract, locks the wallet, validates/replays the entry, appends one immutable `LedgerEntry`, and changes that wallet's posted balance in the caller's mandatory transaction.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Data JPA, Oracle `RAW(16)`/`NUMBER(19,4)`, JUnit 5, Mockito only for fast unit boundaries, real isolated Oracle for persistence and transaction behavior.

**Spec:** `docs/02-member2-wallet-ledger.md`

## Global Constraints

- Preserve `LedgerWriter.append(UUID, String, BigDecimal, String, String)` unchanged.
- Do not edit `pom.xml`, shared security, shared contracts, or applied migrations.
- `PersistentLedgerWriter` is the only Step 3 runtime path that changes posted `Wallet.balance`.
- Ledger amounts are positive, have at most four fractional digits, and use `DEBIT`/`CREDIT` for direction.
- Customer debits may not exceed `balance - held_balance`; system wallets may have signed balances and zero holds.
- Complete journals balance independently per currency.
- The writer requires a caller transaction; wallet changes and ledger inserts commit or roll back together.
- Oracle tests run only against `FLUXPAY_M2_TEST`; never clean, truncate, or reset a shared schema.

---

### Task 1: Journal model, validation, and scoped metadata

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/LedgerJournalLine.java`
- Create: `backend/src/main/java/com/fluxpay/service/LedgerPostingContext.java`
- Create: `backend/src/main/java/com/fluxpay/service/LedgerJournalService.java`
- Test: `backend/src/test/java/com/fluxpay/service/LedgerJournalServiceTest.java`

**Interfaces:**
- Consumes: frozen `LedgerWriter.append(...)`.
- Produces: `LedgerJournalService.post(String, List<LedgerJournalLine>)`; a scoped context exposing the current journal reference and narration for a deterministic entry key.

- [x] **Step 1: Write failing tests**

  Test that a balanced two-line journal delegates both real line values, rejects per-currency imbalance before any append, rejects duplicate entry keys, and clears context after both success and thrown append failure.

- [x] **Step 2: Run the focused test and verify RED**

  Run `./mvnw.cmd -f backend/pom.xml -Dtest=LedgerJournalServiceTest test` with Java 17. Expected: compilation failure because the three Step 3 classes do not exist.

- [x] **Step 3: Implement the minimal journal boundary**

  Add immutable `LedgerJournalLine`; validate nonblank journal/key, supported currency/type, positive scale-4 money, unique keys, and equal debit/credit totals per currency. Bind an immutable metadata map in a `ThreadLocal` scope and always remove it in `finally`. Annotate `post(...)` with `@Transactional`.

- [x] **Step 4: Run the focused test and verify GREEN**

  Run the same Maven test and require zero failures/errors.

### Task 2: Persistent single-entry writer

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/PersistentLedgerWriter.java`
- Create: `backend/src/main/java/com/fluxpay/service/InsufficientWalletFundsException.java`
- Create: `backend/src/main/java/com/fluxpay/service/LedgerIdempotencyConflictException.java`
- Test: `backend/src/test/java/com/fluxpay/service/PersistentLedgerWriterOracleTest.java`

**Interfaces:**
- Consumes: `WalletRepository.findByIdForUpdate`, `LedgerEntryRepository`, and `LedgerPostingContext`.
- Produces: the sole persistent implementation of frozen `LedgerWriter`.

- [x] **Step 1: Write failing Oracle tests**

  Test credit/add, debit/subtract, customer available-funds rejection, system signed debit, wallet-currency mismatch, exact replay with no second delta/row, conflicting replay, metadata persistence, and direct ungrouped calls.

- [x] **Step 2: Run the focused Oracle test and verify RED**

  Run through `backend/src/test/resources/m2/run_oracle_tests.py` with a focused Maven test selector. Expected: compilation failure because `PersistentLedgerWriter` and exceptions do not exist.

- [x] **Step 3: Implement the minimal writer**

  Mark `append(...)` `@Transactional(propagation = MANDATORY)`. Validate inputs, check an existing entry for exact replay, lock the wallet, recheck idempotency, enforce matching currency and customer available funds, apply the debit/credit delta, save immutable ledger metadata, and flush atomically.

- [x] **Step 4: Run the focused Oracle test and verify GREEN**

  Require every writer case to pass on `FLUXPAY_M2_TEST`.

### Task 3: Whole-journal atomicity and replay

**Files:**
- Test: `backend/src/test/java/com/fluxpay/service/LedgerJournalOracleTest.java`
- Modify only if RED proves necessary: the Step 3 service files above.

**Interfaces:**
- Consumes: `LedgerJournalService.post(...)` and persistent `LedgerWriter` bean.
- Produces: proven atomic and replay-safe multi-wallet posting behavior.

- [x] **Step 1: Write failing Oracle transaction tests**

  Test a balanced two-wallet journal, exact whole-journal replay, a refund-shaped compensating journal, rollback after an intermediate line fails, and consistent results when customer funds are held.

- [x] **Step 2: Run and verify RED**

  Introduce the failure after the first real append through a test-only failing writer decorator. Verify the test fails if transaction rollback or context cleanup is absent.

- [x] **Step 3: Make the minimum transaction-boundary correction**

  Keep one outer `@Transactional` boundary and mandatory inner appends. Do not add independent transactions or balance changes to journal orchestration.

- [x] **Step 4: Run and verify GREEN**

  Require unchanged balances and row counts after the forced mid-journal failure, and exactly-once deltas after replay.

### Task 4: Verification, documentation, and commit

**Files:**
- Modify: `docs/02-member2-wallet-ledger.md`
- Create: `docs/superpowers/plans/2026-09-11-ledger-posting-implementation-plan.md`

**Interfaces:**
- Produces: a documented and locally committed Step 3 implementation.

- [x] **Step 1: Update the implementation-status section**

  Record exactly which Step 3 classes and behaviors now exist; keep later conversion/API/FX/frontend work described as future scope.

- [x] **Step 2: Format owned Java files**

  Run Spotless only for M2 Step 3 Java files so unrelated members' files are untouched.

- [x] **Step 3: Run full verification**

  Run all M2 tests through the isolated Oracle runner, `git diff --check`, and inspect the final diff. Require zero test failures and no shared-file changes.

- [x] **Step 4: Commit locally**

  Commit only Step 3-owned files with `feat(m2): add transactional ledger posting`. Do not push.
