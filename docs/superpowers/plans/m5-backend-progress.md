# Plan: docs/superpowers/plans/2026-09-11-m5-backend-completion.md

User approved revised backend implementation and 768-dimensional Ollama exception. No isolated Oracle schema exists/is designated yet; no DB mutations authorized for shared FLUXPAY.

Ruling: use 768 for M5 policy vectors and model-space validation (explicit user approval); unrelated V501 document_embeddings stays1536.
Ruling: preserve current feature-branch dirty files as baseline; no auto commits/resets or new task/worktree. Task targets this folder.
Ruling: delegate disjoint implementation ownership concurrently per active developer multi-agent instructions. Communicate shared contracts before dependent implementation; review each scope and run full integration tests.
Ruling: backend only. Frontend implementation and actual M3 adapters remain out of this request; local fixture ports and backend contract simulations are included.

Preflight cross-task checks:
| Tasks | Shared boundary | Decision |
|---|---|---|
|1/2|authorized stored case context|root M5CaseContextReader interface; compliance implements, copilot consumes|
|1/3|payment snapshots, review ports|compliance publishes exact types early; fixture worker consumes|
|1/4|screening schema/JDBC|compliance publishes DDL contract; root writes additive migration|
|2/3|vector beans, policy API|vector publishes explicit imports and settings for solo fixture app|
|1/2/3|domain API errors|root M5ApiException; scoped handler owned by harness|
|2/4|policy migration version/order|policy owns V606+, root compliance V605; check all local versions before creating|
|1|rule/persistence tests vs required statuses|use new statuses and exact reasons, never oldprototype assertions|
|2|vectors/tests vs spec dimensions|approved768 exception applied everywhere in M5|
|3|launcher vs tests|launcher opt-in; ordinary suites never wait; nozero-test success|
|4|legacy rows vs fresh chain|V603 olddemo rows preserved inactive; unknown history blocks upgrade|

Task1 implementing: compliance_impl. Task2 implementing: vector_impl. Task3 implementing: harness_impl. Task4 implementing migrations, legacy isolation, docs and verification.

Root evidence:
- Protected baseline captured for 39 files (common, POM, YAML, V001..V604) in m5-protected-baseline.json. This includes the user's pre-existing edits, not pristine HEAD.
- M5LegacyIsolationUnitTest initially failed with active prototype beans (1 executed/1 failure); after profile gating passed. Additional guard test failed because explicit m5-legacy profile could start; after early configuration guard all 3 tests passed, no skips. Expected rejected-context warning is intentional.
- Windows sandbox javac getRealPath denied cached spring-orm JAR. Approved normal-access focused Maven reruns succeeded. No database connections were used.
- V605 additive SQL written with pre-DDL history preflight, required cycle metadata, constrained case/head/command relationships. Not executed: no isolated schema designated.
- 18 legacy M5 controllers/services/providers/repositories gated; shared security unchanged. Explicit legacy activation rejected by new M5LegacyGuard.
- Setup guide and private-env template drafted, explaining manual test-user provisioning. User asked to supply test schema and private env-file path after setup; secrets are not requested in chat.
