# FluxPay

FluxPay is a Java 17 / Spring Boot 3.2.5 payment backend with Oracle persistence,
Kafka-backed durable events, JWT authentication, wallet/ledger accounting, quotes,
payments, payout orchestration, recovery, and timeline APIs. The frontend is outside
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

After the reset, start the backend once so Flyway installs `V001` through `V005`, run
the seed twice to prove idempotence, copy the reported system UUID into
`FLUXPAY_SYSTEM_USER_ID`, then restart the backend:

```powershell
python -B scripts/seed-local.py
python -B scripts/seed-local.py
python -B scripts/check-ledger.py
```

The seed registers four local identities with the real `fullName`/password contract,
promotes only its local admin/system identities through a schema-scoped provisioning
path, creates required system wallets, and upserts three routes. Passwords come from
`SEED_SYSTEM_PASSWORD`, `SEED_ADMIN_PASSWORD`, and `SEED_CUSTOMER_PASSWORD`.
Public registration never accepts a role.

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
authentication mechanism. With no real provider credentials, payout, compliance,
and KYC document-storage operations fail explicitly; the application does not claim
production readiness for those integrations.

Development-only metadata KYC, demo funding, simulated compliance, and simulated
payout providers are off by default and require their explicit `FLUXPAY_DEVELOPMENT_*`
or funding toggles. Metadata KYC records state that files were not stored. Embedding
and policy-vector work remains intentionally unfinished.

## Formatting

Activate hooks with `git config core.hooksPath .githooks`. Run Java formatting with
`.\mvnw.cmd -f backend/pom.xml spotless:apply`, Python formatting with `ruff format .`,
and frontend formatting independently when frontend work is in scope.
