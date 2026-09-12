# FluxPay M2 Demo Funding Design

**Date:** 2026-09-11

**Status:** Approved in chat for design documentation

**Scope:** Complete backend demo-funding flow only. FX conversion, ledger-list APIs,
frontend work, Kafka publication, and shared security changes remain outside this
milestone.

## Goal

Provide an authenticated `POST /api/wallets/receive-demo` endpoint that credits a
customer wallet exactly once, records a balanced append-only journal, and stores a
replayable operation response in the same Oracle transaction.

## Constraints

- Continue on `member2-wallet-ledger`; new commits will update PR #3.
- Preserve the frozen `LedgerWriter.append(...)` signature.
- Route every posted balance change through the existing `LedgerJournalService`
  and `PersistentLedgerWriter` path.
- Do not edit existing migrations, `pom.xml`, shared security classes, shared API
  envelopes, Kafka configuration, frontend files, or other members' code.
- Use Java 17, Spring Boot 3.2.5, Spring Data JPA, Oracle 23ai, UUID/`RAW(16)`, and
  `BigDecimal` scale 4 with `HALF_EVEN`.
- Keep demo funding disabled by default.

## Public API

### Request

```http
POST /api/wallets/receive-demo
Authorization: Bearer <signed JWT>
Idempotency-Key: <1 to 255 characters>
Content-Type: application/json

{
  "currency": "USD",
  "amount": "500.0000"
}
```

The authenticated principal supplies the customer UUID. Callers cannot supply a
user UUID, wallet UUID, clearing-wallet UUID, or account role.

Supported currencies are `USD`, `EUR`, and `INR`. Amount must be positive, contain
at most four fractional digits, and fit Oracle `NUMBER(19,4)`. Currency is
normalized to uppercase; amount is normalized to an exact four-place decimal
string. Overprecise input is rejected rather than silently rounded.

### Success response

The controller returns the existing `ApiResponse<WalletResponse>` envelope.
`WalletResponse` contains:

- `walletId` as a UUID string
- `currency`
- `balance`, `heldBalance`, and `availableBalance` as four-place decimal strings
- `journalReference`

An exact replay returns the stored response snapshot, including the original
wallet ID, amounts, and journal reference. It does not calculate a fresh response.

### HTTP errors

- `400` for a missing/invalid idempotency key, currency, or amount
- `401`/`403` through the existing security configuration
- `404` when demo funding is disabled or its configured clearing account is absent
- `409` when a key is reused with a different normalized request or when a
  retryable concurrency conflict cannot be resolved safely
- `503` is not used by this milestone because FX and Kafka are not involved

M2-owned exceptions are mapped by `M2ApiExceptionHandler` without changing the
shared `GlobalExceptionHandler`.

## Configuration and system account

`M2DemoFundingConfig` reads:

- `fluxpay.demo-funding-enabled`, default `false`
- `fluxpay.demo-system-user-id`, optional while disabled and required when funding
  is used

Standard environment variables are
`FLUXPAY_DEMO_FUNDING_ENABLED` and `FLUXPAY_DEMO_SYSTEM_USER_ID`.

The configured UUID identifies a pre-provisioned, non-login system user satisfying
the existing `users` foreign key. That user owns one `DEMO_CLEARING` wallet per
supported currency. The service never exposes system wallets through the API and
does not create or modify the system user.

## Components

### `WalletController`

- Extracts `CurrentUser` with `@AuthenticationPrincipal`.
- Reads the required `Idempotency-Key` header.
- Delegates to `DemoFundingService`.
- Maps the result into `ApiResponse` using the existing correlation-ID convention.
- Does not access repositories or mutate entities.

### `DemoFundingService`

- Validates and normalizes the request before any transaction begins.
- Looks up `(user_id, operation_type='RECEIVE_DEMO', client_key)`.
- Returns a parsed stored response for an exact completed replay.
- Rejects the same key with a different normalized request.
- Invokes `WalletPostingService` through a separate Spring bean so its transaction
  proxy is active.
- If a unique-key race rolls the inner transaction back, reloads the committed
  winner in a fresh transaction context and applies the same replay/conflict rules.
- Never changes wallet balances directly.

### `WalletPostingService`

The `receiveDemo(...)` method owns one `@Transactional` boundary:

1. Recheck the operation key inside the transaction.
2. Insert and flush a `WalletOperation` in `IN_PROGRESS` state.
3. Find or create the authenticated user's `CUSTOMER` wallet for the requested
   currency. A uniqueness race aborts this transaction and is retried through the
   outer service rather than continuing in a rollback-only transaction.
4. Find the configured system user's matching `DEMO_CLEARING` wallet.
5. Post a two-line journal through `LedgerJournalService`:
   - debit the demo-clearing wallet
   - credit the customer wallet
6. Use the operation UUID to form a journal reference no longer than 64 characters
   and deterministic per-line keys no longer than 255 characters.
7. Serialize `WalletResponse`, call `WalletOperation.complete(...)`, flush, and
   commit the operation, both ledger rows, and both balance changes together.

Any failure rolls back the operation row, wallet creation, ledger rows, and balance
deltas. No durable failed operation is retained.

### DTOs and exceptions

- `WalletReceiveRequest` carries the inbound decimal string and currency.
- `WalletResponse` contains only public customer-wallet information plus the
  committed journal reference.
- Small M2-specific exceptions distinguish disabled/missing configuration,
  idempotency conflicts, and retry conflicts.

## Idempotency model

The operation identity is the database-unique tuple
`(user_id, operation_type, client_key)`. The normalized request contains only the
authoritative inputs, for example:

```json
{"currency":"USD","amount":"500.0000"}
```

- Same identity and same normalized request with `COMPLETED`: return the stored
  response and make no wallet or ledger change.
- Same identity with a different normalized request: return `409`.
- `IN_PROGRESS` is never intentionally committed; it exists only inside the
  posting transaction.
- A failed transaction leaves no operation row, allowing a safe retry with the
  same key.
- A database uniqueness race is resolved only after the losing transaction has
  rolled back. Code never retries a failed statement within a rollback-only
  transaction.

## Locking and atomicity

`LedgerJournalService` locks all journal wallets in Oracle RAW/canonical UUID order
before calling the mandatory-transaction writer. This preserves the Step 3 lock
ordering rule. The operation insert and customer-wallet creation constraints
provide race detection; the ledger writer provides entry-level exact-once defense.

The clearing wallet may carry a signed balance and has zero held funds. The
customer credit increases posted and available balance; it does not create or
consume a payment hold.

## Testing

Development follows red-green-refactor. Tests are divided by boundary:

- Service unit tests: normalization, disabled configuration, exact replay,
  changed-request conflict, response-snapshot parsing, and delegation.
- Controller tests: signed principal extraction, required header/body validation,
  existing API envelope, decimal-string serialization, disabled `404`, conflict
  `409`, and unauthenticated behavior using the existing `TestAuthHelper` and a
  mocked `JwtUtil.parse(...)`.
- Isolated Oracle tests: successful two-line funding, missing customer-wallet
  creation, exact replay with unchanged balances/row counts, changed-request
  conflict, injected mid-journal rollback, and concurrent identical requests.
- Full regression: run the existing M2 Oracle runner and require every Step 1-4
  test to pass. Kafka is not required.

Tests never clean, truncate, or reset the shared development schema. Oracle cases
use the existing dedicated `FLUXPAY_M2_TEST` schema and deterministic fixture rows.

## Owned files

Planned production additions or focused modifications:

- `backend/src/main/java/com/fluxpay/controller/WalletController.java`
- `backend/src/main/java/com/fluxpay/service/DemoFundingService.java`
- `backend/src/main/java/com/fluxpay/service/WalletPostingService.java`
- `backend/src/main/java/com/fluxpay/dto/WalletReceiveRequest.java`
- `backend/src/main/java/com/fluxpay/dto/WalletResponse.java`
- `backend/src/main/java/com/fluxpay/config/M2DemoFundingConfig.java`
- `backend/src/main/java/com/fluxpay/config/M2ApiExceptionHandler.java`
- focused M2 exception/helper classes only when required by the implementation
- focused additions to `WalletRepository` and `WalletOperationRepository`

Tests mirror these horizontal packages. `docs/02-member2-wallet-ledger.md` will be
updated only after verified implementation.

## Completion criteria

- Demo funding is disabled by default and returns `404` when disabled.
- An authenticated user can fund only their own customer wallet.
- Each accepted request produces one completed operation and one balanced,
  two-entry journal.
- Exact replay returns the original snapshot without balance or ledger changes.
- Changed input with the same operation key returns `409`.
- Transaction and concurrency tests prove no partial or duplicate funding.
- All prior M2 tests continue to pass against isolated Oracle.
