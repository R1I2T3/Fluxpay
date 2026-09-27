# FluxPay

FluxPay is a Java 17 / Spring Boot 3.2.5 payment backend with Oracle persistence,
Kafka-backed durable events, JWT authentication, wallet/ledger accounting, quotes,
payments, payout orchestration, recovery, timeline APIs, policy indexing, and a
policy-grounded compliance copilot. The active JET frontend is included in the
local acceptance runbook and must not expose simulation or financial write controls.

## Local configuration

Copy `.env.example` to `.env` and replace every `change-me` value. Keep application,
administrative, and test-schema credentials distinct. Scripts load the requested env
file before building subprocess commands and never print passwords or tokens.

Choose one infrastructure mode:

- `FLUXPAY_INFRA_MODE=compose`: FluxPay starts/stops the named `oracle` and `kafka`
  Compose services and uses Kafka tools inside the Kafka container.
- `FLUXPAY_INFRA_MODE=external`: scripts only probe configured services. They never
  stop them. Topic provisioning needs `KAFKA_HOME`; destructive schema acceptance
  additionally requires a separately verified fresh broker.

Start and provision the complete local topic inventory:

```powershell
python3 -B scripts/start-infra.py --mode compose
```

The explicit eleven-topic inventory is `payment.initiated`, `payment.route.selected`,
`payment.screening.completed`, `payment.review.requested`, `payout.submitted`,
`payout.failed`, `payout.retry`, `payout.refund`, `payout.completed`,
`payment.refunded`, and `payout.recovery.dlt`. `payout.retry` and `payout.refund`
are operational recovery commands, not customer timeline events.

## Local schema reset and seed

`scripts/reset-local-db.py` accepts only `FLUXPAY` and `FLUXPAY_TEST`, rejects remote Oracle,
checks live connection metadata and target existence, and is dry-run unless
`--execute` is supplied. Stop the backend first. No export is created automatically.

```powershell
python3 -B scripts/reset-local-db.py --schema FLUXPAY
python3 -B scripts/reset-local-db.py --schema FLUXPAY --execute
python3 -B scripts/reset-local-db.py --schema FLUXPAY_TEST
python3 -B scripts/reset-local-db.py --schema FLUXPAY_TEST --execute
```

In Compose mode the execute path removes and recreates only the eleven FluxPay topics;
it never removes volumes or unrelated topics. In external mode, set
`FLUXPAY_EXTERNAL_BROKER_FRESH=true` only after verifying that the dedicated broker
contains no stale FluxPay events.

After the reset, start the backend once so Flyway installs the versioned migrations, run
the seed twice to prove idempotence, copy the reported system UUID into
`FLUXPAY_SYSTEM_USER_ID`, then restart the backend:

```bash
python3 -B scripts/seed-local.py
python3 -B scripts/seed-local.py
python3 -B scripts/check-ledger.py
```

The seed registers four local identities with the real `fullName`/password contract,
promotes only its local admin/system identities through a schema-scoped provisioning
path, creates required system wallets, and inserts the demonstration transfer catalogue: the
protected `FLUXPAY` provider with its `FLUXPAY_INTERNAL` wallet route plus one demonstration
provider for each external rail with representative `IN`/`INR` routes. Passwords come from
`SEED_SYSTEM_PASSWORD`, `SEED_ADMIN_PASSWORD`, and `SEED_CUSTOMER_PASSWORD`.
Public registration never accepts a role. Catalogue inserts use deterministic UUIDs and
`MERGE ... WHEN NOT MATCHED THEN INSERT` only, so reruns keep administrator edits.

The catalogue concepts: code-shipped `TransferRail` implementations (one per `RailType`) do the
actual delivery; administrators manage providers (institutions bound to one rail type) and routes
(commercial and eligibility configuration beneath one provider). Execution follows
`route -> provider -> rail -> internal ledger or external network`. Administrators never install
code, endpoints, or credentials.

## Payment operations event-flow demonstration

This is the repeatable evaluator runbook for the read-only admin operations console. It
uses the real local API, Oracle persistence, Kafka outbox relay, and existing JET UI; it
does not insert events, force database states, or call refund directly. After the stack
is prepared, the presenter can explain and demonstrate the complete flow in a
**5–8 minute** target.

### 1. Safe local-only demo configuration

Copy `.env.example` to `.env`, fill the local Oracle/Kafka credentials and the three
`SEED_*_PASSWORD` values, and use this exact development block for the demonstration.
Keep the file private and do not use these settings outside a local development stack.
Route codes are case-sensitive.

```dotenv
FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true
FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE=BANK_STANDARD
FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS=2
FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE=BANK_EXPRESS
FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS=6
FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS=5
SEED_BASE_URL=http://localhost:8080
```

`FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS=5` changes only the nonterminal recovery
interval; the terminal `payout.refund` is due immediately. Without the block, the
normal safety defaults remain: simulation is off, no route policy is active, and the
production recovery delay is 120 seconds. For the one-time wallet-funding prerequisite
below, temporarily set `FLUXPAY_DEMO_FUNDING_ENABLED=true` and restart the backend; it
may be returned to `false` after funding.

### 2. Start, reset, migrate, and seed the local stack

Run these steps from the repository root. The dry run is intentional, and the application
backend must be stopped before an executing schema reset.

```bash
python3 -B scripts/start-infra.py --mode compose
python3 -B scripts/reset-local-db.py --schema FLUXPAY
python3 -B scripts/reset-local-db.py --schema FLUXPAY --execute
```

The reset removes and recreates only the eleven FluxPay topics, including
`payout.retry` and `payout.refund`; it does not remove volumes or unrelated topics.
For an externally managed broker, use the external mode only after independently
verifying a fresh isolated broker.

Start the backend once in a separate terminal so Flyway installs the versioned schema.
Wait for `backend UP`, and keep that first process running while you seed in a second
terminal:

```bash
python3 -B scripts/start-backend.py
```

With the first backend still running, seed twice to prove idempotence and capture the
reported system UUID:

```bash
python3 -B scripts/seed-local.py
python3 -B scripts/seed-local.py
python3 -B scripts/check-ledger.py
```

Stop the first backend, set the printed `system-user-id` in `.env` as
`FLUXPAY_SYSTEM_USER_ID`, and then restart the backend. With the demo block, the seed
output should identify the selected seeded routes (both belong to `BANK_ALPHA`):

```text
demo-route=retry-success route=BANK_STANDARD failure-attempts=2 provider=BANK_ALPHA
demo-route=refund route=BANK_EXPRESS failure-attempts=6 provider=BANK_ALPHA
```

The seed validates every selected route before changing any active flag. It activates
only the selected inactive seeded provider/routes, increments their versions, and
leaves unrelated catalogue records unchanged. A normal seed with no demo route
configuration remains insert-only and prints `demo-routes=none active-flags-unchanged`;
there is no manual route activation step for this demo.

### 3. Prepare the existing customer once

The demo script deliberately does not create KYC state, fund a wallet, or create a
recipient. Prepare the seeded customer through the existing UI or API before starting
the two-payment run. The required customer state is a **verified KYC** session, a
**funded USD wallet**, and an **active INR recipient**. The default seeded customer is
the value of `SEED_ALICE_EMAIL`
(`priya.sharma@gmail.com` unless overridden), authenticated with
`SEED_CUSTOMER_PASSWORD`.

1. **Verified KYC:** in Verification, upload a real PDF/JPG/PNG document and submit the
   application. In the administrator KYC Reviews page, approve the resulting
   `kycApplicationId` with its current `expectedVersion`. The API equivalents are
   `POST /api/kyc/applications` (multipart) and
   `PUT /api/admin/kyc/applications/{kycApplicationId}/approve`. A metadata-only JSON
   submission is not accepted. Confirm that a fresh login reports
   `user.kycStatus: "VERIFIED"`.

2. **Funded USD wallet:** use Wallets → Add money, or call
   `POST /api/wallets/receive-demo` with `{"currency":"USD","amount":"25.0000"}` and a
   unique `Idempotency-Key`. The demo needs at least the script's `10.0000` source
   amount available in a USD wallet. The endpoint is available only while the
   development funding toggle is enabled.

3. **Active INR recipient:** use Recipients, or call `POST /api/recipients` with an
   `ACTIVE` INR record such as
   `{"name":"Demo Recipient","account":"1234567890","bankName":"Demo Bank","country":"IN","currency":"INR","status":"ACTIVE"}`
   and a unique `Idempotency-Key`. Confirm `GET /api/recipients` returns an active INR
   recipient. If there are several active records, make the first one returned to the
   script an INR record or remove the ambiguity before running the demo.

The script selects the first wallet whose available balance covers `10.0000` and the
first active recipient; it does not silently create or repair either prerequisite.

### 4. Start the application and frontend

After setting `FLUXPAY_SYSTEM_USER_ID`, start the backend with the demo `.env` in one
terminal and the active frontend in another:

```bash
python3 -B scripts/start-backend.py
python3 -B scripts/start-frontend.py
```

Wait for the backend readiness message, then open the UI at
`http://localhost:8000/`. The frontend proxies to the backend at `http://localhost:8080`
by default. Do not edit or commit generated `web-dev` output.

### 5. Create both real payments

From the repository root, run both commands against the running backend:

```bash
python3 -B scripts/demo-payment-operations.py retry-success
python3 -B scripts/demo-payment-operations.py refund-exhaustion
```

Each command logs in as the seeded customer, selects the exact route, creates a draft,
quotes, confirms, and submits a payout through the public API. The output contains the
new `paymentId`, route code, and expected attempt count. Save Payment A's ID from
`retry-success` and Payment B's ID from `refund-exhaustion`; the script does not call
`/refund`.

### 6. Presenter flow and exact expected outcomes

Open each saved ID under **Admin → Payment Operations**. The page polls while work is
active and is read-only: it must show no simulation, retry, refund, reconciliation, or
other financial write control.

**Payment A — `retry-success` on `BANK_STANDARD` (expectedAttempts=3):**

- Attempt 1 fails definitively with the simulated provider failure.
- A `payout.retry` recovery command is published after the five-second demo delay.
- Attempt 2 also fails definitively; the next retry is scheduled after five seconds.
- The next automatic retry (attempt 3) succeeds.
- `payout.completed` is published and the payment reaches final `COMPLETED`.
- The outbox rows for the lifecycle events and retry commands reach `SENT`; the
  corresponding lifecycle events eventually appear in the timeline read model.

**Payment B — `refund-exhaustion` on `BANK_EXPRESS` (expectedAttempts=6):**

- The initial attempt plus five automatic retries produce six failed attempts.
- The operations view shows five `AUTO_RETRY` operations, with the nonterminal retry
  commands delayed by five seconds.
- After the fifth automated retry fails, `payout.refund` is due immediately (not after
  another delay).
- The recovery operation reverses the original funding snapshot, so refund ledger
  entries are visible; `payment.refunded` is published and the payment reaches final
  `REFUNDED`.
- The `payout.retry` and `payout.refund` outbox rows reach `SENT`, while lifecycle rows
  appear in the timeline read model.

Do not conflate these evidence types: a committed outbox row is a database write; a
`SENT` delivery is Kafka publication; a matching timeline row is consumer persistence;
and recovery operation/ledger rows show that recovery or reversal actually ran. A
`SENT` row alone is not proof that a customer timeline row exists. `payout.retry` and
`payout.refund` are operational commands, not customer timeline events. Also verify
that the customer tracking page has no refund control; it should offer non-mutating
support navigation instead.

The refund scenario normally takes about 25 seconds with the five-second interval;
include the UI lookup, event expansion, and explanation in the presenter target.

### 7. Presenter timing target

Treat the **5–8 minute** target as the time from a prepared stack to the final
explanation, not as a promise that Docker, schema reset, or Flyway startup is instant.
A practical sequence is: one minute to show the seed/config and two commands, two to
three minutes for Payment A and Payment B evidence, one minute to expand one normal
event and one recovery command, and the remaining time for the evidence distinction
and questions. Keep polling visible while a payment is `PROCESSING` or a delivery is
`PENDING`/`SENDING`.

### 8. Verification commands

Run the focused documentation and demo checks first:

```bash
python3 -B -m unittest tests.test_demo_payment_operations -v
ruff check tests/test_demo_payment_operations.py
ruff format --check tests/test_demo_payment_operations.py
```

Run the targeted backend and frontend checks:

```bash
./mvnw -f backend/pom.xml -Dtest=PaymentOperationsAdminControllerMvcTest,PaymentOperationsServiceTest,PayoutLifecycleIntegrationTest test
cd frontend/fluxpay-ui
node --test tests/admin-payment-operations.test.cjs tests/admin-integration.test.cjs tests/admin-navigation.test.cjs
```

Run the non-infrastructure full suite and formatting checks:

```bash
git diff --check
./mvnw -f backend/pom.xml spotless:check
./mvnw -f backend/pom.xml test
python3 -B -m unittest discover -s tests -v
cd frontend/fluxpay-ui
npm test
npm run typecheck
npm run build
cd ../..
```

For genuine Oracle/Kafka acceptance, stop the application backend, reset only the test
schema, load the environment, and explicitly activate the integration profile:

```bash
python3 -B scripts/reset-local-db.py --schema FLUXPAY_TEST --execute
set -a
. ./.env
set +a
ORACLE_TESTS_ACTIVE=true \
ORACLE_TEST_USERNAME=FLUXPAY_TEST \
./mvnw -f backend/pom.xml -Pintegration verify
```

`ORACLE_TEST_JDBC_URL`, `ORACLE_TEST_PASSWORD`, and `KAFKA_BOOTSTRAP_SERVERS` must be
present in `.env`; the integration profile must execute `BackendAcceptanceIT` against
real Oracle and Kafka. **skipped integration tests are not acceptance**. Confirm that
the run log contains executed integration tests and no skipped Oracle/Kafka tests.
If the prescribed `python` or `python -m ruff` aliases are unavailable, use `python3`
and the installed standalone `ruff` command, as in the commands above.

## Verification

The focused, frontend, full-suite, and genuine Oracle/Kafka commands for the payment
operations acceptance flow are collected in the
[event-flow verification section](#8-verification-commands). Unit and script checks do
not require frontend dependencies:

```bash
python3 -B -m unittest discover -s tests -v
./mvnw -f backend/pom.xml test
python3 -B scripts/test-all.py --suite backend
```

For a running local development backend, the smoke command creates a real draft,
publishes one canonical envelope for that payment, and waits for the exact event ID
to appear in its persisted timeline:

```bash
python3 -B scripts/test-all.py --suite e2e
```

The Oracle/Kafka integration profile must be run with the test schema stopped and
must execute `BackendAcceptanceIT`; skipped integration tests are not acceptance.

## Supported and intentionally unavailable behavior

The default application uses the HTTP Frankfurter FX adapter, Oracle repositories,
Kafka outbox relay, and JWT owner/admin authorization. `X-Local-User-Id` is never an
authentication mechanism. With no real provider credentials, payout operations
fail explicitly. KYC uses authenticated local document storage in gitignored
`temp_images`; users and administrators can preview PDFs and images. Pending and
approved applications are read-only, and only rejection permits resubmission.
Keep the document directory with your database backups. Local storage does not
provide malware scanning, encryption at rest or production retention management.

Compliance uses configurable source-currency amount thresholds to request review.
Policy indexing and copilot answers use Ollama and Oracle vector search; configure
the `FLUXPAY_OLLAMA_*`, `FLUXPAY_POLICY_CHUNKER_VERSION`, `FLUXPAY_COPILOT_*`, and
`FLUXPAY_COMPLIANCE_*` settings in `.env.example` for the local providers.

Development-only demo funding, simulated compliance, and simulated
payout rails are off by default and require their explicit `FLUXPAY_DEVELOPMENT_*`
or funding toggles. Legacy metadata KYC records state that files were not stored
and cannot be approved. JSON metadata submissions are rejected; use the real
multipart upload instead. It needs no development toggle.

## Transfer routing

Rails are code-shipped and trusted: `INTERNAL_LEDGER` delivers to another FluxPay wallet,
`BANK_NETWORK` delivers through a conventional bank network, `REAL_TIME_NETWORK` delivers
through a real-time payment network, and `PARTNER_NETWORK` delivers through a regional partner.
One rail implementation serves every provider bound to its rail type.

Administrators manage the catalogue from the dashboard (Administration → Transfer routing)
or through the admin APIs:

- `GET /api/admin/rail-types` lists the installed rail types and their supported destinations;
  `GET /api/admin/rail-types/{railType}` describes one rail.
- `GET/POST /api/admin/providers`, `GET/PUT/DELETE /api/admin/providers/{id}` manage providers
  (code, name, rail type, active).
- `GET/POST /api/admin/routes`, `GET/PUT/DELETE /api/admin/routes/{id}` manage routes (provider,
  code, destination, country, currency, fee, spread, ETA, configured success rate, limits,
  active).

Codes are uppercase and immutable; a used provider/route binding cannot change; used or
system-protected records archive instead of deleting. Deletes accept `?version=` for optimistic
locking and return `{disposition: DELETED|ARCHIVED}`.

Smart routing filters candidates by transfer context (active and unarchived provider/route,
destination corridor, installed compatible rail), blends the configured success rate with
terminal `COMPLETED`/`FAILED` outcomes using a 20-attempt prior, and ranks deterministically by
`CHEAPEST`, `FASTEST`, or `BALANCED` preference. Every quote generation persists at most the top
three quotes, and several winners may belong to the same provider. Wallet-to-wallet transfers
rank internal routes with `BALANCED`, execute the winner once through the internal ledger rail,
and return its `providerCode`, `routeCode`, `railType`, and `effectiveReliability`.

Enable simulated external rails locally with
`FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true`. When the demo route policy block is
present, `scripts/seed-local.py` validates the selected seeded routes and activates only
those inactive routes and their provider (both `BANK_STANDARD` and `BANK_EXPRESS` are
seeded under `BANK_ALPHA`); unrelated active flags remain unchanged. A seed without a
configured demo policy remains insert-only. The simulated bank rail records definitive
route-policy failures for the configured attempts, while the legacy
`SIMULATE_FAILURE=BANK_NETWORK` probe remains available for uncertain-timeout/reconciliation
demonstrations (`BANK_NETWORK:2` fails only the next two attempts per transfer, then
completes).

## Backend structure

Production code under `backend/src/main/java/com/fluxpay` follows these shared
packages. Tests mirror them under `backend/src/test/java/com/fluxpay`.

| Package | Responsibility |
| --- | --- |
| `controller` | HTTP endpoints, including policy indexing and copilot |
| `service` | Application workflows and compliance assessment |
| `dto` | Request, response, and boundary data records |
| `common/contracts` | Ports implemented by providers and persistence adapters |
| `adapter/ollama`, `adapter/persistence` | Ollama HTTP and Oracle vector implementations |
| `config` | Spring wiring and validated configuration properties |
| `exception` | Application and provider exceptions |
| `beans`, `repository` | Persistent entities and repository interfaces |
| `domain`, `messaging`, `development` | Domain rules, event delivery, and opt-in development implementations |

Name code for its responsibility, without milestone/member prefixes or a separate
`m5` package. New compliance, vector, and copilot properties use
`fluxpay.compliance.*`, `fluxpay.vector.*`, and `fluxpay.copilot.*`. Legacy
`fluxpay.m5.*` values for the settings listed in `application.yml` and `M5_*`
environment variables remain fallback inputs; the new `FLUXPAY_*` inputs take
precedence over those fallbacks.

Versioned Flyway migrations `V601`–`V605` retain their original filenames, SQL,
and constraint names to preserve existing migration history. The stored
`m5-sentence-v1` chunker identifier also remains unchanged because the chunking
algorithm has not changed. These compatibility identifiers are not package or
class naming conventions.

## Formatting

Activate hooks with `git config core.hooksPath .githooks`. Run Java formatting with
`.\mvnw.cmd -f backend/pom.xml spotless:apply`, Python formatting with `ruff format .`,
and frontend formatting independently when frontend work is in scope.
