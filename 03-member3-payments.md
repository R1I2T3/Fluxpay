# FluxPay Member 3 - Recipients, Payments and Quotes

> Revised 2026-09-10 after logical and isolation review. This document specifies proposed implementation behavior; it does not claim that the code or integrations exist. Backend: Java 17 / Spring Boot 3.2.5 horizontal layers. Frontend: Oracle JET 16.1 TypeScript business files under `frontend/fluxpay-ui/src/js`. Oracle and Kafka run on bare metal.

## 1. Review findings and chosen resolutions

| Problem in the earlier spec | Resolution in this revision |
|---|---|
| Missing wallet reader blocked standalone development, and a balance alone could not prove wallet ownership | M3-owned wallet port with complete wallet identity; local fixtures implement it until a production adapter is available |
| A payment sent for review was debited without a defined release/refund flow | Review and block outcomes never debit; only approved, executable confirmation posts funds |
| A single debit had no balancing accounting entry | A source-currency journal debits the customer and credits payout clearing plus fee revenue in the same Oracle transaction |
| The check before debit could race with another payment | The persistent posting adapter locks and rechecks available funds in the transaction; payment locks alone are insufficient |
| Same idempotency key with a different quote could silently replay | Store the normalized request and original response; compare user, payment, quote and operation type before replay |
| Refresh could change an already-issued quote ID | Quotes are immutable; refresh creates a new generation and new IDs |
| BALANCED score was unspecified and CHEAPEST was not always cheapest | BALANCED recommends its named route; cost preference sorts by actual recipient amount, including small transfers |
| Duplicate source-currency and incompatible legacy-status fields could drift | Map existing `currency` to source currency; explicitly distinguish legacy rows before interpreting status |
| Kafka publication could be lost after a committed debit | Insert an outbox event atomically, then relay with acknowledgment and retry; consumer effects require deduplication |
| Seed retries could create repeated drafts | Draft creation also requires an idempotency key |
| Component tests were conflated with integrated acceptance | Three separate completion levels: standalone, adapter verification, combined integration |

All choices below are proposals for implementation, including new ports, review behavior, demo fees and event contracts. Existing frozen interfaces retain their signatures. Other members' adoption of a proposed contract is a later integration requirement.

## 2. Horizontal layout and scope

Backend paths are relative to `backend/src/main/java/com/fluxpay/`. Ownership is per file within shared technical packages; do not create feature subpackages.

| Layer | Proposed M3-owned files |
|---|---|
| `controller` | `RecipientController.java`, `PaymentController.java` |
| `beans` | `Recipient.java`, `Payment.java`, `PaymentQuote.java`, `M3PaymentOperation.java`, `M3ReviewDecisionRecord.java`, `M3OutboxEvent.java`, `M3OutboxDelivery.java`, `PaymentLifecycleStatus.java`, `RecipientStatus.java`, `PaymentPurpose.java`, `QuoteRoute.java` |
| `dto` | `RecipientRequest.java`, `RecipientResponse.java`, `DraftPaymentRequest.java`, `PaymentResponse.java`, `QuoteResponse.java`, `ConfirmPaymentRequest.java`, `PaymentPageResponse.java`, `M3WalletSnapshot.java`, `M3PostingAccounts.java`, `M3ExecutionSnapshot.java`, `M3ReviewDecision.java`, `M3PaymentEvent.java` |
| `repository` | `RecipientRepository.java`, `PaymentRepository.java`, `PaymentQuoteRepository.java`, `M3PaymentOperationRepository.java`, `M3ReviewDecisionRepository.java`, `M3OutboxRepository.java`, `M3OutboxDeliveryRepository.java` |
| `service` | `RecipientService.java`, `PaymentService.java`, `QuoteService.java`, `PaymentConfirmationService.java`, `PaymentPostingService.java`, `M3ReviewService.java`, `M3OutboxRelay.java`, `M3KafkaTransport.java`, `PaymentReaderAdapter.java`, `M3WalletPort.java`, `M3PostingPort.java`, `M3ExecutionReader.java`, `M3TransportPort.java` |
| `config` | `M3PaymentConfig.java`, `M3ApiExceptionHandler.java`, `M3BusinessException.java` |

Dependency direction: controllers call services; services use repositories and injected interfaces; repositories persist entities; HTTP boundaries exchange DTOs. Adapters that perform transport or posting belong in `service`; configuration wires them. Use constructor injection for services, ports, `Clock`, ID generation and transaction collaborators.

Tests mirror horizontal packages under `backend/src/test/java/com/fluxpay/` and use M3-specific class names. Do not import M1/M2/M4/M5 implementation classes. Do not edit shared `package-info.java` files or claim ownership of an entire layer.

Other M3 files:

- `backend/src/main/resources/db/migration/V601__m3_payment_extensions.sql`
- `backend/src/main/resources/db/migration/V602__m3_payment_quotes.sql`
- `backend/src/main/resources/db/migration/V603__m3_operations_outbox.sql`
- `backend/src/test/resources/m3/**`
- `backend/src/test/java/com/fluxpay/config/M3TestApplication.java` and `M3FixtureConfig.java`
- M3 fixture adapters/tests in the matching test-side `service` and `repository` packages
- `scripts/seed_m3.py` and `scripts/run_m3_checks.py`
- Frontend files in section 10
- `docs/03-member3-payments.md`

Version numbers are provisional allocations, not evidence that V601-V603 are free on every branch. Check repository migrations and the target's Flyway history before rollout. Existing V001/V101/V201/V301/V401/V501 remain unchanged. Do not run historical lower-numbered additions by silently enabling out-of-order migrations; coordinate one combined migration ordering across members.

## 3. Dependency contracts and isolation

### Existing signatures

```java
public interface KycGate { boolean isVerified(UUID userId); }
public interface FxRateProvider { BigDecimal rate(String from, String to); }
public interface ComplianceAssessor {
  ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency);
}
public interface LedgerWriter {
  void append(UUID walletId, String entryType, BigDecimal amount,
              String currency, String idempotencyKey);
}
public interface EventPublisher {
  void publish(String topic, Object payload, String correlationId);
}
```

The current compliance verdicts are `APPROVE`, `REVIEW` and `BLOCK`. The writer returns no entry ID. The current `InMemoryEventPublisher` only logs; it neither retains an assertable list nor provides durable delivery.

### New M3-owned boundaries

Define these locally in the layers above so M3 compiles without waiting for new shared interfaces:

- `M3WalletPort.findOwned(userId, walletId)` returns `Optional<M3WalletSnapshot>` containing wallet ID, owner ID, currency, available funds and customer-account eligibility. Its production adapter must obtain authoritative identity, not infer ownership from a balance. Its transactional `lockPostingAccounts(userId, walletId, currency, gross)` method locks customer/payout-clearing/fee wallets in UUID order, verifies their roles, currency, identity and available funds, and returns `M3PostingAccounts` with those validated IDs. System IDs are resolved by configuration, not supplied by the HTTP client.
- `M3PostingPort.postApprovedPayment(...)` accepts payment ID, owner ID, wallet ID, source currency, gross source amount, fee and quote expiry. `PaymentPostingService` implements it using `M3WalletPort.lockPostingAccounts` and the frozen writer: recheck expiry after acquiring locks, validate the complete journal, then post within the caller's Oracle transaction. Its result establishes committed effects only when that enclosing transaction commits.
- `M3ExecutionReader.findExecutable(paymentId)` returns an immutable `M3ExecutionSnapshot` only for `PROCESSING` with a committed posting and selected quote. Fields include payment/sender/wallet IDs, selected quote ID and generation, recipient payout snapshot, gross/fee/net source amounts, both currencies, offered rate and recipient amount. `UNDER_REVIEW` is never executable.
- `M3TransportPort.send(event)` completes successfully only when the broker acknowledges the send; it must report serialization, timeout and broker errors to the relay.

M2's proposed `WalletBalanceReader.available(userId, currency)` alone is insufficient for a request carrying `walletId`. Do not pretend that interface supplies identity or locks. A production adapter needs an agreed richer wallet query and transactional posting behavior. M3's fixture can implement the full local ports today; the same contract tests must later run against the real adapters.

For M4, publish an agreed shared reader or event schema before combined deployment. The local `M3ExecutionReader` supplies the contract-test reference and adapter point. Adding `common/contracts/PaymentReader.java` remains a coordinated addition; standalone M3 does not import a nonexistent shared file.

A production posting implementation may wrap the frozen `LedgerWriter` with M3 journal orchestration and an agreed M2 wallet/system-account adapter. It must use the same Oracle datasource and transaction manager, join the caller's transaction and never use independent commits or `REQUIRES_NEW` per ledger line. A remote wallet service would require a different transaction design and is outside this proposal.

## 4. Oracle schema and legacy data

Current schema facts:

- V301 defines `recipients(id, user_id, name, account_ref, created_at)` and `payments(id, sender_wallet_id, recipient_id, amount, currency, status, created_at)`, plus payout tables.
- V501 already defines `outbox_events(id, topic, payload, created_at)` and a vector table.
- Existing status defaults to `CREATED`. The shared `PaymentStatus` enum represents a different routing lifecycle.

### V601: extensions without invented historical facts

Recipients gain nullable `bank_name`, `country`, `currency`, and non-null `status`, `profile_complete` and `version`. Existing recipients start incomplete and unavailable for new drafts until the owner supplies missing details. New profiles require country and supported currency. Do not guess country/currency from an account string or enable incomplete historical recipients.

Add `UNIQUE(user_id, account_ref, country)` after checking existing complete rows. Trim account references; preserve meaningful characters and leading zeros. Country/currency use uppercase validated codes. Recipient DTOs use `name` and `account` mapped to existing `name` and `account_ref`.

Payments retain `sender_wallet_id`, `amount` and `currency`, mapped to `sourceWalletId`, `sourceAmount` and `sourceCurrency`. Do not add another source-currency or source-amount column.

Add `m3_flow_version` (existing rows default 0; M3 inserts explicitly use 1), `sender_id`, `payout_currency`, `purpose`, `preference`, `recipient_version`, immutable `recipient_snapshot`, `selected_quote_id`, `current_quote_generation`, `quote_generation_counter`, `event_sequence_counter`, `posting_snapshot`, `posted_at`, `version`, `updated_at`, and nullable review reference/approval expiry/fingerprint fields. JSON snapshots use CLOB with valid-JSON checks. Generation/event counters start at zero and only increase under the payment lock; clearing a current-generation pointer after review does not reset either counter. The posting snapshot records account IDs, amounts, currencies and deterministic per-line keys in the same transaction as the posting.

Legacy rows retain their original status and may have null new business fields. Read `status` as a raw persisted string and interpret it as `PaymentLifecycleStatus` only when `m3_flow_version=1`. History shows legacy status explicitly with `legacy=true`; every M3 mutation of a legacy row, including cancel, returns `409 LEGACY_PAYMENT`. Do not map unknown legacy statuses with an enum converter that crashes reads, or reclassify an old `CREATED` payment as an unfunded draft.

For version-1 rows, enforce the M3 allowed statuses, non-null business fields, positive amount, valid enum/currency values, and ownership checks in services. Check constraints must explicitly reject nulls where required; do not rely on nullable SQL comparisons. New rows snapshot recipient payout details. New status/ownership indexes support paginated history.

Derive historical sender identity through the wallet relationship where available; display ownership through the same join if the new field is absent. Historical amounts, purposes and beneficiary details must not be fabricated.

Preflight existing schema/data before the first DDL statement. Oracle DDL performs implicit commits, so a migration with several ALTER statements is not rolled back as one unit. Document partial-failure recovery; do not automatically repair Flyway history or rerun partially applied DDL against shared data. See [Oracle COMMIT behavior](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/COMMIT.html).

### V602: immutable quote generations

Create `payment_quotes` with UUID ID, payment ID FK, positive generation number, route, market rate, spread bps, offered rate, source-currency fee, recipient amount, ETA, recommendation flag, policy version, created time and expiry. Use `UNIQUE(payment_id, generation, route)` and a payment/generation index.

Each refresh allocates the next `quote_generation_counter` value, inserts three new rows with new IDs, and points `current_quote_generation` to that value. Superseded rows remain immutable for audit and can never be confirmed. Preserve selected quotes permanently with the payment; do not cascade-delete confirmed financial records through an API.

After the quotes table exists, add a nullable FK from `payments.selected_quote_id` to `payment_quotes.id`. A nullable cyclic relationship is insertable: create the payment, create quotes, then select one. Always check the quote's payment ID in the service as the simple FK alone does not establish that match.

Map Java UUIDs explicitly to `RAW(16)` and test on Oracle. Money uses `NUMBER(19,4)`, rates `NUMBER(19,6)`. Map timestamps with an explicit UTC JDBC/session convention and injected UTC clock; `TIMESTAMP` alone contains no timezone.

### V603: operations and outbox delivery

Create `m3_payment_operations` with user ID, operation type, client key (1-64 characters), operation ID, normalized request JSON, outcome HTTP status, response-data JSON, payment ID and timestamps. Unique key is `(user_id, operation_type, client_key)`. Persist only completed replayable outcomes; an in-flight row is inserted and completed within one transaction and rolls back on failure.

Normalize draft payloads by the validated UUIDs, canonical four-decimal amount, uppercase currencies, enum names and all request fields; confirmation payloads include payment and quote IDs. JSON key ordering and equivalent valid decimal spellings must not cause false conflicts. Reject blank/invalid keys instead of altering them silently. For a stored error outcome preserve error code, message and field errors as well as HTTP status; regenerate the current request correlation ID in its envelope.

Create `m3_review_decisions` with unique decision ID, unique active review reference, payment ID, normalized decision, receipt fingerprint/expiry, accepted timestamp and result snapshot. Persist the decision and payment transition atomically. One review reference accepts one decision; replay compares the saved decision payload even if the payment has since advanced.

Use existing `outbox_events` for immutable payloads. Create `m3_outbox_delivery` with event ID FK/PK, payment ID, aggregate sequence, state (`PENDING`, `SENDING`, `SENT`), attempt count, next-attempt time, lease expiry, claim token, bounded last error and sent time. Add unique payment/sequence and retry/lease indexes.

The M3 relay selects only rows registered in `m3_outbox_delivery`; it must never pick up another member's unregistered outbox event. Coordinate any later shared relay so an event has one delivery owner. No V501 edits or competing `outbox_events` creation.

## 5. Recipient and draft behavior

Recipient create/update accept owner-editable name, account, bank, country, currency and optional status. In this API, `BLOCKED` means the owner's disable switch; compliance restrictions remain controlled by the assessor and cannot be cleared by an owner update. Create defaults to active and complete after validation. Updates require an expected version and increment it. Missing/foreign records return `404` consistently.

A draft request contains `walletId`, `recipientId`, `amount`, `sourceCurrency`, `payoutCurrency`, `purpose` and `preference`. Send UUIDs and decimal strings. Require `Idempotency-Key` for draft creation as well as confirmation.

Validate request syntax/enums/precision first, then ownership and exact replay; on a new draft check KYC, active complete recipient, wallet identity/eligibility, matching source-wallet currency, matching recipient payout currency, supported corridor, valid fee/amount and advisory available funds. A successful draft holds no funds and makes no promise about a later balance.

Supported demo currencies are USD, EUR and INR, with distinct source/destination currencies. Reject unsupported/same pairs rather than returning a generic fixed FX rate. Purposes are `FAMILY_SUPPORT`, `EDUCATION`, `BUSINESS` and `SAVINGS`.

Snapshot recipient data/version and the draft financial request. Draft financial fields are immutable; changing beneficiary, amount, wallet or currencies requires a new draft. Recheck recipient version and active/complete state before quote generation and confirmation. A changed profile produces `409 RECIPIENT_CHANGED`, requiring a new draft. Lock recipient updates against confirmation using the same row-lock discipline; no payout may resolve beneficiary details from the subsequently edited live profile.

## 6. Quotes and deterministic policy

Use one FX rate per generation for all three routes. Require a positive finite rate and representable values. Provider failure is `503 FX_UNAVAILABLE` and leaves existing quotes unchanged. A generation expires 15 minutes after issuance; it is valid only while `now < expiresAt`. Expose `serverTime` with the response for UI countdown correction.

Proposed demo fee schedule: the following fixed fees are units of the source currency for every supported demo corridor (for example, 1.99 EUR for an EUR source). Display the currency explicitly. This is a deterministic demo policy, not a claim about commercially appropriate production fees; a production tariff is a separate configured policy.

| Route label | Spread | Source-currency fee | ETA |
|---|---:|---:|---:|
| CHEAPEST | 30 bps | 1.99 | 1440 minutes |
| BALANCED | 60 bps | 0.99 | 240 minutes |
| FASTEST | 110 bps | 2.99 | 15 minutes |

Because all three quotes must be comparable, reject a draft/quote generation unless the source amount exceeds every applicable route fee. Reject excess input precision, overflow and zero rounded recipient proceeds. Never silently round an invalid request.

```text
offeredRate     = round6(marketRate * (1 - spreadBps / 10000))
netSourceAmount = sourceAmount - fee
recipientAmount = round4(netSourceAmount * offeredRate)
```

M3 uses BigDecimal and HALF_UP for these quote calculations. Pass already scaled money to the writer; do not round it again using M2's separate FX-conversion policy. Display formatting must never feed back into posting calculations.

At source 500.0000 USD and market rate 83.200000 INR/USD:

| Route | Offered rate | Fee USD | Recipient INR |
|---|---:|---:|---:|
| CHEAPEST | 82.950400 | 1.9900 | 41310.1287 |
| BALANCED | 82.700800 | 0.9900 | 41268.5262 |
| FASTEST | 82.284800 | 2.9900 | 40896.3684 |

Recommendation/order:

- CHEAPEST preference sorts by descending actual recipient amount, then ascending ETA, then route name. The first is recommended. At small amounts BALANCED can beat the route labelled CHEAPEST; the UI labels the actual best return accurately.
- FASTEST preference sorts by ascending ETA, then descending recipient amount, then route name.
- BALANCED preference puts the BALANCED route first, then sorts the remainder by descending recipient amount and route name. It is a fixed compromise policy with reason "balanced route policy", not an unspecified optimal score.
- Exactly one quote is recommended. Store the calculation policy version with the generation.

Under the payment lock, POST quotes from `DRAFT` or `QUOTED` returns the current complete unexpired generation (200). If none exists or it is expired, insert one new generation, set QUOTED and return 201 in the same transaction. This makes network retries safe and serializes refresh against confirmation. An incomplete generation is an integrity error and rolls back; never return two cards as a valid generation.

GET quotes is read-only: return the current generation with expiry metadata, or 404 if none exists. Confirmation with a superseded quote returns 409; a current expired quote returns 410. A retry of an already completed confirmation follows operation replay before any current expiry check.

## 7. Confirmation, review and money movement

### State machine

```text
DRAFT -> QUOTED
DRAFT | QUOTED -> CANCELLED
QUOTED + APPROVE -> PROCESSING       (post journal + initiated event)
QUOTED + REVIEW  -> UNDER_REVIEW     (no posting; review-request event only)
QUOTED + BLOCK   -> REJECTED         (no posting or executable event)
UNDER_REVIEW + reject -> REJECTED    (no refund required)
UNDER_REVIEW + approve -> DRAFT     (fresh quotes and user reconfirmation)
PROCESSING -> COMPLETED | FAILED    (later payout integration)
FAILED -> REFUNDED                  (only after compensating journal)
```

A failed attempt that will be retried stays PROCESSING; FAILED means execution has definitively failed. REFUNDED follows a completed compensation, not a failed attempt or mere status edit. Terminal execution/refund consumers belong to later integration and are excluded from standalone M3 completeness.

### Confirmation transaction and replay

A nontransactional facade calls a separate transactional service bean; do not rely on self-invoked `@Transactional` methods. Rollback and retry/replay recovery happen outside that transaction.

1. Authenticate, validate key/body, verify payment ownership, then check completed `CONFIRM` operation by user/key. Exact normalized `(paymentId, quoteId)` replay returns stored HTTP status and data even after quote expiry or later state changes. A changed quote/payment is `409 IDEMPOTENCY_CONFLICT`. Correlation ID belongs to the current request envelope.
2. For a new operation, claim the unique operation row and lock the payment with `PESSIMISTIC_WRITE`, retaining `@Version` as a secondary guard. Recheck state, replay/races, recipient ownership/version/active state, KYC, wallet identity and selected current quote.
3. Reassess compliance with a bounded timeout (three seconds in the demo adapter). A fresh BLOCK always wins. A valid review-approval receipt may replace a repeated REVIEW verdict only; it cannot override BLOCK. Provider timeout is 503 with rollback, never implicit APPROVE.
4. For BLOCK, record REJECTED and a replayable 422 outcome with `PAYMENT_BLOCKED`. For REVIEW, record UNDER_REVIEW, a review reference, replayable 202 data and one review-request outbox event. Neither path calls the posting port.
5. For APPROVE, lock the needed wallet rows in consistent UUID order via the posting adapter. All M2/M3 multi-wallet writers must share that ordering. Recheck available funds under lock; advisory reads from draft are not sufficient. Check quote expiry again after lock waits and immediately before posting; this is the quote acceptance point. Do not reprice or check expiry after a durable success.
6. Post the full journal once through the persistent writer, set the selected quote and PROCESSING, insert the initiated outbox event plus delivery record, and store replayable 200 response data. Commit all Oracle changes together.

Any exception before commit rolls back the operation, journal, wallet deltas, payment update and outgoing records. Do not catch a posting error and commit a partial outcome.

Use deterministic ledger keys `m3:<paymentId>:customer`, `m3:<paymentId>:clearing` and `m3:<paymentId>:fee`. A duplicate writer key with a changed posting payload must fail. Same user/key races load the winner only after rollback in a fresh transaction. Different keys racing to confirm the same payment serialize on its lock: one may succeed, later requests receive 409. Lock timeout can return `409 RETRY` after rollback.

### Balanced source-currency posting

| Account | Currency | Debit | Credit |
|---|---|---:|---:|
| Sender customer wallet | Source | gross | 0 |
| Payout clearing wallet | Source | 0 | gross minus fee |
| Fee revenue wallet | Source | 0 | fee |

Under the project's DEBIT-subtract / CREDIT-add convention, total debit equals total credit per source currency. M3 never subtracts the balance separately from LedgerWriter. System wallets must be real configured/provisioned UUIDs with valid user FKs and account roles; callers cannot select them. Missing system accounts fail before posting.

M3 records the exact journal lines and their keys in the payment audit/posting data so reconciliation can identify them without importing M2's private ledger context. Do not claim that arbitrary frozen-writer calls automatically populate M2 journal metadata. M4's settlement and refund integration must use this recorded split; the proposed failed-payment compensation reverses clearing and fee credits and restores the gross customer amount atomically.

No local transaction guarantee is valid if the real writer commits each line separately. Combined adapter tests must prove it joins the payment transaction and enforces available balance for two different payments spending the same wallet.

### Review decisions

Review does not reserve funds. The UI says "Under review - funds not debited" and cannot start payout. Quote expiry while waiting is expected.

`M3ReviewService` exposes a typed internal command, not an end-user HTTP approval endpoint. It accepts a trusted decision ID, payment ID, active review reference and APPROVE/REJECT. Duplicate identical decisions are no-ops; conflicting decisions or stale review references are 409. Authenticate the eventual M5 transport and persist decision identity/result for deduplication. Tests call the command directly with fixtures.

Approval transitions to DRAFT, clears the current-generation pointer without resetting its monotonic counter, and stores a one-use approval receipt expiring 15 minutes after acceptance. Bind it to sender, wallet, recipient snapshot/version, gross source amount, source/payout currencies and purpose; those fields cannot change on this payment. Only an unexpired matching receipt may replace a fresh REVIEW assessment; fresh BLOCK still rejects. Always recheck KYC, recipient eligibility and wallet funds. If the receipt is expired, the assessor's verdict applies without that override.

The user requests fresh quotes and confirms with a new client key. The earlier key must continue to replay the original UNDER_REVIEW result. Spend the receipt only in a committed approved confirmation. Actual M5 transport/authorization and final payout integration remain separate acceptance checks.

## 8. Outbox and bare-metal Kafka

The chosen delivery mechanism is an Oracle transactional outbox. A confirmation success means funds and outgoing intent are committed; it does not mean the broker or payout provider has already processed them. Broker downtime leaves the payment PROCESSING and the event pending.

`M3OutboxRelay` claims an eligible delivery row in a short Oracle transaction using row locking and an expiring lease/claim token, commits that claim, then sends outside the database transaction. Mark SENT only after acknowledgment and only for the matching claim token. On send failure schedule a bounded exponential delay (1, 2, 4 seconds, up to 60 seconds) and retain the event. An expired lease makes a crashed worker's event retryable; a stale worker may not overwrite a newer claim.

For one payment, allocate sequence values under its lock and send the lowest unsent sequence before later sequences. Use payment ID as the Kafka record key. Crashing after broker acknowledgment but before recording SENT can publish a duplicate: delivery is at least once. Consumers must persist event-ID deduplication with their effects and reject stale sequence effects. Consumers of only one topic must allow sequence gaps, because review and initiated events use different topics; there is no total broker ordering across those topics. Producer idempotence alone does not remove these application-level duplicates.

Use `payment.initiated` with `eventType=payment.initiated.v1` only for approved, posted PROCESSING payments. REVIEW uses separately provisioned `payment.review.requested` with its own versioned schema; M4 must not execute those events. Event fields include event ID, aggregate sequence, payment ID, status, selected quote/review reference as applicable, sender/wallet IDs, source/fee/net amounts, both currencies, offered rate/recipient amount for execution, occurrence time and schema version. JSON money is string-valued. Sensitive payout details stay in the protected execution snapshot; events contain the reference needed to retrieve it.

The relay uses its typed `M3TransportPort` and KafkaTemplate; it does not replace the application's global `EventPublisher` bean or accidentally route other members' traffic. The shared void EventPublisher is insufficient evidence of acknowledged delivery. Tests provide a recording/controllable transport explicitly.

This design addresses the separate database/broker commits documented by [Spring Kafka 3.1 transaction synchronization](https://docs.spring.io/spring-kafka/docs/3.1.4/reference/kafka/transactions.html). It does not promise end-to-end exactly-once delivery.

Bare-metal endpoints use existing `ORACLE_JDBC_URL`, `ORACLE_USERNAME`, `ORACLE_PASSWORD` and `KAFKA_BOOTSTRAP_SERVERS`. Map optional TLS/SASL configuration through standard Spring Kafka properties. Operations provisions required topics and permissions; tests use dedicated topics and consumer groups. Do not run the repository's current Compose-based start/stop scripts or Docker-based Kafka smoke routine for this workflow.

## 9. API contract

There are ten distinct method/path endpoints below. All require a real signed JWT and return the existing `ApiResponse<T>` or `ApiError` envelope. Monetary DTO fields are decimal strings; entity relations are not serialized directly.

| Method/path | Success / essential behavior |
|---|---|
| POST `/api/recipients` | 201; duplicate complete owner/account/country is 409 |
| GET `/api/recipients` | 200; own records only |
| PUT `/api/recipients/{id}` | 200; expected version required; stale version 409 |
| POST `/api/payments/draft` | 201; required Idempotency-Key; replay original 201 data |
| POST `/api/payments/{id}/quotes` | 201 new generation or 200 current valid generation |
| GET `/api/payments/{id}/quotes` | 200 including expiry/server time, 404 if none |
| POST `/api/payments/{id}/confirm` | 200 PROCESSING, 202 UNDER_REVIEW, or replayable 422 BLOCKED outcome |
| POST `/api/payments/{id}/cancel` | 200 only for DRAFT/QUOTED; repeat cancellation 200, funded/review states 409 |
| GET `/api/payments` | 200; own history, page size default 20/max 100, stable createdAt/id descending |
| GET `/api/payments/{id}` | 200 own current detail; legacy status clearly identified |

A confirmation request contains `{quoteId}`. Its success data contains `{id, quoteId, status, fundsDebited}`; it never promises `debitEntryId`. Confirmation replays return their original data snapshot; current status is obtained from GET detail.

Return paginated DTO projections from history queries. Do not join-fetch a to-many quotes collection in a paginated query or expose lazy entity graphs through JSON. Fetch quotes only for the requested detail/generation and preserve the payment's original recipient snapshot in its history.

Missing/invalid key, malformed UUID/JSON, invalid enum or excess decimal precision: 400. Foreign/missing payment, recipient or wallet: 404. KYC failure: 403. Inactive/incomplete owned recipient, currency mismatch, insufficient funds and invalid fee/amount: 422. State/idempotency/recipient-version conflicts: 409. Expired current quote: 410. Unavailable dependency: 503. Existing security determines unauthenticated handling; verify its actual response rather than promising an unconfigured 401.

Use explicitly controller-scoped M3 advice and M3-specific exceptions, with precedence tested alongside the existing shared handler. Do not throw a generic SecurityException for an owned-resource 403/404 and rely on the shared handler, which currently maps it differently.

## 10. Frontend implementation and isolation

Use current business paths:

```text
frontend/fluxpay-ui/src/js/viewModels/recipients-vm.ts
frontend/fluxpay-ui/src/js/viewModels/payments-new-vm.ts
frontend/fluxpay-ui/src/js/viewModels/payments-list-vm.ts
frontend/fluxpay-ui/src/js/viewModels/payment-quotes-vm.ts
frontend/fluxpay-ui/src/js/views/recipients.html
frontend/fluxpay-ui/src/js/views/payments-new.html
frontend/fluxpay-ui/src/js/views/payments-list.html
frontend/fluxpay-ui/src/js/views/payment-quotes.html
frontend/fluxpay-ui/src/js/services/recipient-service.ts
frontend/fluxpay-ui/src/js/services/payment-service.ts
frontend/fluxpay-ui/tests/m3/**
frontend/fluxpay-ui/tsconfig.m3-tests.json
```

Inject service, navigation, clock and ID-generator interfaces into each view model. Service adapters accept an Axios-compatible client and default to the existing `api` imported from `./api-client`. Use relative `/payments` and `/recipients` URLs because its base URL already contains `/api`; unwrap `response.data.data`.

Wallet options are injected through an M3 view-model dependency; fixture data supplies wallet ID, currency, available funds and eligibility. The integrated implementation obtains them through the agreed wallet API. Client checks improve feedback but never establish authorization or reserve funds.

Create one key per intended draft submission and one per intended confirmation; disable duplicate submits while pending. On timeout retain the request/body/key and retry exactly. Retain pending operation metadata per user/payment in session storage for reload recovery; clear it on logout, changed request or a definitive outcome. Do not generate a fresh key for a lost response, including a confirmation that may have committed after its quote expired.

Render blocked/incomplete recipients, three quote cards, server-adjusted expiry, original/updated status, API failures and history pagination. Never perform authoritative financial arithmetic with JavaScript Number. The countdown prevents a new expired submission but does not block resolving an uncertain prior submission with its existing key. Dispose timers/subscriptions on view teardown.

For isolated tests, provide an explicit tsconfig including the M3 source files and fixture harness, emit to a dedicated test output directory, and load JET modules with the project's AMD/RequireJS tooling. A separate browser fixture page under `tests/m3` mounts the real templates with fake services. Use existing TypeScript/Node assertions for pure logic and record browser checks for template bindings. The current package does not declare a configured Jest runner; do not claim automated DOM coverage merely because JET tooling mentions Jest.

The business route declarations live at `src/js/appRouter.ts`: recipients, payments-new and payments-list already exist. Proposed quotes route state is `payment-quotes` with a `paymentId` parameter. Map states explicitly to `*-vm` module names and their HTML views; do not assume the module adapter guesses that suffix or that a literal Express-style `:id` path works in JET CoreRouter.

Shared integration must include the business TS sources in the build, mount those route declarations from the active `src/ts/appController.ts`/entry point, and verify direct navigation/reload with the chosen JET URL adapter. This is a named integration change to `tsconfig.json`, entry/router wiring and any required asset/module mapping. An existing route object is not proof that a page is reachable.

M3's standalone harness renders all four views without that shared routing change. Full application navigation remains a separate acceptance gate. History is payments-only in standalone mode; future ledger embedding uses M2's wallet-ID contract, never an assumed payment-ID component.

## 11. Independent development and verification matrix

Every M3-owned component can be implemented and tested without other members' implementation code. That does not mean every test can run without infrastructure.

| Suite | What actually runs | External requirements | What it proves |
|---|---|---|---|
| Unit | Quote/service/decision/replay/relay logic with injected clock and ports | None | M3 behavior and interface expectations |
| MVC | M3 controllers, real validation/security filter and scoped advice; mocked services/JwtUtil where appropriate | None | HTTP contracts, ownership/error mappings, replay response shapes |
| Oracle repository | Real migrations, M3 entities/queries/constraints/locks | Isolated Oracle schema | Oracle persistence behavior |
| Oracle transaction | Real M3 services including PaymentPostingService plus transactional SQL fixture wallet/writer on the same datasource | Isolated Oracle schema | Rollback, balanced posting, operation/outbox atomicity, concurrent spends under fixture contract |
| Kafka adapter | Real Kafka transport/serializer plus test consumer; relay records can be fixtures | Dedicated topics on bare-metal Kafka | Acknowledgment/failure and actual serialized payload behavior |
| Outbox crash/retry | Real Oracle records and controlled transport; optional real broker replay check | Oracle; broker for actual delivery check | Lease recovery, durable retry and duplicate tolerance |
| Frontend | M3 TS/view-model tests and real JET templates with fake services | Local Node/JET/browser tooling | All four views independent of backend/router integration |
| Combined integration | Real M1/M2/M4/M5 adapters, active business router and actual infrastructure | Required members plus Oracle/Kafka | Real adapter compatibility, security, payout/review/refund and routed UAT |

### Test bootstrap and fixtures

Create an M3 test application under test sources with explicit configuration/imports of M3 beans, repositories and entities. A package-wide `com.fluxpay.service` scan would include other members after merging; avoid it. Do not use an unbounded main application scan as the isolation mechanism.

MVC tests use the Boot 3.2 packages and controller slice with explicit mock dependencies. `TestAuthHelper.withUser(...)` does not populate security context, and `mockJwt(...)` is not accepted by the real parser without an explicit test mock. A separate signed-token test uses real `JwtUtil.generate/parse`.

Oracle tests use explicit datasource settings, the real migration chain and `@AutoConfigureTestDatabase(replace = NONE)` where using `@DataJpaTest`. Restrict scanned entities/repositories to M3 and fixture requirements. Disable automatic test wrapping for concurrency/commit-visibility tests and drive independent transactions/connections with barriers rather than sleep. See [Spring Boot 3.2.5 testing documentation](https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/features.html#features.testing.spring-boot-applications.autoconfigured-spring-data-jpa).

SQL fixtures insert users, customer wallets and system wallets satisfying current schema constraints, with non-login fixture password hashes. Test-side JDBC implementations of `M3WalletPort` and `LedgerWriter` participate in the same Oracle transaction and model idempotent debit/credit, wallet locks, nonnegative available funds and rollback. Run the real `PaymentPostingService` against these fixtures so its journal orchestration is exercised; do not stub the entire posting port in transaction tests. Inject failures after each line. Passing these tests does not verify the real M2 implementation.

The existing V201 wallets have no held-balance or account-role columns. Standalone fixtures keep hold/role metadata in explicitly test-only tables provisioned after production migrations in the dedicated schema; do not query nonexistent proposed M2 columns or add them to production M3 migrations. The combined adapter instead reads M2's actual schema and must pass the same ownership, hold and posting contract tests.

All fixture configurations remain under `src/test` and are explicitly imported. A `m3-fixture` profile supports a local test application, not the production artifact. KYC, pair-aware FX, compliance and recording transport are deterministic configurable fixtures, including rejection/error cases. Produce one selected implementation per local port; no global mock @Primary bean may accidentally override a real member in production.

An explicit test-only launcher can run M3 MVC/services on Oracle fixtures without M1/M2/M4/M5. Default it to loopback and disable relay unless a test broker is configured. It uses a dedicated schema and locally signed test JWTs, and contains no runtime authentication bypass.

Bare-metal schema credentials must identify a provisioned dedicated test schema. Check schema identity before writes and run V001 through V603 as applicable. The existing V501 VECTOR column requires a compatible Oracle installation even if M3 itself does not use vectors. Report that requirement if the full chain fails; do not quietly omit migrations and call the result full verification.

Name Oracle tests `M3*OracleTest` and enable them only by an explicit `M3_ORACLE_TESTS=true` flag. Name broker tests `M3*KafkaTest` with `M3_KAFKA_TESTS=true`. Disabled suites report skipped. An explicitly requested suite must fail if credentials/resources are absent, not silently skip. This naming uses the existing Surefire test lifecycle and does not assume a configured Failsafe plugin.

`scripts/run_m3_checks.py` is a proposed portable runner selecting unit, oracle, kafka or frontend suites, setting flags and using existing platform command helpers. Do not claim it exists yet. No M3 suite needs Docker/Testcontainers or the current Docker-based full-project smoke script.

### Required failure cases

- Changed quote ID/payment ID with the same confirm key; same textual key used by different users; unauthorized attempts to read replay data.
- Same payment with different concurrent confirm keys; two payments spending one wallet; concurrent confirm versus refresh, cancel or recipient update.
- Lost response after commit followed by expiry and replay; UNDER_REVIEW replay after approval and a new confirmation.
- Invalid/foreign/stale/expired quotes; expiry while waiting for wallet locks; zero/overflow/overprecision amounts and invalid FX.
- BLOCK/REVIEW write no journal and no executable initiated event; expired/mismatched review receipts cannot authorize payment.
- Failure after each journal line or outbox insert rolls back all money/state; writer failure cannot leave a replayable success.
- Broker unavailable after commit; relay crash after acknowledgment; expired/stolen lease; duplicate event handling.
- Fresh schema plus upgrade with legacy payments/incomplete recipients; no manufactured legacy purpose/currency/status.
- Frontend timeout retry, reload recovery, changing user/request, countdown cleanup, and all four fixture views.
- Production bean-selection checks reject missing required adapters and never load test fixtures.

## 12. Seed and completion levels

`scripts/seed_m3.py` uses stdlib HTTP, an explicit base URL, existing wallet UUID and bearer token supplied through an environment variable or protected token file. It never prints tokens. The fixture launcher provisions test identity/wallets, or a real demo uses M1/M2 provisioning first. The script does not create/fund production wallets directly.

Create/find fictional Priya and Alex recipients by normalized owner/account/country; on duplicate fetch and compare the existing profile instead of overwriting it silently. Use a stable per-user/scenario `DRAFT` key so reruns recover the same draft. Request quotes using the current-generation semantics. Confirmation is opt-in with a stable key and retained selected quote; after an uncertain outcome retry that exact request before generating any new quote/key. A completed scenario reports current state and makes no second debit. Creating a new demo payment requires an explicit new scenario ID. Re-running never tops up spent funds or resets payment history.

Report completion separately:

1. **Standalone M3:** horizontal components compile; unit/MVC and four frontend fixture views pass; all dependency behavior is represented by explicit contracts/fixtures. No other member's implementation is required.
2. **M3 adapters verified:** real Oracle migration/transaction and real Kafka adapter suites pass on dedicated bare-metal resources. If unavailable, report pending; unit success is not equivalent.
3. **Combined application ready:** production wallet/ledger, compliance decision transport, payout/refund consumers, event schemas and active frontend router/build pass their combined tests. Re-run port contract tests against real providers and verify missing/fake adapters fail startup.

A recorded blocker can coexist with completed standalone work, but it cannot count as completed production integration. The specification fixes and these proposed test plans have not themselves implemented or executed the application components.
