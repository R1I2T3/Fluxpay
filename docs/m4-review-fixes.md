# M4 upgrade and verification

V301 and the compliance V401 retain their original contents from commit `43ef969`.
V503 upgrades the legacy payout schema and V504 adds the payment timeline, so an
existing database already at V501 or V502 can migrate forward with validation enabled.
V503 keeps `payout_routes_legacy` and `payout_attempts_legacy`, copies historical
attempt IDs and payment UUIDs, and numbers attempts chronologically (ID breaks ties).
It maps PENDING to INITIATED and SUBMITTED to PROCESSING. Legacy routes remain
inactive because their old schema contains no execution/pricing configuration.

Databases that already applied the conflicting M4 version of V401 or the removed
V402/V403 need a separate reconciliation based on their actual Flyway history and
schema. These migrations target the original deployed history; do not run checksum
repair blindly on a database with the conflicting history.

The payout, recovery, route catalog/admin, and timeline HTTP components currently
require `mock`, matching the available payment, quote, FX, ledger, and provider
implementations. Use `python scripts/start-backend.py --profile mock` for the demo.
The Kafka timeline consumer and persistence remain available in `local` and
`integration` profiles.

All recovery actions lock the first attempt row before reading the latest attempt.
The lock remains held through transaction completion. Refund ledger replay may
republish the same deterministic event ID until timeline persistence confirms
delivery; the consumer deduplicates this ID. Infrastructure failures escaping the
consumer retry indefinitely, including failed poison-event quarantine sends.

Run the backend tests with `mvnw.cmd -f backend/pom.xml clean verify` and script tests
with `python -m unittest discover -s tests -v`. Recovery concurrency tests use H2
with separate transactions. The Oracle/Kafka persistence test remains gated by
`ORACLE_JDBC_URL` and `KAFKA_BOOTSTRAP_SERVERS` and must be run explicitly with
`-Dtest=PaymentEventPersistenceIT test` against a configured test environment.
