# FluxPay

FluxPay is a Java 17 / Spring Boot 3.2.5 payment backend with Oracle persistence,
Kafka-backed durable events, JWT authentication, wallet/ledger accounting, quotes,
payments, payout orchestration, recovery, timeline APIs, policy indexing, and a
policy-grounded compliance copilot. The frontend is outside
the backend-cleanup verification scope.

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

Start and provision topics:

```powershell
python -B scripts/start-infra.py --mode compose
python -B scripts/start-backend.py
```

The topic inventory is `payment.initiated`, `payment.route.selected`,
`payment.screening.completed`, `payment.review.requested`, `payout.submitted`,
`payout.failed`, `payout.completed`, `payment.refunded`, and
`payout.recovery.dlt`.

## Local schema reset and seed

`reset-local-db.py` accepts only `FLUXPAY` and `FLUXPAY_TEST`, rejects remote Oracle,
checks live connection metadata and target existence, and is dry-run unless
`--execute` is supplied. Stop the backend first. No export is created automatically.

```powershell
python -B scripts/reset-local-db.py --schema FLUXPAY
python -B scripts/reset-local-db.py --schema FLUXPAY --execute
python -B scripts/reset-local-db.py --schema FLUXPAY_TEST
python -B scripts/reset-local-db.py --schema FLUXPAY_TEST --execute
```

In Compose mode the execute path removes and recreates only the nine FluxPay topics;
it never removes volumes or unrelated topics. In external mode, set
`FLUXPAY_EXTERNAL_BROKER_FRESH=true` only after verifying that the dedicated broker
contains no stale FluxPay events.

After the reset, start the backend once so Flyway installs the versioned migrations, run
the seed twice to prove idempotence, copy the reported system UUID into
`FLUXPAY_SYSTEM_USER_ID`, then restart the backend:

```powershell
python -B scripts/seed-local.py
python -B scripts/seed-local.py
python -B scripts/check-ledger.py
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

## Verification

Unit and script checks do not require frontend dependencies:

```powershell
python -B -m unittest discover -s tests
.\mvnw.cmd -f backend/pom.xml test
python -B scripts/test-all.py --suite backend
```

For required Oracle/Kafka acceptance, set `ORACLE_TESTS_ACTIVE=true`; configure
`ORACLE_TEST_JDBC_URL`, `ORACLE_TEST_USERNAME`, `ORACLE_TEST_PASSWORD`, and
`KAFKA_BOOTSTRAP_SERVERS` in `.env`; reset only `FLUXPAY_TEST`; and run:

```powershell
.\mvnw.cmd -f backend/pom.xml -Pintegration verify
python -B scripts/test-all.py --suite backend
```

The integration profile fails before tests when credentials are incomplete, when the
username is not exactly `FLUXPAY_TEST`, or when activation is absent. It runs the real
Oracle/Kafka flow in `BackendAcceptanceIT`; a skipped suite is not acceptance.

For a running local development backend, the smoke command creates a real draft,
publishes one canonical envelope for that payment, and waits for the exact event ID
to appear in its persisted timeline:

```powershell
python -B scripts/test-all.py --suite e2e
```

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
`FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true`; the demo external providers stay inactive
until an administrator activates them after enabling the flag. The simulated bank rail records
`UNCERTAIN` delivery while `SIMULATE_FAILURE=BANK_NETWORK` is set (`BANK_NETWORK:2` fails only
the next two attempts per transfer, then completes); uncertain operations record no terminal
outcome and are retried through reconciliation.

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
