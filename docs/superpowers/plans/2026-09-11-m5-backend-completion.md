# Member 5 backend completion implementation plan

> For agentic workers: execute using superpowers:subagent-driven-development with test-first implementation, focused reviews and final integration verification.

**Goal:** Implement and test the backend-only requirements of the revised M5 specification, including an isolated Oracle test application and detailed UAT tools.
**Architecture:** M5-owned horizontal classes implement local payment/decision ports, authoritative screening_cases with serialized heads and durable delivery, scoped JWT endpoints and generation-based native Oracle search. Test-only fixtures implement external ports; integrated startup fails closed without real adapters.
**Tech stack:** Java 17, Spring Boot 3.2.5, existing JDBC/Flyway/JUnit/Mockito dependencies, Python standard library.
**Spec:** C:/Users/Nitya/Downloads/05-member5-compliance-ai.md (SHA256 EC747F5F2D2FDBC277441500AB499FDC3DDDD9480A629D7F600A0F108CB09BE6).
**Approved exception:** User explicitly selected Ollama nomic-embed-text and 768 dimensions on 2026-09-11. Replace the specification's 1536 requirements for M5 policy vectors with 768; do not alter the unrelated document_embeddings table.

## Global constraints

- Preserve common/**, pom.xml, application YAML, V001 through V604, other members' code and frontend.
- Work against the user's current feature-branch files, preserving the dirty baseline. Do not commit, reset, push or apply DB migrations in the application schema.
- New code uses M5-specific names in controller/service/repository/beans/dto/config. No feature subpackages.
- Old M5 prototype components become inactive (profile m5-legacy) so their HTTP routes/providers cannot bypass or conflict with the new runtime. Leave old data intact.
- No fixture reader/sink/controller in the production artifact; no permissive integrated adapter fallback.
- Exact statuses: UNDER_REVIEW, PROCESSING, APPROVED, REJECTED. Never change PaymentStatus or move money.
- Test Java names M5*UnitTest, M5*WebTest, M5*ContractTest, M5*OracleTest. No Docker requirement; do not alter POM.
- Oracle verification requires an explicitly designated disposable schema. None has been supplied. Do not claim Oracle tests passed until actually executed there.
- No schema/users/grants created by launch/test scripts. Document manual DBA setup of FLUXPAY_M5_TEST as an example.
- Keep source excerpts, prompts and fixtures synthetic; tokens/credentials never printed or committed.
- Use apply_patch; every behavioral implementation gets an observed failing test first. Run selected tests with the existing cached Maven distribution and project .m2 repository.
- Current final build baseline is success with zero Java tests; this is not behavioral verification.

## Shared interfaces / ownership

Root creates config/M5ApiException and service/M5CaseContextReader only. All workers may consume them but must not edit them.
M5ApiException(int status,String code,String message,Map<String,String> fieldErrors), getters status(), code(), fieldErrors(); overload without map.
M5CaseContextReader: Map<String,Object> context(UUID paymentId, CurrentUser actor).

Compliance worker owns all NEW M5 compliance controller/service/repository/DTO/beans/config classes and their tests. Root owns the compliance DDL migration; worker must supply exact repository/schema contract early.
Vector worker owns all NEW M5 policy/vector/copilot controller/service/repository/DTO/beans/config classes, policy migration(s) and tests. It consumes M5CaseContextReader for authorized stored case context.
Harness worker owns M5SecurityConfig, M5ApiExceptionHandler, test-only M5Solo* application/fixtures/sink/controller/launcher, Python scripts and script/security tests. It consumes the compliance DTO/local-port interfaces and vector configuration; coordinate signatures before implementing dependent fixture construction.
Root owns disabling legacy components, new compliance DDL, cross-module fixes/review, documentation/Postman acceptance artifacts and verification.

## Task 1: Compliance local ports, rules and persistence

Files: new service/M5AssessmentPort.java, M5PaymentReader.java, M5ComplianceRulesEngine.java, M5ComplianceService.java, M5ReviewDecisionSink.java, M5ReviewDeliveryService.java; repository/M5ScreeningRepository.java; controller/M5ComplianceController.java; beans/dto/config M5-specific supporting types; matching unit/contract/Oracle tests.

Interfaces to publish first:
- M5AssessmentRequest(UUID assessmentId,long assessmentSequence,UUID paymentId,String expectedPaymentFingerprint).
- M5PaymentReader.readForAssessment(UUID): M5PaymentSnapshot; ownerOf(UUID): UUID.
- Snapshot includes immutable sender/wallet/payment/recipient IDs, amount/source+payout currencies/purpose, recipient snapshot/version/country, purposeAvailable flag, nullable authoritative KYC/history fields, observation/cutoff/zone. Known-null purpose differs from unavailable.
- M5AssessmentPort.assess(M5AssessmentRequest): immutable response. Expose controller replay flag without polluting stored immutable response.
- M5ReviewDecisionSink.deliver(M5ReviewCommand): M5DeliveryAck; exact fields from spec.
- M5CaseContextReader implemented by the case read service with owner/admin authorization.
- Fixture callback service recordDisposition(assessmentId,disposition,reviewReference).
- Delivery explicitly invoked after commit, with bounded retry/backoff and immutable payload.

Steps:
- [ ] Write/read failures for literal R1-R6 results, e.g. EUR 920 no HIGH_VALUE, EUR 921 HIGH_VALUE; null and nine-char purposes SHORT_PURPOSE; one MEDIUM stays LOW, two MEDIUM become MEDIUM; HIGH precedence; exact R1-R6 order.
- [ ] Implement configurable thresholds USD1000/EUR920/INR83500, Asia/Kolkata day semantics and known-null/unavailable handling. Validate positive amount <=4dp, configured currency, input completeness; persist config hash/version.
- [ ] Write behavioral replay/sequence/decision tests. Hand-checked expected behavior: assess A, replay A without reader; changed A ->409; B higher sequence reads fresh snapshot; stale new ID ->409; original A response survives B/manual decision.
- [ ] Implement canonical UTF-8 SHA256 fingerprints, head-then-case locking, fresh-transaction race recovery, durable decisions and disposition gating. One same-action/reviewer/reason replay returns identical decision ID; conflicting decision returns409.
- [ ] Implement current/prior case lookup, passport authorization, pagination stable by date+UUID and status/risk/reviewable filters with size1..100.
- [ ] Implement HTTP envelope/status/body contracts and exact domain error codes using shared M5ApiException.
- [ ] Add real Oracle test cases gated by explicit isolated-schema setup: concurrent initial head/assessment, sequence race, single review winner, delivery acknowledgment loss and stale refs.
- [ ] Run selected unit/contract tests; report actual test counts and Oracle pending separately.

## Task 2: Generation-based policy/vector/Copilot

Files: new config/M5VectorConfig.java and settings/providers; service/M5PolicyService.java, M5PolicyChunker.java, M5CopilotService.java; repository/M5PolicyRepository.java; controller/M5PolicyController.java, M5CopilotController.java; related M5 DTOs; additive policy migration and matching tests.

Interfaces:
- Exactly one selected frozen common.contracts.EmbeddingProvider, with M5-owned adapter methods to support document/query preprocessing and remaining deadlines.
- M5CaseContextReader supplies authorized stored context for optional paymentId; never reassess.
- settings embedding.* and policy.* plus existing fluxpay.embedding-mode; default768 and mock; ollama supported explicitly.

Steps:
- [ ] Tests first: NFC/LF/trim content-only hash, duplicate title-independent normalization, valid bounds64KiB/5000words/16chunks, question1000codepoints, chunk tails and sentence splitting with50overlap.
- [ ] Implement immutable document creation, duplicate error field existingPolicyDocumentId, list/detail pagination and index metadata.
- [ ] Preserve all existing documents/chunks/hash evidence in additive migration; unknown legacy spaces remain inactive. No old generation silently becomes searchable. Runtime must use canonical policy_documents/policy_chunks.
- [ ] Test vector finite/nonzero/768 and provider selection/failure mappings; mock deterministic unit vectors; HTTP provider tests with local controlled server. Ollama document/query prefixes; no network retries.
- [ ] Generate vectors outside publication transaction; total index deadline30s/per-call<=3s/remaining budget and DB bounds. Lock/recheck doc version+token, atomically publish complete generation; retain old generation; same configuration no-op replay.
- [ ] Query exact cosine filtered active generation/current space, stable ties, topK1..5, threshold0.35; 5s query budget. Exclude unindexed and other spaces.
- [ ] Test no-answer EXACT text and empty sources; mock flag; supporting <=300-character source passages; structured caseContext; no generated policy claims. Keep answer baseline extractive.
- [ ] Test Oracle vector round trips/order/generation races/rollback/space changes when dedicated schema provided; report pending otherwise.
- [ ] Run selected unit tests and report precise commands/counts.

## Task 3: Security, isolated fixture application and test tools

Files: config/M5SecurityConfig.java, M5ApiExceptionHandler.java; test config/M5SoloApplication.java, M5SoloFixtureConfig.java, M5MockPaymentReader.java, M5RecordingReviewDecisionSink.java, M5SoloFixtureSetup.java, M5SoloLauncherTest.java; test controller/M5SoloFixtureController.java; src/test/resources/m5 fixtures; scripts/m5_support.py, test_m5.py, start_m5_solo.py, seed_policies.py, verify_m5.py; Python tests; security Web tests.

Steps:
- [ ] Write HTTP tests using real JwtUtil/JwtAuthFilter: unsigned401 AUTH_REQUIRED, signed USER403 FORBIDDEN, ADMIN pass; foreign passport404, malformed JSON/filter/UUID errors preserve envelope.
- [ ] Implement ordered chain scoped only to M5 paths, stateless JWT, role restrictions and shared envelope handlers. Scope controller advice to new M5 controllers; no shared security edits.
- [ ] Implement explicitly imported test application, no broad main/service scan, test-only fixture ports, integrated adapters fail closed.
- [ ] Before Flyway, require M5_ALLOW_FIXTURE_SETUP=true and exact M5_TEST_SCHEMA==configured username==actual current schema; reject absent/ambiguous/shared declaration. Do not create schema or cleanup data.
- [ ] Insert/reuse collision-checked reserved synthetic users (real BCrypt), wallets, recipients and payment rows satisfying full current baseline constraints. Never insert assessed cases directly. Scenarios supply full snapshots LOW/two-MEDIUM/HIGH/foreign/unavailable with deterministic clock.
- [ ] Generate real signed1h ADMIN/USER tokens into explicit private M5_SOLO_TOKEN_FILE outside version control; output path only.
- [ ] Fixture controller metadata/scenario/disposition/delivery endpoints only under test; identity protected; sink deduplicates effects and supports acknowledgment-loss/stale simulation.
- [ ] Unit launcher skipped without m5.solo.launch=true. Python startup explicitly loads private env over stale inherited values, configures loopback8081(default configurable), owns/stops child process. No visible helper windows.
- [ ] test_m5 selects groups and fails if reports have zero executed tests or skipped Oracle selection; excludes launcher. No Docker/main-context for unit suite. Unit tests exercise wrapper behavior with controlled subprocess/filesystem doubles.
- [ ] seed_policies uses authenticated APIs and synthetic five-policy corpus; duplicate-safe/index replay. verify_m5 checks identity cycles, activation, decisions/retry, delivery dedup, stale sequence, security, policies/sources/no-answer using real API calls.
- [ ] Report exact fixture/launcher/API contracts to root for detailed UAT documentation.

## Task 4: Root migrations, compatibility cutover and integration

- [ ] Before production edits capture tracked/untracked baseline inventory and migration hashes. Do not apply migrations to FLUXPAY.
- [ ] Write compliance SQL based on worker schema contract, reserve local V605.. after listing migration files; fail safe BEFORE any DDL on unclassifiable existing screening history or non-seed legacy compliance data. Preserve exact V603 demo history in inactive old table. No fabricated reviewer/cycle data, no replay of old approvals.
- [ ] Disable old prototype route/service/provider scanning under m5-legacy; production activation must not permit legacy route bypass. Add focused context/route test proving correct controllers/providers selected.
- [ ] Validate ordinary unit groups then application/security integration and Python tools. Run all selected tests after workers finish; examine actual report counts.
- [ ] Produce detailed docs/M5-BACKEND-SETUP-AND-UAT.md, private-env template without credentials, signed-token Postman collection with current required bodies/fresh fixture metadata.
- [ ] Document manual SQL Developer schema setup, connection verification, launcher, unit/Oracle/API tests, vectors vs semantics and test-data lifecycle; no automatic schema creation/reset.
- [ ] Perform independent spec+quality review and address findings; re-run affected tests and full suite.
- [ ] Declare implemented/verified/pending separately. No claim of full Oracle or shared-upgrade acceptance until actual dedicated Oracle tests run.

## Verification commands

Use existing Maven distribution on this host; all workers coordinate test compilation (no simultaneous half-written shared types).
Maven: -o -B -ntp -Duser.home=C:/Users/Nitya -Dmaven.repo.local=C:/Users/Nitya/Desktop/Fluxpay/.m2/repository -f backend/pom.xml -Dtest=<specific M5 test class> test
Python: python -m unittest discover -s tests -p 'test_m5*.py'
Integration: scripts/test_m5.py --suite unit, then oracle only with explicit private isolated env.

