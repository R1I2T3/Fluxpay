# M5–M3 backend integration handoff

This handoff describes the implementation as of 2026-09-13. The integration connects M3 confirmation and review state to authoritative M5 assessments and the existing M2 wallet/ledger implementation in one process and one Oracle schema. Its successful payment outcome is **PROCESSING with a balanced persisted journal and durable outbox intent**. It does not execute an external payout or mark a payment COMPLETED.

Use [M5-Backend.postman_collection.json](postman/M5-Backend.postman_collection.json) for the current signed APIs. Retired prototype and solo-runtime collections have been removed to prevent accidental use.

## Existing M3 changes

Paths in this table are relative to `backend/src/main/java/com/fluxpay/`.

| Existing file | Before | Current behavior |
| --- | --- | --- |
| `service/PaymentConfirmationService.java` | One transactional method locked the payment before replay, called the ambiguous three-argument assessor, and exempted all M3 business exceptions from rollback. | A `NOT_SUPPORTED` facade checks committed idempotency first, prepares a payment-specific assessment and delegates atomic mutation. Preparation/state errors recheck for a concurrently committed same-key outcome. Unique-claim recovery occurs after worker rollback. BLOCK is converted to its HTTP exception after commit. |
| `beans/Payment.java` | Review reference and expiry fields existed without an exact assessment/case or spent-receipt lifecycle. | Adds assessment ID, case ID, fingerprint, approval decision ID and consumption timestamp. `bindReview`, `acceptReview`, `hasApproval` and `consumeApproval` implement the review/receipt transitions. Acceptance clears only the current quote-generation pointer; it preserves the generation counter. |
| `beans/M3PaymentOperation.java` | An operation was constructed only with its final result. | `complete(status, json)` finalizes a transaction-local operation claim. The temporary zero status and `{}` response are never intentionally committed. |
| `repository/PaymentRepository.java` | Only user-owned locking lookup was available. | Adds `lockInternal(paymentId)` for the trusted review receiver. It is not a public approval endpoint. |

`PaymentController`, `PaymentService`, `QuoteService`, the existing M3 posting/wallet port contracts, and the frozen common `ComplianceAssessor`, `KycGate` and `LedgerWriter` contracts retain their source behavior. M1/M2/M4 source is not changed by this bridge. New M3-owned adapters consume existing M2 contracts.

The original 11-argument confirmation constructor remains for the default runtime. It selects explicit package-private `LegacyM3ComplianceAdapter` and `LegacyM3DispositionAdapter` compatibility paths and requires an injected transaction manager for new mutations. The integration configuration uses the typed constructor and real M5 port instead. No integration setup should select the legacy adapters or the blanket `local` mock configuration.

## New ports and persistence

| Port or component | Responsibility |
| --- | --- |
| `M3PaymentCompliancePort.assess(M3PaymentFacts)` | Payment-specific preparation. Facts include payment/owner/wallet/recipient identity, amount/currencies, purpose and immutable recipient version/snapshot. |
| `M3PaymentAssessment` | Assessment ID, case ID, sequence, payment fingerprint, typed verdict and the captured facts. |
| `M3M5ComplianceBridge` | Reads committed M5 payment data, checks facts, obtains a fresh assessment and retries sequence contention using M5-owned head state. It never locates a payment by matching amount/currency. |
| `M3ConfirmationWorker` | Claims the confirmation operation and commits all payment, money, receipt, disposition and outbox changes in one transaction. |
| `M5PaymentDispositionPort.lockAndValidate / record` | Retains the latest M5 head/case locks and writes disposition while joining M3's transaction. |
| `M5JoiningDispositionService` | `MANDATORY` implementation of that port. It does not call the older independently committing `recordDisposition` API. |
| `M3ReviewDecisionPort.accept(M3ReviewCommand)` | Trusted internal receiver; returns `M3ReviewAck`. No end-user HTTP approval route is added. |
| `M3ReviewService`, `M3ReviewDecision`, `M3ReviewDecisionRepository` | Locked command acceptance, full normalized command replay, exact review binding, saved acknowledgment and receipt consumption in the existing M3 decision table. |
| `M3M5ReviewDecisionSink` | Maps M5's immutable command into M3's typed command and returns its post-commit acknowledgment. |
| `M5ActiveReviewGuard`, `JdbcM5ActiveReviewGuard` | Prevent new assessment publication from superseding a case still bound to an active M3 review, including the decision-to-delivery interval. The JDBC read does not acquire payment locks while M5 holds its head lock. |

`V707__m3_m5_review_binding.sql` adds nullable binding and receipt metadata without deleting or rewriting existing payment/review rows. It extends `payments` and `m3_review_decisions`, adds the command JSON constraint and links `payments.approval_decision_id` to the existing decision table. The consumption columns are `payments.approval_consumed_at` and `m3_review_decisions.receipt_consumed_at`. Applied migrations through V705 are not edited by this integration.

## Transaction boundaries and lock order

The facade has no active mutation transaction during preparation. A committed operation matching `(user_id, CONFIRM, client_key)` is replayed before payment state, quote expiry or screening is checked. Normalized command identity includes user, payment and quote IDs; a changed request returns `IDEMPOTENCY_CONFLICT`.

Fresh M5 observations use `REQUIRES_NEW` and committed facts. Sequence contention is coordinated through M5's own head/publication state, not a rollbackable M3 counter. A failed M3 mutation may leave an immutable, inactive M5 observation for audit. A new attempt assesses again, so a failed attempt cannot cache an old KYC/history authorization indefinitely.

The separate worker uses `REQUIRES_NEW` with a 15-second transaction timeout. It inserts the unique operation claim, locks/rechecks the payment and recipient, validates quote generation/expiry, rechecks KYC and compares immutable facts, then locks the latest M5 head/case. Posting happens only after those checks. The lock order is:

```text
confirmation operation → payment → recipient → M5 head/case → M2 wallet accounts
```

M2 locks wallet accounts in database UUID order and refreshes their balances before checking available funds. The joining disposition port uses the same JPA transaction manager and datasource as M3. A failure after posting, disposition, receipt consumption, operation completion or outbox insertion rolls back all those effects. A losing unique-operation claimant reads the winner only after its failed transaction has rolled back.

There is also a preparation race: a winner can commit after the facade's initial empty lookup but before the loser reads payment state or completes screening. The facade rechecks the committed operation before returning such errors. It replays only the identical normalized request; without a winner it retains the original error.

The backend rejects `spring.jpa.open-in-view=true` in integrated mode. This prevents a facade-loaded managed payment from being retained into the worker's fresh locking transaction. Boot preflight requires an identical datasource for JDBC and the JPA manager and checks the required schema columns. It does not apply migrations or create financial accounts at startup.

## Outcomes, review and receipts

| Assessment / receipt | Committed M3 outcome | Money and durable result |
| --- | --- | --- |
| APPROVE | PROCESSING | Balanced posting, initiated outbox, saved 200 and PROCEED disposition. A valid unused receipt, if present, is consumed. |
| REVIEW without a valid matching receipt | UNDER_REVIEW | No posting; exact review binding, review-requested outbox, saved 202 and REVIEW_REQUIRED disposition. |
| REVIEW with a valid matching receipt | PROCESSING | Same atomic posting/result path as approval, including receipt consumption. |
| BLOCK | REJECTED | No posting; saved 422 and BLOCKED disposition. A receipt cannot override BLOCK. |

The current six-rule demo engine emits **APPROVE or REVIEW only**. BLOCK is a supported integration branch exercised with controlled test assessments; changing payment input cannot currently make the live six-rule engine return BLOCK. Manual reviewer REJECT can produce a REJECTED payment, but it does not rewrite the immutable original screening verdict into an original BLOCK assessment.

The rules record KYC status, first completed payment to the recipient, qualifying activity during the configured day, a strict source-currency amount threshold, purpose shorter than ten characters, and a configured country list. HIGH risk or at least two reasons leads to review; LOW leads to approval. The static country list is a demo rule setting, not current legal sanctions data. Embeddings, retrieved policies and copilot output never choose the risk verdict. An unverified sender is also stopped by M3's KYC gate; a reviewer cannot bypass it.

M5 operator approval/rejection stores a durable decision command. The M3 receiver locks the payment and verifies flow version, owner presence, UNDER_REVIEW state, assessment/case/fingerprint and exact review reference. The entire normalized command includes decision ID, case/assessment/payment IDs, reference, fingerprint, action, reviewer, decision time and normalized reason.

Identical decision replay returns the saved acknowledgment. Changed payload, conflicting decision, stale reference or mismatched binding returns CONFLICT. APPROVE returns the payment to DRAFT and grants a one-use receipt expiring **900 seconds after M3 acceptance**, not after the operator's earlier decision time. At 899 seconds it is valid; at exactly 900 seconds it is expired. Replay neither extends expiry nor recreates a consumed receipt. REJECT records REJECTED without posting or refund.

After acceptance, request fresh quotes and use a new confirmation key. The previous quote generation is no longer current, but its generation counter is preserved. Replaying the old key with its original quote ID still returns the old UNDER_REVIEW result. Reusing that old key with a new quote ID is an idempotency conflict.

M5 publication refuses an unrelated new assessment while the bound M3 review remains active; exact assessment replay remains available. This also protects a case after the operator decides it but before delivery reaches M3. Once M3 accepts and leaves UNDER_REVIEW, subsequent fresh screening can proceed.

## Review delivery and outbox scope

`M5ReviewDeliveryService` invokes the sink outside its M5 decision/head locks. `M3ReviewService` commits before returning an acknowledgment. M5 then persists ACKNOWLEDGED or CONFLICT separately. If acknowledgment is lost, the same immutable command is retried, and M3 returns the original result without a second effect.

The integration scheduler defaults to an initial/fixed delay of 10 seconds and a batch limit of 20. `m5.review-delivery.enabled=false` disables scheduling. Failed delivery attempts back off from 30 seconds, doubling up to one hour; after eight unsuccessful attempts the existing pending-query limit stops automatic retries. Such a row remains PENDING with its retry count/error code and requires investigation. There is no new public “deliver now” or retry-reset endpoint.

The payment outbox is separate from this same-process review command path. Each stored outbox row, delivery row and JSON payload share one event UUID. UNDER_REVIEW emits `payment.review.requested` with exact binding metadata; PROCESSING emits `payment.initiated`. Persisting either event is not proof of Kafka publication or external payout execution. This integration does not wire a new Kafka review topic.

## Runtime and real M2 adapters

The entry point is `com.fluxpay.config.M5BackendApplication`, normally bound to `127.0.0.1:8082`. It adds `m5-risk` and `m5-backend`; add `m5-m3-integration` to enable payment APIs and review delivery. The focused `M5RiskApplication` remains available separately. The backend rejects `local`, `m5-solo` and `m5-legacy` fixture/prototype profiles.

The project-owned `backend/src/main/resources/m5-backend.properties` contains non-secret runtime settings. The Git-ignored root `.env` supplies Oracle credentials and the JWT secret. The reviewed start script reads only those required environment keys and starts the correct main class:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\docs\scripts\Start-M5Backend.ps1
```

The private path is an example; substitute your own. Migrations through V708 must already be applied explicitly. The launcher disables automatic Flyway/SQL initialization and Hibernate DDL mutation. The supplied synthetic fixture is a separate, user-run script; neither importing the Postman collection nor booting the application runs it. Auth/login endpoints are not added to this launcher: obtain valid signed USER/ADMIN tokens through the existing authorized identity/test-token workflow, using the configured signing secret.

The M5 backend initializes every new Hikari Oracle connection with `ALTER SESSION SET TIME_ZONE='UTC'`. Keep this paired with the integration's existing `hibernate.jdbc.time_zone=UTC` and `hibernate.type.preferred_instant_jdbc_type=TIMESTAMP`: legacy columns have no timezone, while quote/receipt columns use `TIMESTAMP WITH TIME ZONE`. A non-UTC Oracle session can label a UTC timestamp bind with the local offset and make a newly saved quote immediately expired. Restart the backend after changing this setting; existing mis-stamped quote rows are not rewritten. The opt-in `M5OracleTimestampRoundTripTest` covers both column types and the non-UTC failure control using only SELECTs and connection-local session settings.

`M3M2WalletAdapter` reads actual customer available funds as balance minus held balance. It requires an already-provisioned `PAYOUT_CLEARING` and `FEE_REVENUE` account in the source currency owned by `m5.integration.system-user-id`; it never creates them. It checks account role, currency and owner again under ordered locks and refreshed state. Missing accounts or insufficient funds fail closed.

`M3M2PostingAdapter` uses `LedgerJournalService` and `PersistentLedgerWriter`. For gross G and fee F, it debits the customer G, credits payout clearing G−F and credits fee revenue F; a zero-fee line is omitted. Keys are `m3:<paymentId>:customer`, `m3:<paymentId>:clearing` and `m3:<paymentId>:fee`, with journal reference `m3:<paymentId>`. It rechecks quote expiry after account locking and before the balanced journal. `DatabaseKycGate` and `FxQuoteService` are reused; the FX source must be an explicitly configured HTTPS endpoint.

M5 operator routes under `/api/compliance`, `/api/policies` and `/api/copilot` require a signed ADMIN token. The passport route permits the authorized payment owner or ADMIN. `/api/payments` requires a signed token and retains M3 ownership checks; ADMIN does not automatically act as a different payment sender. No unsigned token or identity-header fallback is provided by these runtime chains.

## API walkthrough: review, delivery and fresh confirmation

Use a verified synthetic sender with a funded USD customer wallet, an owned eligible INR recipient and provisioned USD system accounts. To exercise REVIEW predictably with the example configuration, use the fixture's recipient whose country matches the configured demo list; do not alter real customer/KYC history to manufacture a result. Keep signed sender and ADMIN tokens in private Postman environment values. The collection defaults to the synthetic wallet and demo-risk recipient IDs from `docs/sql/m5-backend-test-data.sql`, but does not execute that script.

1. **Sender creates a draft.** `POST /api/payments/draft`, `Idempotency-Key: <draftKey>`:

   ```json
   {"sourceWalletId":"<owned-wallet-id>","recipientId":"<owned-recipient-id>","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","purpose":"FAMILY_SUPPORT","preference":"BALANCED"}
   ```

   Expect 201 and save `data.id` as `paymentId`. Payment source/payout currencies must differ and match wallet/recipient eligibility.

2. **Sender obtains quotes.** `POST /api/payments/{paymentId}/quotes`; expect 201. Save `data.recommendedQuoteId` as `quoteId` and preserve it separately as `originalQuoteId`. Quotes expire after 15 minutes; the response supplies `expiresAt` and `serverTime`.

3. **Sender confirms.** `POST /api/payments/{paymentId}/confirm`, `Idempotency-Key: <confirmKey>`, body `{"quoteId":"<quoteId>"}`. For the review scenario expect 202 and `data.status=UNDER_REVIEW`. No funds are posted. A direct 200/PROCESSING means current rules approved the actual facts; do not try to approve a non-reviewable case.

4. **Read the authoritative case.** `GET /api/compliance/payments/{paymentId}/passport` with sender or ADMIN token. Save `data.caseId`. Inspect `screeningVerdict`, reasons, `paymentDisposition=REVIEW_REQUIRED`, review reference and `reviewable=true`. A manual call to the standalone assess API is unnecessary for confirmation; an unrelated assessment during the active hold is rejected.

5. **ADMIN decides.** `PUT /api/compliance/cases/{caseId}/approve` with `{"reason":"Synthetic evidence reviewed"}`. The response records the decision; it does not itself mean M3 has accepted it. Alternatively, use `/reject` with a nonblank reason on a separate undecided review scenario. Conflicting decisions are not a way to switch an already decided case.

6. **Wait for durable delivery.** Read `GET /api/compliance/cases/{caseId}` until `deliveryState=ACKNOWLEDGED`; also read `GET /api/payments/{paymentId}` as the sender. Approval acceptance changes the payment to DRAFT. A CONFLICT delivery needs investigation. PENDING is not permission to reconfirm, and the receipt lifetime starts at M3 acceptance. The collection deliberately has no automatic polling loop or forced delivery request.

7. **Sender obtains a fresh quote and key.** Call the quotes POST again, capture the new `recommendedQuoteId` as `freshQuoteId`, and confirm with `Idempotency-Key: <reconfirmKey>` and `{"quoteId":"<freshQuoteId>"}`. The key must differ from the original key. A fresh REVIEW is overridden only by the unexpired exact receipt; quote, recipient, KYC, funds and latest-assessment checks still apply. An expired/mismatched receipt can lead to a new review. The successful response is 200/PROCESSING.

8. **Check replay independently of current state.** Send the original confirmation key with `originalQuoteId`: its historical 202/UNDER_REVIEW outcome is preserved even after successful reconfirmation. Send the reconfirmation key with `freshQuoteId`: it replays PROCESSING without new money/outbox effects. Changing the quote under either key yields `IDEMPOTENCY_CONFLICT`.

The collection also includes signed policy list/detail/create/index and copilot requests. Those operations do not approve a payment. Indexing/copilot retrieval needs the configured provider; a provider failure must remain an error, not a fallback mock success. Reconciliation and policy creation/indexing are explicit mutations and are kept outside the payment walkthrough folder.

## Verification and limits

The focused implementation evidence includes 9 confirmation-boundary tests, 13 JPA/JDBC transaction integration tests, the 899/900-second domain test and 9 M5 lifecycle tests, all green in the recorded root-run batch. Transaction tests use disposable H2. They cover preparation/claim races, stale assessments, committed KYC observation, exact decision replay, pending-delivery publication protection, receipt behavior and rollback after posting/disposition/outbox/operation changes.

The real M2 test funds synthetic accounts through a balanced journal, verifies that an outbox failure rolls the M3 journal and wallet changes back, then verifies a successful retry and idempotent replay. Its payment journal has three entries whose debits equal credits; customer, payout clearing and fee balances match the journal. This is evidence about the actual M2 implementations on H2, not a claim that Oracle migrations or real external services were exercised by that test.

Consult the separate end-to-end testing guide for current Oracle, HTTP and semantic-provider acceptance results. This handoff does not claim to have run a migration, the fixture, a Postman request, a live Oracle transaction or an Ollama semantic request. No frontend changes, M4 payout execution, Kafka delivery, settlement, completion or refund workflow are delivered by this bridge.
