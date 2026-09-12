# FluxPay M2 FX Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add cached live/mock FX snapshots, authenticated preview, and replay-safe atomic customer wallet conversion.

**Architecture:** `FxQuoteService` implements the frozen FX contract while caching snapshots from a selected live/mock source. `WalletConversionService` resolves validation/replay and obtains one snapshot before delegating to `WalletPostingService`, whose Oracle transaction posts the complete per-currency-balanced journal and stores the committed response.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Java HttpClient, Jackson, Spring MVC/Security/Data JPA, Oracle 23ai, JUnit 5, Mockito, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-11-fx-conversion-design.md`

## Global Constraints

- Continue on `member2-wallet-ledger`; do not create another branch or worktree.
- Do not edit existing migrations, `pom.xml`, frozen contracts, shared security/API classes, Kafka, or frontend files.
- Support only USD/EUR/INR directed non-identical pairs and exact scale-4 monetary strings.
- Replay completed conversion operations before contacting FX; post balances only through `LedgerJournalService`/`LedgerWriter`.
- Oracle tests use only the isolated `FLUXPAY_M2_TEST` schema.

---

### Task 1: Snapshot sources and bounded cache

**Files:**
- Create: `backend/src/main/java/com/fluxpay/dto/FxSnapshot.java`
- Create: `backend/src/main/java/com/fluxpay/service/FxSnapshotSource.java`
- Create: `backend/src/main/java/com/fluxpay/service/FxQuoteService.java`
- Create: `backend/src/main/java/com/fluxpay/service/FrankfurterFxProvider.java`
- Create: `backend/src/main/java/com/fluxpay/config/M2FxConfig.java`
- Create: `backend/src/main/java/com/fluxpay/config/M2MockFxRateProvider.java`
- Create: `backend/src/main/java/com/fluxpay/service/FxUnavailableException.java`
- Test: `backend/src/test/java/com/fluxpay/service/FxQuoteServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/config/M2MockFxRateProviderTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/FrankfurterFxProviderTest.java`

**Interfaces:**
- `FxSnapshotSource.fetch(String from, String to)` produces one validated snapshot.
- `FxQuoteService.snapshot(String from, String to)` returns cached metadata and `rate(...)` returns its exact rate.

- [x] **Step 1: Write failing snapshot, mock-pair, HTTP adapter, and cache tests.**
- [x] **Step 2: Run the focused tests and witness RED because the new types are absent.**
- [x] **Step 3: Implement source selection, pair normalization, live/mock fetching, bounded single-flight cache, one-hour freshness and two-hour stale fallback.**
- [x] **Step 4: Run focused tests and require GREEN.**

### Task 2: Replay boundary and conversion calculation

**Files:**
- Create: `backend/src/main/java/com/fluxpay/dto/WalletConvertRequest.java`
- Create: `backend/src/main/java/com/fluxpay/dto/WalletConvertResponse.java`
- Create: `backend/src/main/java/com/fluxpay/service/WalletConversionService.java`
- Test: `backend/src/test/java/com/fluxpay/service/WalletConversionServiceTest.java`

**Interfaces:**
- `WalletConversionService.convert(UUID, WalletConvertRequest, String)` validates, replays, selects one snapshot, calculates fee/net/credit, and delegates posting.
- Normalized operation JSON contains only the canonical customer request, so exact replay does not depend on a later FX result.

- [x] **Step 1: Write failing tests for validation, literals, replay-before-FX, changed payload, unavailable FX and race retry.**
- [x] **Step 2: Run the focused test and witness RED.**
- [x] **Step 3: Implement the minimal validation/replay/calculation boundary using `M2ConversionMath`.**
- [x] **Step 4: Run focused tests and require GREEN.**

### Task 3: Atomic five-line Oracle conversion

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/service/WalletPostingService.java`
- Create: `backend/src/main/java/com/fluxpay/service/FxSystemWalletNotFoundException.java`
- Test: `backend/src/test/java/com/fluxpay/service/WalletConversionPostingOracleTest.java`

**Interfaces:**
- `WalletPostingService.convert(...)` claims `CONVERT`, locates customer/system wallets, posts deterministic gross/net/fee/target lines, stores the response and commits once.

- [x] **Step 1: Write failing isolated-Oracle tests for the USD/INR example, all directed pairs, replay, rollback, insufficient funds, and concurrent identical requests.**
- [x] **Step 2: Run the Oracle class and witness RED before the posting method exists.**
- [x] **Step 3: Implement the transaction without direct balance mutation; omit a rounded zero-fee line.**
- [x] **Step 4: Run focused Oracle tests and require GREEN with unchanged state after rejected attempts.**

### Task 4: Authenticated preview/conversion APIs and final verification

**Files:**
- Create: `backend/src/main/java/com/fluxpay/controller/FxController.java`
- Modify: `backend/src/main/java/com/fluxpay/controller/WalletController.java`
- Modify: `backend/src/main/java/com/fluxpay/config/M2ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/fluxpay/controller/FxControllerTest.java`
- Modify: `backend/src/test/java/com/fluxpay/controller/WalletControllerTest.java`
- Modify: `docs/02-member2-wallet-ledger.md`

**Interfaces:**
- Authenticated `GET /api/fx/rate` and `POST /api/wallets/convert`, using existing envelopes/correlation IDs and M2-scoped errors.

- [x] **Step 1: Write failing MockMvc tests for authentication, success envelopes and 400/409/422/503 mappings.**
- [x] **Step 2: Run focused controller tests and witness RED.**
- [x] **Step 3: Implement controllers/advice and update progress documentation.**
- [x] **Step 4: Run focused tests, format M2 Java, then run the full isolated Oracle suite and diff checks.**
- [x] **Step 5: Commit and push `member2-wallet-ledger` to update PR #3.**
