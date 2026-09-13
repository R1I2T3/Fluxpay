# Backend cleanup design

Status: proposed for review; no application changes or database reset performed.

## Objective and scope

Complete the backend cleanup discussed in the repository audit: remove obsolete code, retire runtime mocks where concrete implementations exist, remove member-based names, put classes in appropriate packages, consolidate duplicated business responsibilities, and fix the broken payment/ledger/event connections.

The user confirmed that there is no production deployment and the local database can be wiped. The target is a fresh FluxPay application schema, not compatibility with the previous local migration history. Frontend implementation and unfinished embedding/compliance integrations are excluded. No new bank, deposit, or external KYC provider is invented by this cleanup.

The current request authorizes creating this plan. Execution, including the already-agreed local database rebuild, happens when implementation starts; this document does not perform it.

## Approaches considered

1. **Staged consolidation with a fresh schema (recommended).** First establish reliable checks, then rename and reorganize, consolidate business behavior, replace migrations, and rebuild the local application schema. This addresses both structural debt and functional defects while keeping changes reviewable.
2. **Mechanical renaming only.** Smaller initial change, but leaves mismatched routes, invalid operation records, failed refunds, and disconnected events unresolved. Insufficient for the requested cleanup.
3. **Full backend rewrite or separate Maven modules.** Larger scope and avoidable implementation risk. Keep the current Spring Boot application and its functioning persistence, authentication, and ledger components.

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

## Incomplete integrations

Proposed default, matching the earlier request to remove mocks when a concrete implementation exists:

- Remove runtime mock/solo FX selection; use the existing HTTP FX provider. Tests inject their own deterministic sources.
- Remove the logging-only InMemoryEventPublisher fallback.
- Retain demo funding and payout simulators only under explicit development settings, off by default. Use a `development` package and names containing `Simulated` for fake payout implementations.
- A normal startup with no real payout provider is allowed, but payout submission returns `503 PAYOUT_PROVIDER_UNAVAILABLE` before reserving an execution or moving money for that submission. Startup and health reporting must not imply real payouts are available.
- KYC metadata-only development behavior is explicitly labelled; it must not manufacture a URL that pretends a file was stored. A request requiring document storage returns `503 KYC_STORAGE_UNAVAILABLE` when no storage implementation is configured. Existing metadata and administrative review remain distinguishable from uploaded files.
- The always-approve compliance assessor is development-only. Outside that mode, confirmation returns `503 COMPLIANCE_UNAVAILABLE`; unfinished compliance is not silently treated as approval.
- Development identity impersonation via X-Local-User-Id is removed in every mode. Development endpoints still require JWT authentication and existing authorization rules.

An optional question was sent about retaining explicit development behavior versus removing it entirely. If the user chooses complete removal, delete those components and their demo-only endpoints/tests, retaining the same unavailable responses for missing integrations. That choice does not block the independent rename, security, ledger, schema, and tooling tasks.

## Package responsibilities

Paths below are relative to `backend/src/main/java/com/fluxpay`.

| Package | Responsibility |
|---|---|
| `controller` | HTTP endpoints and request adaptation |
| `web/advice` | Domain-specific HTTP exception translation |
| `common/web` | Shared correlation IDs, validation errors, error response construction |
| `exception` | Application/domain exceptions |
| `service` | Application use cases and transaction coordination |
| `domain` | Shared business policies and lifecycle/preference enums |
| `common/contracts` | Interfaces consumed by application services |
| `adapter/fx`, `adapter/persistence` | External FX and persistent wallet adapters |
| `messaging` | Event contracts, codec, durable publishing, relay and consumers |
| `development` | Explicitly enabled demo behavior without authentication bypasses |
| `config` | Bean wiring and validated application settings only |
| `beans`, `repository`, `dto` | Existing persistence entities, repositories, and HTTP DTOs |

Do not move every entity or query service merely for stylistic uniformity. Keep complementary abstractions such as LedgerJournalService and PersistentLedgerWriter.

## Canonical business behavior

### Quotes and routing

RoutePreference expresses CHEAPEST, BALANCED, or FASTEST; it is not a provider identity. Persist the actual payout route code on each quote. Both quote creation and route recommendations use the same pricing and ranking policies over active PayoutRoute rows.

The source amount is a gross source-currency amount. Route base fees are defined in source currency for this cleanup. Calculate net source amount = gross - fee, offered FX = market FX adjusted for spread, then recipient amount = net source amount * offered FX. Preserve scale 4 and HALF_EVEN for monetary outputs. Reject nonpositive net/recipient amounts. Balanced ranking uses the existing RouteRecommender recipient/speed/success scoring; all endpoints use that same policy. Treat this fee-unit decision as part of the proposed design, not as a claim that the old implementations agreed.

A quote freezes route identity, fee, rate, recipient amount, and expiry, including any customer-facing provider surcharge. Confirmation and payout submission validate the selected quote and route; they do not independently re-price it. Subsequent route edits affect newly generated quotes only. Provider-reported settlement costs cannot silently change accepted customer fees. Route switching must use an explicit replacement quote for the new route and must not silently charge an extra fee. If the stored source-currency net and fee differ, reject the switch as `409 REQUOTE_REQUIRED`; use a new payment for a different funding allocation. Persist the accepted replacement quote and return its changed recipient amount.

### Payments, accounts and journals

Use one PaymentStatus enum for payment lifecycle states: DRAFT, QUOTED, UNDER_REVIEW, PROCESSING, COMPLETED, FAILED, REFUNDED, REJECTED, CANCELLED. PayoutAttemptStatus remains separate because it describes an individual attempt.

Only a confirmed, funded PROCESSING payment is eligible for its first payout; DRAFT, QUOTED and UNDER_REVIEW must not be mapped to eligible states. Retry/switch requires a failed attempt, an unreversed posting, and explicit payment-state checks. Attempt completion/failure and refund update the payment row in the same transaction as their durable state changes.

A shared system-account service resolves accounts by currency and role using one configured system user. Production posting does not depend on demo configuration. Seed required clearing and revenue accounts explicitly. Resolve refund accounts and amounts from the persisted original posting snapshot, never by reusing the customer wallet as the clearing wallet.

All complete business postings go through LedgerJournalService. PersistentLedgerWriter remains the individual entry writer. On a final failed-payment refund, reverse the original three legs: debit clearing by net, debit fee revenue by fee, credit customer by gross. Omit zero-valued fee legs. This is the proposed full-fee refund policy; do not debit clearing by gross when it originally received only net. Stable reversal keys and journal metadata make replay idempotent.

### Idempotency

Share request normalization, replay validation, and response serialization without forcing wallet and payment operations into a generic framework or a single table.

Keep wallet_operations and payment_operations as domain-owned persistence. Payment operations gain an explicit IN_PROGRESS/COMPLETED state, nullable completion fields while pending, and valid JSON constraints. Store normalized JSON objects and structured response JSON; a plain UUID or empty string is not an operation response.

Use a consistent maximum of 255 characters for Idempotency-Key. For payment operations, a key is unique per user across payment mutations; the normalized request includes action, payment ID, route and quote where relevant. An identical key/request replays the stored result, and reuse for another action/payment/route is a 409 conflict. Wallet operations retain their documented operation scope but enforce request comparison. Race recovery happens after the losing transaction rolls back, not inside a failed JPA transaction. An in-progress reservation is an explicit retryable conflict; no operation is released after an external payout may have succeeded.

### Messaging and provider execution

Use one event envelope: eventType, eventId, paymentId, correlationId, occurredAt, payload. eventType equals the unversioned Kafka topic; payload carries schemaVersion and aggregateSequence. Generate an event ID once and reuse it in the outbox row and payload. Register review events explicitly even though a real review integration remains unfinished.

Domain transactions persist state plus an outbox event; they never synchronously publish Kafka and assume rollback can undo delivery. A scheduled relay claims a bounded batch in a short transaction, publishes outside it, then marks success/retry in another transaction. Reclaim expired SENDING leases; prevent later events overtaking an earlier unsent event for the same payment. Duplicate delivery is expected and consumer event IDs remain idempotent.

Payout orchestration follows the same boundaries: reserve and commit an attempt, call the provider outside the DB transaction, then finalize payment/attempt/outbox together. Include `payout:<attemptId>` as the provider idempotency key. An uncertain provider result remains pending reconciliation and must not automatically create a second attempt or refund potentially paid funds. Current development simulators can implement deterministic replay; a future real provider must implement this contract before activation.

Quarantine records use ObjectMapper and retain the original payload plus source topic/partition/offset. Broker availability and delivery are verified independently of the UI.

## Database reset and baseline

Replace the historical create/alter/legacy-upgrade chain with ordered fresh-schema migrations for identity/KYC, wallets/ledger, routing/payments/quotes, operations/outbox/events, and the existing unfinished policy/vector structures. Do not remove policy/vector DDL merely because its application integration is unfinished.

Remove member tags from table, column, constraint, index, test-schema, and script identifiers. Examples: m3_payment_operations -> payment_operations; m3_outbox_delivery -> outbox_delivery; m3_review_decisions -> review_decisions. Remove flowVersion/legacy-payment compatibility branches now that only the fresh schema is supported. Preserve public UUID string representations; a broad payment-ID type rewrite is not required by this cleanup.

Rebuild only the local FluxPay application schema and the dedicated FLUXPAY_TEST schema. Resolve the actual configured database and schema before resetting. Keep application and test credentials separate. The reset helper must print a dry-run target, reject remote/system schemas, stop the app/relay before reset, and require an explicit execute flag. The user's local reset authorization need not be requested again when these conditions are met.

Existing Kafka events can outlive a database reset. Reset only verified FluxPay-owned topics/consumer offsets on the local development broker as part of the documented rebuild, or use a fresh task-specific broker for acceptance. Do not wipe unrelated topics or all Compose volumes.

## Verification and completion

- Full default application context boots with real repositories, security, configuration, and messaging components.
- Header-only authentication fails in every profile, including invalid-Bearer-plus-local-header requests.
- Mock FX and logging event fallback are absent from production paths.
- Fresh Oracle migration, Hibernate validation, valid operation JSON, journal balance/replay, payment transitions, and Kafka delivery pass integration tests.
- First-run seeding creates the accounts it reports, uses the real registration contract, and fails on errors.
- A backend-only verification command reports run/skipped/failed counts and does not claim success when required integration checks were skipped.
- No active member-based class, package, SQL object, configuration key, or script name remains; historical documentation is clearly marked superseded.
- No frontend or unfinished embedding implementation is added.

Implementation plan: [backend-cleanup.md](../plans/2026-09-13-backend-cleanup.md).
