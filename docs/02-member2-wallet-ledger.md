# Member 2: wallet and ledger backend progress

This implementation follows the revised team specification of 2026-09-10 and its
horizontal packages. It is a persistence and ledger-posting foundation, not a
completed wallet API.

## Implemented

- Step 1: `M2ConversionMath` calculates the 0.5% fee and net conversion using
  `BigDecimal`, four decimal places and `HALF_EVEN`. It rejects missing/nonpositive
  inputs, source inputs with more than four decimal places (including trailing
  zeros), monetary overflow and zero target proceeds. Rates retain their precision.
- Step 2: `Wallet`, `LedgerEntry`, `WalletOperation` and `WalletAccountRole` in
  `beans`, plus the three corresponding repositories in `repository`.
- UUIDs explicitly map to Oracle `RAW(16)`. Timestamps map to Oracle `TIMESTAMP`.
  Money getters normalize Oracle's variable returned decimal scale to four places.
- Available funds are posted `balance` minus `held_balance`; no competing
  persisted available-balance column is introduced.
- `@Version` supports optimistic concurrency; explicit row-lock repository
  queries support the next step's posting transactions. Multi-wallet locking uses
  Oracle RAW/canonical UUID text order, not Java UUID's signed `compareTo` order.
- Ledger rows have no setters, Hibernate treats them as immutable, and the ledger
  repository exposes insertion and reads only. This is not a database-level ban
  on privileged SQL updates/deletes.
- Operation rows enforce uniqueness by user/type/client key and store normalized
  requests and response snapshots. Complete replay/race handling belongs to the
  future service layer; the table alone does not implement idempotent requests.
- Step 3: `PersistentLedgerWriter` implements the frozen `LedgerWriter` contract
  and requires an existing transaction. It validates each posting, locks its
  wallet, applies one debit or credit, and appends one immutable ledger entry.
- Customer debits check available funds; system wallets may carry signed balances.
  An exact entry replay has no effect, while reuse of a key with changed payload or
  metadata is rejected.
- `LedgerJournalService` validates complete journals per currency and posts wallets
  in canonical UUID order. Every line receives the same journal reference and its
  own narration through a `LedgerPostingContext`, which is cleared after success or
  failure without changing the shared writer interface.
- A journal cannot mix replayed and new entries. Wallet changes and ledger entries
  use one outer transaction, so an intermediate failure rolls back every line.

## Migration V202

`V201__wallet_ledger.sql` and all other existing migrations are unchanged.
`V202__m2_wallet_ledger_extensions.sql` adds:

- Wallet role, held balance, version, supported-currency/role/funds constraints,
  and unique `(user_id,currency,account_role)` keys.
- Nullable ledger journal/narration fields, indexes, and positive-amount/type/
  currency constraints. Existing entries remain ungrouped.
- `wallet_operations`, including request identity, state and response constraints.

Customer wallets require `balance >= held_balance >= 0`. System wallets require
zero held funds and may carry signed balances; the future posting services enforce
their accounting use. Accounts must always be explicitly assigned a role.

### Legacy data prerequisite

The first PL/SQL block validates existing data before any migration DDL. On a
nonempty V201 schema it refuses to infer roles. The owner must inspect wallet UUIDs
and their purpose, add nullable `ACCOUNT_ROLE VARCHAR2(20)` if absent, and assign a
reviewed role to every wallet. Do not blanket-classify all wallets as CUSTOMER.

The migration reports unclassified/unknown roles, invalid funds, duplicate future
keys, unsupported currencies, invalid versions and invalid legacy ledger values.
It never deletes, resets balances or fabricates historical journals. Oracle DDL is
not transactional: investigate any DDL failure rather than blindly repairing
Flyway history or rerunning partial changes.

Databases already at V301/V401/V501 require a reviewed one-time out-of-order V202
migration. Do not disable validation or edit old checksums. Out-of-order migration
is explicitly enabled in the isolated tests; normal application configuration is
unchanged. Coordinate migration version 202 with the team before merging.

## Testing

Regular unit tests require Java 17 and Maven's wrapper:

```powershell
.\mvnw.cmd -f backend\pom.xml test
```

Oracle tests are opt-in. Without the opt-in they are skipped, not evidence of
database verification. They require two dedicated users on the configured Oracle
PDB: `FLUXPAY_M2_TEST` and `FLUXPAY_M2_LEGACY_TEST`, each with CREATE SESSION, TABLE,
SEQUENCE, TRIGGER, PROCEDURE privileges and a tablespace quota. Provisioning is an
explicit local administrator task. Both must use the supplied test password.

On this laptop they were provisioned separately from `FLUXPAY_VSCODE`, using the
local development password from the private `.env`. Credentials are not committed.

Run the complete suite from the project root:

```powershell
python backend\src\test\resources\m2\run_oracle_tests.py
```

For a different test password/URL, supply `--env-file` pointing to a private file
containing `ORACLE_JDBC_URL` and `ORACLE_PASSWORD`. The runner fixes the test username
and passes settings only to the Maven child process, so later application starts
in the same terminal cannot accidentally inherit the test schema selection.

The main test schema runs the actual migration chain and validates JPA mappings,
foreign keys, funds constraints, operation/entry uniqueness, pagination, row
locking, transactional posting, exact replay, rollback and concurrent debits.
Tests generally roll back their fixtures. Locking/concurrency tests use committed,
non-login fixture users and wallets in this dedicated schema so they test separate
database sessions rather than uncommitted inserts.

The legacy test schema stays at V201 with an explicit nullable role column. Tests
execute the actual V202 preflight block against classified/unclassified and invalid
fixtures, then roll back. They do not apply final V202 constraints in that schema.
Neither suite cleans, truncates, resets or uses the application's development schema.

The suite contains 28 calculation cases, 19 repository/Oracle cases, 2 schema
checks, 6 legacy-preflight cases, 5 journal unit cases, 10 persistent-writer Oracle
cases, and 7 complete-journal Oracle cases. Expected constraint-rejection tests may
log Oracle errors even when assertions pass. Flyway 10.22.0 reports a compatibility
warning for Oracle 23.26; these checks run against the real installed database.

## Next steps (not implemented yet)

1. Demo funding, signed-auth fixtures, request normalization and operation replay.
2. Live/mock FX snapshots and bounded cache, followed by conversion orchestration.
3. Five authenticated endpoints and their ownership/serialization/error tests.

`PersistentLedgerWriter.append(...)` is now the runtime mutation path for posted
balances. The receive-demo/conversion orchestration and public APIs are still future
work. No shared contract, security, POM, router or other member's code is changed.
