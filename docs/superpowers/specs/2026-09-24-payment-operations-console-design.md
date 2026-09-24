# Payment Operations Console and Event-Flow Demonstration Design

**Date:** 2026-09-24  
**Status:** Approved for implementation planning  
**Primary objective:** Make FluxPay's Kafka event delivery, bounded payout retry, uncertain-delivery reconciliation, and automatic refund behavior visible in a repeatable 5–8 minute local-stack demonstration.

## 1. Problem statement

FluxPay already implements the payment lifecycle correctly, but most of the evidence is hidden from the UI:

- `payment.initiated`, `payout.completed`, `payout.failed`, and `payment.refunded` are eventually stored and returned by the owner timeline API.
- `payout.retry` and `payout.refund` are operational recovery commands and are not customer timeline events.
- Outbox delivery state, aggregate sequence, delivery attempts, recovery operations, and ledger reversals are not exposed in the frontend.
- The customer tracking page shows a refund action that targets an endpoint intentionally forbidden to customers.
- The current bank simulation (`SIMULATE_FAILURE=BANK_NETWORK`) returns an uncertain result, which correctly pauses for reconciliation but does not exercise definitive-failure retry and refund recovery.

The result is a technically strong reliability design that is difficult for an evaluator to verify during a live demonstration.

## 2. Goals

1. Add a read-only admin payment operations view backed by real persisted state.
2. Show the event path from payment confirmation to payout completion.
3. Show definitive failure, automatic retry, successful retry, and retry exhaustion followed by refund.
4. Make outbox delivery, recovery commands, idempotency, and ledger evidence inspectable.
5. Use existing seeded bank routes; do not create a dedicated failure provider or route.
6. Configure failure scenarios and recovery timing from `.env`.
7. Auto-activate only the configured seeded provider and routes when simulation is explicitly enabled.
8. Provide a deterministic demo script and presenter runbook.
9. Remove the customer-facing refund action that conflicts with backend authorization.

## 3. Non-goals

This change will not:

- redesign event sourcing or replace Kafka;
- expose Kafka administration controls to customers;
- add a generic Kafka/DLT management product;
- persist or display Kafka partition and offset values;
- introduce WebSockets or Server-Sent Events;
- change the normal 120-second production recovery policy;
- add a new database migration;
- create new provider or route records;
- change payment ownership authorization;
- expose the read model to non-admin users.

## 4. Existing flow retained

The implementation keeps the current durable path:

```text
Payment confirmation transaction
  -> payment_operations and ledger posting snapshot
  -> outbox_events + outbox_delivery committed in the same database transaction
  -> OutboxRelay publishes with paymentId as the Kafka record key
  -> fluxpay-timeline persists customer lifecycle events
  -> PayoutFinalizationService commits payout outcome
  -> payout.completed or payout.failed enters the same outbox
  -> fluxpay-recovery consumes payout.failed
  -> payout.retry or payout.refund enters the common outbox
  -> PayoutRetryConsumer executes bounded recovery
  -> RecoveryService reverses the original funding snapshot
  -> payment.refunded enters the common outbox
```

The existing behavior remains authoritative:

- Kafka records are keyed by payment ID.
- Ordinary outbox events preserve aggregate order.
- Delayed `payout.retry` and `payout.refund` commands do not block later ordinary events.
- Duplicate Kafka delivery is acknowledged idempotently.
- Provider execution occurs outside a database transaction.
- Payout operations use a stable `payout:{attemptId}` provider key.
- A definitive failure schedules at most five automated retries.
- After the fifth automated retry fails, the recovery consumer creates `payout.refund`.
- An uncertain provider result never schedules a fresh payout or automatic refund; it remains pending for explicit reconciliation.
- Refund reverses the original source-side funding snapshot and publishes `payment.refunded`.

## 5. Development configuration

### 5.1 Environment contract

Add a safe, disabled example block to `.env.example`:

```env
FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=false

# Uncomment and use these values only for the local event-flow demonstration.
# FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE=BANK_STANDARD
# FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS=2
# FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE=BANK_EXPRESS
# FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS=6
# FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS=5
```

Map them to backend properties under `fluxpay.development`:

```yaml
development:
  simulated-payouts-enabled: ${FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED:false}
  retry-success-route-code: ${FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE:}
  retry-success-failure-attempts: ${FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS:0}
  refund-route-code: ${FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE:}
  refund-failure-attempts: ${FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS:0}
  recovery-delay-seconds: ${FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS:120}
```

Rules:

- A failure-attempt value of `0` disables that configured policy.
- A negative failure-attempt value is invalid and fails configuration validation.
- `recovery-delay-seconds` must be at least `1`; its default remains `120`.
- A configured route code is trimmed and matched case-sensitively against frozen route snapshots.
- Both policy variables are optional so the normal product configuration remains valid.
- Route-specific failure behavior exists only inside the development-only simulated bank rail.
- If either failure route or a nondefault recovery delay is configured while simulated payouts are disabled, configuration validation fails. This prevents an accidental production timing override.
- The existing `SIMULATE_FAILURE=BANK_NETWORK[:n]` uncertain-timeout behavior remains supported for reconciliation demonstrations. A matching route-specific definitive-failure policy takes precedence for that route.

### 5.2 Seeded route activation

`seed-local.py` remains insert-only for catalogue creation, then performs an explicit local-demo activation step only when simulation is enabled and at least one failure route is configured.

The seed script will:

1. Read the two optional route codes from `.env`.
2. Validate each configured code against the existing `ROUTES` catalogue.
3. Reject missing, duplicate, internal, or non-bank route codes with a clear error before changing active flags.
4. Resolve the existing provider code for each route.
5. Activate that provider only if it is currently inactive.
6. Activate only the configured routes that are currently inactive.
7. Leave every other provider and route unchanged.
8. Print the configured route, failure-attempt count, and provider code.

Normal seed runs with no demo route configuration do not change existing active flags.

### 5.3 Failure simulation semantics

`SimulatedBankNetworkRail` will resolve a route-specific policy from `command.route().code()`.

For a matching route:

- Count distinct payout attempts for the payment and route.
- Return a definitive `TransferRailResult.failed(...)` for the first configured number of attempts.
- Return `TransferRailResult.completed(...)` after that count.
- Cache the result by `command.idempotencyKey()` before evaluating policy, so replaying the same provider key returns the same result and does not consume another simulated failure.
- Use a clear demo error code such as `SIMULATED_PROVIDER_FAILURE` and include the frozen route code in the error message.
- Return the customer fee as the provider fee on failure.

For a nonmatching bank route, real-time route, partner route, or internal route, preserve successful behavior.

### 5.4 Recovery delay

`PayoutRetryConsumer` will receive a validated recovery delay and use it only for nonterminal `payout.retry` commands. The terminal `payout.refund` command remains due immediately after the final failed attempt.

This changes only local/demo timing when the environment variable is set; the default and production semantics remain 120 seconds.

## 6. Admin read model

### 6.1 Endpoint

Add:

```http
GET /api/admin/payments/{paymentId}/operations
Authorization: Bearer <admin-token>
```

The endpoint is annotated with `@PreAuthorize("hasRole('ADMIN')")`. It returns `404` for an unknown or malformed payment ID and the existing standard error envelope for other failures.

No write, retry, refund, or reconcile operation is added to this endpoint.

### 6.2 Response model

Return one read-only aggregate:

```json
{
  "payment": {
    "id": "uuid",
    "status": "PROCESSING",
    "selectedQuoteId": "uuid",
    "eventSequence": 3,
    "createdAt": "instant",
    "updatedAt": "instant"
  },
  "attempts": [
    {
      "id": "uuid",
      "attemptNumber": 1,
      "status": "FAILED",
      "routeCode": "BANK_STANDARD",
      "providerCode": "BANK_ALPHA",
      "providerReference": null,
      "errorCode": "SIMULATED_PROVIDER_FAILURE",
      "errorMessage": "Simulated definitive failure for BANK_STANDARD or null when no matching terminal event payload is persisted",
      "initiatedAt": "instant",
      "completedAt": "instant"
    }
  ],
  "outboxEvents": [
    {
      "eventId": "uuid",
      "eventType": "payout.failed",
      "aggregateSequence": 4,
      "createdAt": "instant",
      "payload": {},
      "delivery": {
        "state": "SENT",
        "attemptCount": 0,
        "nextAttemptAt": "instant",
        "sentAt": "instant",
        "lastError": null
      }
    }
  ],
  "timelineEvents": [
    {
      "eventId": "uuid",
      "eventType": "payout.failed",
      "kafkaTopic": "payout.failed",
      "correlationId": "uuid",
      "payload": {},
      "occurredAt": "instant"
    }
  ],
  "operations": [
    {
      "id": "uuid",
      "namespace": "INTERNAL",
      "operationType": "AUTO_SCHEDULE",
      "clientKey": "auto:schedule:payment-id:1",
      "status": "COMPLETED",
      "outcomeStatus": 200,
      "response": {},
      "createdAt": "instant"
    }
  ],
  "ledgerEntries": [
    {
      "id": "uuid",
      "journalReference": "refund:payment-id:clearing:debit",
      "idempotencyKey": "refund:payment-id:clearing:debit",
      "entryType": "DEBIT",
      "amount": "100.0000",
      "currency": "USD",
      "narration": "Payment refund",
      "createdAt": "instant"
    }
  ],
  "recovery": {
    "automatedRetryCount": 1,
    "decision": "RETRY",
    "nextRun": "instant"
  }
}
```

Implement the response as `PaymentOperationsResponse` with nested immutable records for the aggregate, payment, attempt, outbox event, delivery, timeline event, operation, ledger entry, and recovery summary. All fields above are required.

`decision` is derived from persisted operations and events using one of:

- `NOT_REQUIRED`
- `RETRY_SCHEDULED`
- `REFUND_SCHEDULED`
- `REFUNDED`
- `RECONCILIATION_REQUIRED`
- `STALE`

The read model must not claim that a `SENT` outbox row was consumed. For lifecycle topics, a corresponding row in `timelineEvents` proves the timeline consumer persisted it. For recovery commands, internal operation rows show whether recovery processing is pending or completed.

### 6.3 Read strategy

Add a focused `PaymentOperationsService` that:

1. Reads the payment.
2. Reads ordered payout attempts.
3. Reads ordered outbox deliveries for the payment.
4. Loads their outbox payloads in one batch.
5. Reads ordered timeline events.
6. Reads payment operations ordered by creation time.
7. Reads original and refund ledger journals by their known payment-specific references.
8. Resolves attempt route/provider codes for display.
9. Derives a nullable attempt `errorMessage` from the matching `payout.failed` event payload because the human-readable message is intentionally transient on `PayoutAttempt`.
10. Derives recovery state without mutating any record.

Repository query methods should return lists in deterministic order. The service should avoid per-event database lookups.

No Flyway migration is required because all evidence already exists in current tables.

## 7. Admin frontend

### 7.1 Navigation and route

Add an admin navigation item under **Operations**:

```text
Operations
  Payment Operations
```

Use module path `admin-payment-operations` and the existing JET/Knockout module conventions.

### 7.2 Page layout

The page contains:

1. A payment-ID lookup form.
2. Current payment and event-sequence summary.
3. A four-stage horizontal/vertical flow:
   - Payment confirmation
   - Payout execution
   - Recovery / retry
   - Refund
4. A correlated event list ordered by aggregate sequence and occurrence time.
5. Expandable event details.
6. Payout attempt history.
7. Recovery-operation summary.
8. Refund ledger evidence.

Event rows show:

- event type;
- aggregate sequence;
- outbox state;
- occurred/created time;
- delivery attempt count;
- next attempt time;
- correlation/event identity;
- last delivery error;
- decoded event payload.

The UI must visually distinguish:

- `PENDING`, `SENDING`, and `SENT` outbox states;
- a published lifecycle event with no timeline row yet;
- a lifecycle event consumed into `payment_events`;
- `payout.retry` and `payout.refund` operational commands;
- definitive failure, pending reconciliation, retry, and refund stages.

### 7.3 Refresh behavior

- Fetch immediately after lookup.
- Poll every 1 second while payment status is `PROCESSING`, or while any outbox delivery is `PENDING`/`SENDING`, or while an operation is `IN_PROGRESS`.
- Poll every 5 seconds for `UNDER_REVIEW`.
- Stop polling for terminal states after the final response is rendered.
- Clear the polling timer when the user changes payment or leaves the page.
- Preserve the last successful response during transient refresh errors and show a non-blocking warning.

The page uses polling because it is compatible with the current architecture and can be implemented within the demo time box.

### 7.4 No simulation controls in the UI

The page is read-only. Failure behavior comes only from `.env` and existing seeded route codes. This keeps the demo repeatable and prevents the UI from becoming another source of financial state.

## 8. Customer refund mismatch

The tracking page will no longer display a customer refund action. A failed transfer will show a non-mutating **Contact support** link that navigates to the existing `tickets` route.

The frontend will remove the customer `api.refund(id)` call and the corresponding `Page.executeAction('refund')` branch. The backend continues to:

- reject `POST /api/payments/{paymentId}/refund` with `FORBIDDEN`;
- allow refund only through the admin endpoint or the automatic `payout.refund` recovery command.

This change does not add a new support workflow; it reuses the existing support-ticket page.

## 9. Kafka topic provisioning

Add `payout.retry` and `payout.refund` to the explicit local topic lists in:

- `scripts/start-infra.py`
- `scripts/reset-local-db.py`

The existing DLT topic remains unchanged. Kafka auto-creation may still be available for local Compose, but explicit provisioning must match the application subscriptions.

## 10. Demonstration workflow

### 10.1 Demo script

Add a local script with two modes:

```bash
python scripts/demo-payment-operations.py retry-success
python scripts/demo-payment-operations.py refund-exhaustion
```

The script will:

1. Load `.env` and read `SEED_BASE_URL` plus the selected customer's credentials.
2. Authenticate as the seeded customer.
3. Read the first available customer wallet and recipient.
4. Create a draft and generate quotes.
5. Find the quote for the requested route:
   - `FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE` for `retry-success`;
   - `FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE` for `refund-exhaustion`.
6. Fail with a clear message if that route is absent from generated quotes.
7. Confirm the payment with a unique idempotency key.
8. Submit the payout with a unique idempotency key.
9. Print the payment ID, selected route, and expected scenario.

The script does not fabricate events, force database states, or invoke refund directly.

### 10.2 Presenter sequence

1. Start Oracle and Kafka, reset/seed the local database, and show the active demo routes printed by the seed command.
2. Start the backend and frontend with the development simulation environment enabled.
3. Run both demo script modes to create Payment A and Payment B.
4. Open **Admin → Payment Operations** for Payment A and show confirmation, outbox delivery, `payout.submitted`, two failures, a scheduled retry, and eventual `payout.completed`.
5. Open Payment B and show six failed attempts, the fifth automated retry, immediate `payout.refund`, refund ledger entries, `payment.refunded`, and terminal `REFUNDED` state.
6. Expand one normal event and one recovery event to show topic, IDs, correlation, sequence, payload, and delivery state.
7. State that successful HTTP writes prove transactional outbox commitment, while `SENT` proves Kafka publication and timeline rows prove consumer persistence.

The refund scenario is expected to take approximately 25 seconds with a five-second delay between the initial failure and five retries. The complete demonstration should remain within 5–8 minutes.

## 11. Error handling and safety

- All admin data is read-only and role protected.
- Payloads are rendered as data, never HTML.
- The UI must not expose authentication tokens or wallet secrets in copied diagnostics.
- Malformed payment IDs return the existing `404` error envelope.
- Missing optional relationships return empty collections or `null` according to the DTO contract; they do not fail the whole read model.
- A configured seeded route that cannot execute is reported by the seed script before the demo starts.
- Invalid failure counts or recovery delay prevent backend startup with a configuration error.
- The simulator is never registered when `FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED` is false or missing.
- The seed script performs no destructive reset.

## 12. Testing strategy

### 12.1 Backend unit and contract tests

Add or extend tests for:

- route-specific definitive failure policy;
- two independent configured route policies;
- completion after the configured failure count;
- provider-key replay without consuming another failure;
- nonmatching routes succeeding;
- invalid negative failure count;
- invalid recovery delay;
- admin authorization and response shape for the operations endpoint;
- deterministic ordering of attempts, outbox events, timeline events, and operations;
- recovery decision derivation for retry, refund, stale, and uncertain states;
- the recovery consumer using the configured delay while terminal refund remains immediate.

Retain the existing uncertain-timeout simulator tests.

### 12.2 Seed tests

Extend `tests/test_seed_local.py` to verify:

- no configuration leaves active flags unchanged;
- configured bank routes and their provider are activated;
- unknown, duplicate, internal, and non-bank route codes fail before mutation;
- unrelated catalogue records remain unchanged;
- the script prints the effective demo configuration.

### 12.3 Frontend tests

Add coverage for:

- admin navigation and route registration;
- payment lookup and grouped lifecycle rendering;
- outbox state labels;
- one-second active polling and terminal polling stop;
- transient refresh warning;
- expanded event metadata and JSON payload;
- absence of the customer refund action;
- absence of simulation controls in the admin page.

### 12.4 Integration verification

Run the existing relevant unit and contract suites, then run a full local-stack acceptance flow with Oracle and Kafka:

```bash
FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true \
  ./scripts/start-infra.py
```

After seed, application, and frontend startup, execute both demo script modes and verify:

- Payment A reaches `COMPLETED` after configured failures and retry.
- Payment B reaches `REFUNDED` after initial failure, five automated retries, and automatic refund.
- Outbox rows for every expected event reach `SENT`.
- Lifecycle events appear in the admin read model.
- Recovery and refund operation rows are present.
- Refund ledger entries reverse the original source funding.
- Restarting the same provider attempt does not create another provider result or consume another configured failure.

Environment-gated Oracle/Kafka tests must be genuinely executed for acceptance; a skipped suite is not successful acceptance evidence.

## 13. Expected files and boundaries

Backend additions and changes:

- `backend/src/main/java/com/fluxpay/config/DevelopmentPayoutSimulationProperties.java`
- `backend/src/main/java/com/fluxpay/development/SimulatedBankNetworkRail.java`
- `backend/src/main/java/com/fluxpay/messaging/PayoutRetryConsumer.java`
- `backend/src/main/java/com/fluxpay/controller/PaymentOperationsAdminController.java`
- `backend/src/main/java/com/fluxpay/service/PaymentOperationsService.java`
- `backend/src/main/java/com/fluxpay/dto/PaymentOperationsResponse.java`
- existing payment, attempt, outbox, event, operation, and ledger repositories
- `backend/src/main/resources/application.yml`
- corresponding backend tests

Frontend additions and changes:

- `frontend/fluxpay-ui/src/ts/viewModels/admin-payment-operations.ts`
- `frontend/fluxpay-ui/src/ts/views/admin-payment-operations.html`
- `frontend/fluxpay-ui/src/ts/services/flux-api.ts`
- `frontend/fluxpay-ui/src/ts/appController.ts`
- `frontend/fluxpay-ui/src/ts/views/tracking.html`
- `frontend/fluxpay-ui/src/ts/services/page.ts`
- the existing shared stylesheet containing admin page layout primitives
- frontend tests

Local/demo additions and changes:

- `.env.example`
- `scripts/seed-local.py`
- `scripts/start-infra.py`
- `scripts/reset-local-db.py`
- `scripts/demo-payment-operations.py`
- `README.md`
- `tests/test_seed_local.py`

The implementation should preserve the repository's package, DTO, service, JET/Knockout, and test conventions rather than introducing a parallel framework.

## 14. Acceptance criteria

The change is complete only when all of the following are true:

1. An admin can open a real payment and see confirmation, payout, recovery, and refund evidence from persisted records.
2. The page distinguishes outbox commit/publication from consumer persistence.
3. `payout.retry` and `payout.refund` are visible as operational commands.
4. `BANK_STANDARD` fails for two distinct attempts and then completes on the same payment.
5. `BANK_EXPRESS` fails for six distinct attempts, consumes five automatic retries, and triggers automatic refund.
6. The default recovery delay remains 120 seconds when no demo override is set.
7. Seeded route activation changes only the two configured external bank routes and their provider.
8. Invalid demo configuration fails clearly without silently changing routes.
9. A normal product configuration without demo variables behaves exactly as before.
10. The customer UI no longer offers an endpoint that the backend forbids.
11. Both `payout.retry` and `payout.refund` are explicitly provisioned by local scripts.
12. Relevant backend, seed, frontend, and full local-stack acceptance checks pass without relying on mocked timeline data.
13. A presenter can execute the documented sequence and explain the flow in 5–8 minutes.
