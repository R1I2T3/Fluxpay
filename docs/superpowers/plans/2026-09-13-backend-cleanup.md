# Backend Cleanup Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task. Execute inline unless the user requests delegation. Steps use checkbox syntax for tracking. This is a proposed plan, not a record of completed implementation.

**Goal:** Remove obsolete/member-specific backend structure and reconcile payment, ledger, idempotency, configuration, messaging, and local database behavior.

**Architecture:** Keep one Spring Boot application. Consolidate business policies and durable operation/event boundaries before rebuilding a fresh local Oracle schema. Keep development-only integrations explicit and preserve unfinished embedding work.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Maven wrapper, Oracle, Kafka, Python helper scripts, JUnit/Mockito, Oracle integration tests.

**Spec:** [Backend cleanup design](../specs/2026-09-13-backend-cleanup-design.md). Read the design and this plan before execution.

## Global constraints

- Backend only; no frontend source changes or frontend build requirement.
- Java 17, Spring Boot 3.2.5, Maven wrapper, Oracle persistence, and Kafka remain the stack.
- Preserve useful unit-test doubles; production code must not select fake FX or silently replace event delivery with logging.
- Preserve unfinished embedding/policy functionality as explicitly unfinished; do not delete its contracts or claim it is implemented.
- No live external payout provider, deposit provider, or KYC storage provider is added.
- No database reset during planning; execution may reset only the verified local FluxPay application/test schemas.
- Never reset SYSTEM, SYS, another application schema, or a remote database; never use broad Docker volume pruning.
- One Maven module; package moves are not a multi-module rewrite.
- Use business names instead of member identifiers in active Java code, tests, SQL identifiers, runtime configuration, and scripts.
- Money uses BigDecimal; existing NUMBER(19,4) storage, supported currencies, and explicit rounding are retained.
- Significant behavior changes require regression tests; mechanical renames require compilation and existing tests, not duplicate tests of filenames.

## Execution order

| Stage | Tasks | Reviewable result |
|---|---|---|
| Baseline and structure | 1-3 | Runnable backend checks, member-free names, coherent packages |
| Shared business behavior | 4-7 | One quote policy, journal path, account resolver and consistent operation replay |
| External boundaries | 8-10 | Durable events, real security, explicit development integrations |
| Fresh database and proof | 11-14 | Clean migrations, local rebuild, truthful seed/check scripts and acceptance report |

Do not launch the development app against its existing schema after incompatible mapping changes. Unit tests and dedicated disposable test schemas provide verification until Task 13. Tasks may be reviewed as separate commits, but database-dependent changes are released together after the fresh-schema gate.

Paths beginning with `java/` below resolve against `backend/src/main/`; paths beginning with `test/` resolve against `backend/src/`. Fully expanded paths are used for new files and commands.

## Task 1: Establish a truthful backend verification baseline

**Files:** create `scripts/platform_commands.py`; modify `scripts/start-backend.py`, `scripts/start-infra.py`, `scripts/stop-infra.py`, `scripts/test-all.py`, `tests/test_scripts.py`; inspect `backend/pom.xml` and current Oracle test opt-in guards.

**Interfaces:** `PROJECT_ROOT`, `FRONTEND_DIR`, `project_path(path)`, `maven_command(*args)`, `ojet_command(*args)`, and `python_command(path, *args)` must satisfy the existing script callers. Restoring the frontend command helper is compatibility plumbing only; no frontend work runs.

- [ ] Record `git status --short`, Java/Python versions, current Maven test results and skipped Oracle tests. Preserve unrelated user changes.
- [ ] Reproduce the missing helper with `python -B -m unittest discover -s tests -p test_scripts.py` (audit result: 12 import errors).
- [ ] Implement project-relative command resolution using argument arrays, the Windows Maven wrapper and `sys.executable`. No string-built shell commands or secret logging.
- [ ] Remove the launcher's default `local` profile; explicit profiles must not alter identity validation after Task 9.
- [ ] Load `--env-file` before environment-dependent configuration is read. Keep backend verification independent of frontend availability.
- [ ] Run the script suite and `./mvnw.cmd -f backend/pom.xml test` on Windows; use `./mvnw` on Unix. Record existing failures separately from new regressions.

**Regression example:** the existing tests assert Windows commands begin with the resolved command interpreter, `/d`, `/c`, and the exact project `mvnw.cmd` path; Unix commands use the project `mvnw` directly. Add a test proving `--env-file` affects the backend test database selection before subprocess creation.

## Task 2: Remove confirmed dead code and rename member classes

**Files:** the rename manifest below; `java/com/fluxpay/common/contracts/PayoutProvider.java`; `java/com/fluxpay/common/enums/PayoutStatus.java`; the four unused overloads identified in the audit; their imports, JPQL strings, test references and script references.

- [ ] Remove the unused shared PayoutProvider contract, preserving the active service contract until it is moved to the shared-contract package.
- [ ] Remove PayoutStatus, the keyless draft/confirm overloads, the unpaged KYC list overload, and the String route-metric overload. Confirm no new caller was added since the audit.
- [ ] Rename using the manifest; update declarations, constructors, imports, package names, JPQL entity names and reflection references together. Use apply_patch or a reviewed mechanical refactor. The manifest shows final destinations: defer removal of runtime mock FX until Task 9, clock consolidation until Task 3, and legacy migration-test replacement until Task 11. Until those tasks, retain compilable member-free versions of the existing classes.
- [ ] Keep the outbox relay: its missing caller is repaired in Task 8. Keep embedding/policy contracts.
- [ ] Update RecoveryService tests to construct its full dependency set; then remove the partial four-argument constructor and nullable fallback branches.
- [ ] Compile and run existing tests. Do not add tests that merely check filename spelling.

### Rename and placement manifest

Production paths are relative to `backend/src/main/java/com/fluxpay`.

| Current | Final |
|---|---|
| `config/M1ApiExceptionHandler.java` | `web/advice/AuthKycApiExceptionHandler.java` |
| `config/M1AuthDataIntegrityHandler.java` | `web/advice/AuthDataIntegrityHandler.java` |
| `config/M1KycDataIntegrityHandler.java` | `web/advice/KycDataIntegrityHandler.java` |
| `config/M1SecurityConfig.java` | `common/security/MethodSecurityConfig.java` |
| `config/M2ApiExceptionHandler.java` | `web/advice/WalletFxApiExceptionHandler.java` |
| `config/M2DemoFundingConfig.java` | `config/DemoFundingConfig.java` |
| `config/M2FxConfig.java` | `config/FxConfig.java` |
| `config/M2MockFxRateProvider.java` | Test-only deterministic source under `backend/src/test/java/com/fluxpay/adapter/fx/MockFxRateProvider.java` |
| `config/M3ApiExceptionHandler.java` | `web/advice/PaymentApiExceptionHandler.java` |
| `config/M3BusinessException.java` | `exception/BusinessException.java` |
| `config/M3PaymentConfig.java` | Merge its clock bean into `config/ClockConfig.java` |
| `config/M4ApiExceptionHandler.java` | `web/advice/PayoutApiExceptionHandler.java` |
| `service/M1AuthException.java` | `exception/AuthException.java` |
| `service/M1KycException.java` | `exception/KycException.java` |
| `service/M2ConversionMath.java` | `domain/ConversionMath.java` |
| `service/M3KafkaTransport.java` | `messaging/KafkaTransport.java` |
| `service/M3OutboxRelay.java` | `messaging/OutboxRelay.java` |
| `service/M3PostingPort.java` | `common/contracts/PostingPort.java` |
| `service/M3TransportPort.java` | `common/contracts/TransportPort.java` |
| `service/M3WalletPort.java` | `common/contracts/WalletPort.java` |
| `service/PersistentM3WalletAdapter.java` | `adapter/persistence/PersistentWalletAdapter.java` |
| `beans/M3OutboxDelivery.java` | `beans/OutboxDelivery.java` |
| `beans/M3PaymentOperation.java` | `beans/PaymentOperation.java` |
| `dto/M3PostingAccounts.java` | `dto/PostingAccounts.java` |
| `dto/M3WalletSnapshot.java` | `dto/WalletSnapshot.java` |
| `repository/M3OutboxDeliveryRepository.java` | `repository/OutboxDeliveryRepository.java` |
| `repository/M3PaymentOperationRepository.java` | `repository/PaymentOperationRepository.java` |

Test renames, relative to `backend/src/test/java/com/fluxpay`:

| Current | Final |
|---|---|
| `config/M2MockFxRateProviderTest.java` | `adapter/fx/MockFxRateProviderTest.java` |
| `controller/M1ControllerMvcTest.java` | `controller/AuthKycControllerMvcTest.java` |
| `integration/OracleM1ApiIntegrationTest.java` | `integration/OracleAuthKycApiIntegrationTest.java` |
| `repository/M2LegacyMigrationOracleTest.java` | Retire legacy-upgrade assertions; replace required guarantees with `repository/FreshSchemaOracleTest.java` in Task 11 |
| `repository/M2RepositoryOracleTest.java` | `repository/WalletLedgerRepositoryOracleTest.java` |
| `repository/M2SchemaOracleTest.java` | `repository/WalletLedgerSchemaOracleTest.java` |
| `service/M2ConversionMathTest.java` | `domain/ConversionMathTest.java` |
| `service/PersistentM3WalletAdapterTest.java` | `adapter/persistence/PersistentWalletAdapterTest.java` |

The names AuthKyc/WalletFx/Payment/Payout prevent the four advice classes colliding. MethodSecurityConfig avoids colliding with the existing SecurityConfig bean. A dead or obsolete component is deleted rather than renamed solely to satisfy this manifest.

## Task 3: Finish package, error and configuration boundaries

**Files:** move the 11 service exceptions listed in the audit to `backend/src/main/java/com/fluxpay/exception/`; move `FrankfurterFxProvider` to `adapter/fx`; move Kafka publisher/consumer and event codec/contracts to `messaging`; move active service interfaces to `common/contracts`; create `common/web/ApiErrorFactory.java`, `config/ClockConfig.java`, `config/SystemAccountConfig.java`.

**Interfaces:** one injectable `Clock`; `ApiErrorFactory.create(String code, String message, Map<String,String> fields)` returns the existing ApiError; `SystemAccountConfig` owns `fluxpay.system-user-id`.

- [ ] Move DemoClearingWalletNotFoundException, DemoFundingDisabledException, FxSystemWalletNotFoundException, FxUnavailableException, InsufficientWalletFundsException, LedgerIdempotencyConflictException, OperationRaceException and WalletNotFoundException unchanged apart from package. Rename DemoFundingRetryException to OperationRetryException; AuthException/KycException are handled by Task 2.
- [ ] Scope advice explicitly to relevant controllers; preserve existing HTTP mappings unless a listed defect requires a change. UserController must receive the same auth/profile exception translation as its services require.
- [ ] Share ApiError construction; retain domain-specific mappings rather than placing every exception in one switch statement. Test wallet, payment, auth, and payout endpoints for status/code/correlation-ID preservation.
- [ ] Extract repeated Payment-to-PaymentResponse construction from PaymentService and PaymentConfirmationService into `backend/src/main/java/com/fluxpay/service/PaymentResponseMapper.java`. Preserve the response contract until Task 11 removes obsolete legacy metadata; keep request-specific validation in its use case.
- [ ] Consolidate clock bean declarations from EventClockConfig, FxConfig and the old payment config. Inject the same Clock into FX, auth/KYC audit timestamps, posting and event code where touched.
- [ ] Replace demo/FX system-user fallbacks with SystemAccountConfig. A missing system user must produce an explicit configuration/account-unavailable response, not a customer wallet fallback.
- [ ] Compile and run controller/security/configuration tests. Check the full context has exactly one Clock and one active implementation for each required contract.

## Task 4: Use one pricing and recommendation policy

**Files:** create `backend/src/main/java/com/fluxpay/domain/QuotePricingPolicy.java` and `PricedRoute.java`; move RoutePreference to `domain`; modify `service/QuoteService.java`, `service/RouteRecommender.java`, `service/RouteCatalogService.java`, `beans/PaymentQuote.java`, `beans/Payment.java`, quote DTOs and eligibility checks; remove beans/QuoteRoute after references migrate.

**Interfaces:** `QuotePricingPolicy.price(BigDecimal gross, BigDecimal fee, BigDecimal spreadPercent, BigDecimal marketRate)` returns `PricedRoute(BigDecimal netSourceAmount, BigDecimal offeredRate, BigDecimal recipientAmount)`. Both quote APIs call this policy; RouteRecommender owns ranking only.

- [ ] Add the following arithmetic regression and ranking tests for cheapest/fastest/balanced using the same active route fixtures through both entry points.

```java
var result = new QuotePricingPolicy().price(
    new BigDecimal("100.0000"), new BigDecimal("5.0000"),
    BigDecimal.ZERO, new BigDecimal("80.000000"));
assertThat(result.netSourceAmount()).isEqualByComparingTo("95.0000");
assertThat(result.recipientAmount()).isEqualByComparingTo("7600.0000");
```

- [ ] Reproduce the old fee-before/after-conversion disagreement, then share the policy and explicit rounding.
- [ ] Persist actual payout route code on each quote. Keep preference as a distinct enum and remove comparisons between preference names and provider codes.
- [ ] Freeze quote economics; route updates affect future generations. Verify selected-quote generation, route identity, ownership and expiry at execution.
- [ ] Include all customer-facing surcharges in the frozen quote. Remove independent customer-fee additions from simulated provider submit methods; provider-reported settlement costs must not silently alter the accepted customer amount.
- [ ] Cover no active routes, nonpositive net amounts, deterministic ranking ties, disabled routes and expired/superseded quotes.
- [ ] Run `./mvnw.cmd -f backend/pom.xml '-Dtest=QuotePricingPolicyTest,RouteRecommenderTest,RouteCatalogServiceTest,QuoteServiceTest,DbPaymentEligibilityGateTest' test` after creating/updating those tests.

## Task 5: Share account resolution and complete journal posting

**Files:** create `backend/src/main/java/com/fluxpay/service/SystemAccountService.java`; modify PersistentWalletAdapter, DbPaymentReader, PaymentSnapshot, WalletPostingService, PaymentPostingService, RefundJournalService, LedgerJournalService and their tests; add original posting details to PaymentSnapshot or a dedicated `dto/PaymentPostingSnapshot.java`.

**Interfaces:** `SystemAccountService.require(String currency, WalletAccountRole role)` returns an existing system Wallet. `PaymentPostingSnapshot` records customer/clearing/fee wallet UUIDs, currency, gross, net, fee and original journal reference. Keep `LedgerJournalService.post(String, List<LedgerJournalLine>)` as the shared journal boundary.

- [ ] Add a regression where customer, clearing and fee wallets are different and DbPaymentReader reconstructs them from the persisted posting snapshot.
- [ ] Route payment debit and refund through complete journals, retaining lock ordering, per-currency balance checks and replay/new-entry consistency.
- [ ] Replace independent system-wallet lookups and demo configuration dependencies with the account resolver. Seed system wallets in Task 12 rather than creating accounts opportunistically during a payout.
- [ ] Verify the proposed full refund exactly reverses original postings, including fees:

```text
Confirmation: customer -100, clearing +95, fee revenue +5.
Refund:       customer +100, clearing -95, fee revenue -5.
Replay:       every balance and ledger-entry count unchanged.
```

- [ ] Omit zero-fee legs; reject missing/invalid posting snapshots before writing any refund entry. Test concurrent/replayed refunds and a failure halfway through posting for atomic rollback.
- [ ] Preserve individual entry validation in PersistentLedgerWriter; do not mistake defense at a public persistence boundary for disposable duplication.
- [ ] Run ledger/posting/refund unit tests; run the corresponding Oracle tests against FLUXPAY_TEST after Task 11 provides the fresh schema.

## Task 6: Consolidate operation idempotency without merging domains

**Files:** create `backend/src/main/java/com/fluxpay/service/WalletOperationService.java` and `PaymentOperationService.java`; modify DemoFundingService, WalletConversionService, PaymentService, PaymentConfirmationService, DbPaymentEligibilityGate, PayoutController, operation entities/repositories and tests.

**Interfaces:** wallet coordination keeps `WalletOperation`; payment coordination keeps `PaymentOperation`. Both normalize request JSON before equality checks and store structured response snapshots. PaymentOperation identifies user, key, operation action, normalized request, payment ID, state and optional final HTTP status/response.

- [ ] Add tests for exact replay, changed amount/route/payment/action conflicts, duplicate concurrent requests and in-progress operations.
- [ ] Introduce explicit pending/completed state; remove empty-string/non-JSON sentinels. Use nullable completion fields only while IN_PROGRESS. Ensure SQL changes are carried into Task 11.
- [ ] Enforce a 255-character key limit consistently and require keys for mutating payment actions. Remove randomly generated request keys from controllers and compatibility overloads.
- [ ] Deduplicate wallet lookup/replay/race handling while allowing each operation to own validation and posting. Keep FX fetch outside replay paths.
- [ ] Deduplicate payment replay/serialization and compare normalized requests before every replay. A key used for SUBMIT cannot silently replay RETRY or another payment.
- [ ] Catch unique-key races outside the rolled-back transaction; re-read the winner in a fresh transaction. Do not release reservations when provider delivery is uncertain.

**Acceptance cases:** same user/key/payment/route/action produces one mutation and the stored response; same key with a different payment or action returns 409; a pending operation is representable in Oracle; completed responses and normalized requests pass `IS JSON` checks.

## Task 7: Reconcile payment lifecycle and payout orchestration

**Files:** create canonical `backend/src/main/java/com/fluxpay/domain/PaymentStatus.java`; modify Payment, PaymentSnapshot, DbPaymentReader, PaymentConfirmationService, PayoutExecutionService, RecoveryService, PayoutController, PayoutProvider/PayoutCmd and status-bearing DTOs; remove the old lifecycle enum and lossy status conversion once migrated.

**Interfaces:** provider submission accepts a PayoutCmd containing the persisted attempt ID and stable provider idempotency key. Payment state is authoritative; attempt state describes each execution. Payout service orchestration delegates reserve/finalize transactions to separate injected components so proxy transaction boundaries apply.

- [ ] Add tests proving DRAFT, QUOTED, UNDER_REVIEW, REJECTED and CANCELLED cannot submit; only funded PROCESSING can submit initially.
- [ ] Persist attempt reservation, call provider outside DB locks, then finalize payment/attempt/operation/outbox atomically. Do not hold a DB transaction while waiting on provider I/O.
- [ ] Serialize duplicate submissions through the payment row and operation key. Pass `payout:<attemptId>` to the provider on replay.
- [ ] Represent uncertain outcomes as pending reconciliation; prevent automatic retry/refund from issuing money twice. Final known failures can be retried under a new explicit action key.
- [ ] Update payment status on completion/failure/refund. Remove flow-version compatibility during fresh-baseline work in Task 11.
- [ ] Make route switching accept a replacement quote belonging to the same payment/new route, validate expiry and allocation, and reject different net/fee allocations with REQUOTE_REQUIRED. Return the selected quote economics explicitly.
- [ ] Test one debit across retry, terminal-state rejection, concurrent submissions, switch-route constraints, refund replay and provider timeout ambiguity.

## Task 8: Unify events and activate durable delivery

**Files:** modify `backend/src/main/java/com/fluxpay/messaging/` envelope/codec/publisher/consumer/relay classes; create `OutboxService.java`, `OutboxDispatchJob.java`, `OutboxClaimService.java`; modify outbox repositories, payment and payout transaction services, Kafka configuration and event tests; delete InMemoryEventPublisher.

**Interfaces:** `OutboxService.enqueue(PaymentEventEnvelope envelope, int sequence)` persists a stable event and delivery row in the caller's transaction. `OutboxDispatchJob` invokes `OutboxRelay.relayOnce(int batchSize)` on a configurable fixed delay. KafkaTransport is the one low-level sender. A separate claim service owns short transactional claim/mark/retry methods.

- [ ] Add a producer-to-codec regression using an actual confirmation-generated event, not a hand-built fixture with a different format.

```text
envelope.eventType == Kafka topic == "payment.initiated"
envelope.eventId == outbox event ID
envelope.correlationId is nonblank
payload.schemaVersion == 1
payload.aggregateSequence == the persisted sequence
```

- [ ] Build all envelopes through one factory and codec. Add review-request topic support consistently to topic provisioning, envelope validation and timeline consumption.
- [ ] Replace direct domain Kafka sends with outbox enqueue calls. Change terminal response/event IDs to mean durable event IDs, not proof of Kafka acknowledgement.
- [ ] Schedule relay dispatch. Claim a bounded ordered batch, publish outside transactions, mark sent or retry, reclaim expired SENDING leases and keep per-payment sequence order.
- [ ] Test broker failure and recovery, worker crash after claim, acknowledgement loss, duplicate delivery and two concurrent dispatchers. A second event for a payment must not overtake its earlier pending event.
- [ ] Serialize quarantine records with ObjectMapper, including original payload and broker coordinates. Verify invalid JSON and escaping do not break quarantine publication.
- [ ] Run Kafka unit tests and add Oracle/Kafka integration coverage for confirmation -> outbox -> broker -> timeline. Simulated payout integration tests use test-only providers.

## Task 9: Remove local authentication bypass and obsolete FX fallback

**Files:** modify JwtAuthFilter, SecurityConfig, FxConfig, FxSnapshot/FxQuoteResponse/WalletConvertResponse, FxQuoteService tests, application YAML, `.env.example`, start-backend script and MockSegregationInventoryTest; delete the production mock FX source after tests migrate.

- [ ] Add HTTP regression tests for X-Local-User-Id alone, malformed JWT plus the header, and impersonation of another known user under default/local/development profiles. Expected result is 401 unless a valid JWT is supplied.
- [ ] Remove programmatic Profiles.of("local") authentication and permitAll branches. Keep normal JWT owner/admin checks in every environment.
- [ ] Remove mock/solo FX modes and stale runtime mock flags from the fresh API contract; test fixtures use injected FxSnapshotSource implementations.
- [ ] Verify the supported provider endpoint against its official API documentation at implementation time, then test the adapter's exact request URI and response parsing using an in-process HTTP server. Update the example URL accordingly; a local fixture alone does not prove that an external API base URL accepts a rates query.
- [ ] Reject retired runtime configuration explicitly with an actionable message rather than silently falling back to fake data.
- [ ] Replace the narrow annotation-string inventory test with behavior tests for security and production bean wiring; retain a small structural check only for retired production mock classes.

## Task 10: Make incomplete integrations explicit

**Files:** adapter payout simulators, DemoFundingService/config/controller dependencies, DefaultComplianceAssessor, KycService/document DTO/entity, development configuration, integration availability exceptions and tests.

- [ ] Apply the user's response to the development-behavior question; otherwise use the proposed retain-explicit-development-only design and state that assumption in the execution handoff.
- [ ] Rename retained fake payouts to SimulatedStandardBankProvider, SimulatedInstantPayoutProvider and SimulatedLocalPartnerProvider under `development`; normal provider discovery excludes them unless explicitly enabled.
- [ ] Make provider unavailability, compliance unavailability and missing KYC storage explicit 503 errors. Validate capability before entering an operation that would otherwise claim a completed external action.
- [ ] Keep development funding disabled by default and JWT-protected. Remove fake KYC storage URLs; metadata-only development responses explicitly indicate files were not stored.
- [ ] Limit failure simulation controls to development implementations; use test fixtures for success/failure/recovery tests.
- [ ] Test the full default context with no real provider credentials: application boots, FX uses the HTTP adapter, disabled external operations fail honestly, and no default always-approve assessor is active.

## Task 11: Replace migration history with a fresh, member-free baseline

**Files:** replace `backend/src/main/resources/db/migration/*.sql`; update affected JPA/native query mappings; update repository migration tests and `backend/src/test/resources/m2/run_oracle_tests.py` (rename directory to `wallet-ledger`); replace obsolete legacy-upgrade tests.

Fresh migration files:

```text
V001__identity_and_kyc.sql
V002__wallets_and_ledger.sql
V003__routing_payments_and_quotes.sql
V004__operations_outbox_and_events.sql
V005__compliance_policy_and_vectors.sql
```

- [ ] Capture the effective final schema requirements from entities and the old migrations, including keys, checks, JSON, indexes, version columns, account roles and retained policy/vector structures.
- [ ] Author clean CREATE statements in dependency order. Create payments without selected-quote FK first, create quotes, then add the FK. Remove temporary legacy tables and obsolete seed routes.
- [ ] Replace `m3_payment_operations`, `m3_outbox_delivery`, `m3_review_decisions` and member-prefixed constraints/indexes with business names. Remove `m3_flow_version` and associated legacy API branches.
- [ ] Define real route-code quote references and the operation pending/completed constraints from Tasks 4 and 6. Retain supported money precision and JSON validity checks.
- [ ] Use FLUXPAY_TEST and canonical ORACLE_TEST_* settings throughout integration tests. Remove member-specific flags/schema names and old migration hash assertions because old database upgrade compatibility is intentionally dropped.
- [ ] Replace upgrade-history tests with fresh migration, repeat-startup/no-new-migration, Hibernate validation and invariant tests. Retain meaningful RAW(16), append-only ledger, quote and outbox persistence coverage.
- [ ] Migrate only an isolated test schema first. Do not reset the user's application schema until this gate passes.

**Oracle acceptance:** IN_PROGRESS payment operations have null completion fields and valid normalized JSON; COMPLETED operations require valid response JSON/status; duplicate keys are rejected; invalid states/unbalanced application journals fail; UUID mappings round-trip.

## Task 12: Repair local provisioning, seed scripts and backend smoke tests

**Files:** create `scripts/seed-local.py` and shared script environment/infra helpers as needed; replace `scripts/seed-demo.py`; rename `scripts/seed_m2.py` to `scripts/seed-wallets.py` and `scripts/check_m2_ledger.py` to `scripts/check-ledger.py`; modify start/stop/test scripts, `.env.example`, README and Python tests.

- [ ] Make infrastructure mode explicit: Compose-managed or external Oracle/Kafka. In Compose mode invoke named services and container tools; external mode probes configured services without attempting to stop them. Remove the contradictory assumption that Compose starts Kafka but provisioning always needs a bare-metal Kafka CLI.
- [ ] Define one topic inventory matching Task 8, including review and quarantine topics.
- [ ] Seed local system user, required role/currency wallets and real route catalog deterministically. Admin bootstrap must use a narrowly scoped local provisioning path; public registration must continue rejecting client-controlled privileges.
- [ ] Register development customers with fullName and valid passwords sourced from local seed configuration. Count actual results, fail on API/DB errors, and verify idempotent reruns. Do not print tokens or passwords.
- [ ] Remove policy counts from seed output unless policy records were actually inserted. Keep the unfinished embedding work explicit.
- [ ] Make `test-all.py --suite backend` run backend checks only. Load env files before subprocess construction, use configured ports/base URLs and report skipped integration checks.
- [ ] Replace `smoke-1` broker messages with valid canonical event envelopes tied to seeded test payments, and assert persisted timeline rows rather than only producer exit codes.
- [ ] Remove or implement every exposed no-op argument; document reset responsibility in the dedicated reset helper, not an unused seed --reset flag.
- [ ] Run `python -B -m unittest discover -s tests` and the backend-only smoke command against the isolated test environment.

## Task 13: Rebuild the authorized local FluxPay schemas

**Files:** create `scripts/reset-local-db.py`, `tests/test_reset_local_db.py` and a local schema-provisioning SQL resource; document exact target discovery in README.

**Interfaces:** `reset-local-db.py --schema FLUXPAY` is dry-run only; `--execute` performs the verified reset. Allow FLUXPAY_TEST for isolated tests. Read administrative credentials from environment, never command-line output or committed files.

- [ ] Test that missing execute flag makes no DB changes, remote connections are rejected, SYS/SYSTEM and unrelated schemas are rejected, and the resolved connection/schema is checked before any destructive statement.
- [ ] Confirm the local database identity and actual FluxPay schema using read-only connection metadata. If the installation uses a different application schema name, resolve it explicitly before adding it to the reset target; never infer it from an arbitrary env string alone.
- [ ] Stop the backend/relay. Recreate only the authorized FluxPay application/test schemas and grants; a schema reset also replaces their Flyway history. Do not invoke `docker compose down --volumes`, which removes Kafka data too.
- [ ] Inspect FluxPay-owned local broker topics and reset only those required for the fresh local run, or use a fresh isolated broker. Never replay stale old-schema events into the new application tables.
- [ ] Start backend migrations against the empty schema, validate Hibernate mappings, provision system accounts and run the corrected seed process twice.
- [ ] Report exactly which local schemas/topic data were removed and whether any export was retained. The reset is destructive and should not be described as recoverable without a verified export.

No database command in this task runs during planning. The user's approval to wipe the local database is already recorded; implementation still validates the exact target and obtains only any tool-level permission that the environment requires.

## Task 14: Full acceptance, documentation and final inventory

**Files:** replace `backend/src/test/java/com/fluxpay/ProductionBootTest.java` with real full-context coverage; configure Maven integration-test execution in `backend/pom.xml`; add `backend/src/test/java/com/fluxpay/integration/BackendAcceptanceIT.java`; update README, docs/openapi-notes.md and supersede the two previous member-era design docs.

- [ ] Use Maven Failsafe for named `*IT` acceptance tests and a documented integration profile. Tests require dedicated ORACLE_TEST_* credentials and must never fall back to the application schema. Normalize renamed Oracle test activation settings.
- [ ] Full context covers repositories, security, FX provider wiring, operations, outbox scheduler and Kafka consumer. Keep default unavailable integrations distinct from explicit test/development providers.
- [ ] Exercise register -> JWT login -> KYC development fixture -> seed funds -> quote -> confirm -> simulated test payout -> timeline, then failure -> retry/switch -> refund. Use real Oracle and Kafka; simulated external provider/FX responses belong to tests.
- [ ] Assert no duplicate debit/refund, exact posting reversal, correct terminal payment state, stable event IDs, canonical envelopes, fresh-schema migration success, no local-header authentication and no default fake provider success.
- [ ] Run unit/script checks, then `./mvnw.cmd -f backend/pom.xml -Pintegration verify` with dedicated test environment configured. A required integration suite that was skipped is not a successful acceptance result.
- [ ] Run a final member-name/reference inventory across active Java, SQL, script names, configuration keys and test resources. Historical docs can retain quoted old names only when clearly superseded.
- [ ] Document actual supported behavior, explicit development toggles, unavailable external integrations, local reset/run commands and test requirements. Remove the inaccurate claim that every endpoint has a real production integration.
- [ ] Review changes by stage and report tests passed/failed/skipped, schema reset outcome, remaining intentionally unfinished integrations and any user-selected policy differences. Do not claim production payout/compliance readiness.

## Coverage checklist

| Audit finding | Planned resolution |
|---|---|
| Runtime FX mock and logging event fallback | Tasks 8-9 |
| Simulated providers, demo funding, placeholder KYC/approval | Task 10; no real integrations invented |
| Local authentication bypass | Task 9 |
| Quote/provider name mismatch and duplicate pricing | Task 4 |
| Wrong refund accounts and fee reversal | Task 5 |
| Divergent lifecycle and payout eligibility | Task 7 |
| Idempotency conflicts, invalid Oracle pending/response data | Tasks 6 and 11 |
| Event format mismatch, missing relay caller, direct Kafka in transactions | Tasks 7-8 |
| Dead interfaces/enums/overloads | Task 2 |
| Misplaced exceptions/adapters/handlers | Tasks 2-3 |
| Repeated error construction, clocks and system-user configuration | Task 3 |
| Member-based filenames and SQL identifiers | Tasks 2 and 11-12 |
| Missing Python helper and broken seed/smoke scripts | Tasks 1 and 12 |
| Misleading production boot and migration tests | Tasks 11 and 14 |
| Local database reset permitted; no production compatibility | Tasks 11 and 13 |
| Frontend and unfinished embedding work | Explicitly excluded throughout |

## Proposed review checkpoints

1. Confirm the design choices: source-currency fees, full-fee refund, and treatment of development-only integrations.
2. Review Tasks 1-3 before semantic changes: all naming/package changes compile.
3. Review shared business behavior and transaction boundaries in Tasks 4-10.
4. Review fresh schema acceptance before the authorized local application reset.
5. Review the backend acceptance report and outstanding real-provider work.

These checkpoints organize execution; they do not request permission again for work already approved by the user.
