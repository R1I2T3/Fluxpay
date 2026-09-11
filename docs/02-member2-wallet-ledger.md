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
- Step 4: authenticated `POST /api/wallets/receive-demo` credits a customer wallet
  from a configured `DEMO_CLEARING` wallet through a balanced two-line journal.
- Demo funding is disabled by default. It reads
  `fluxpay.demo-funding-enabled` and `fluxpay.demo-system-user-id` without changing
  shared application configuration.
- `DemoFundingService` normalizes requests and resolves operation replay outside
  the posting transaction. `WalletPostingService` creates the operation/customer
  wallet, posts both ledger lines, stores the response snapshot and commits all
  changes atomically.
- An exact completed replay returns the original response without another balance
  change or ledger row. A changed request with the same operation key is rejected,
  and losing concurrency attempts reload the committed winner only after rollback.
- `WalletController` uses the signed `CurrentUser` principal, the required
  `Idempotency-Key`, existing API envelopes and M2-scoped error responses. It never
  accepts a user, destination wallet or clearing account from the caller.
- Step 5: authenticated `GET /api/fx/rate` previews a rate and
  `POST /api/wallets/convert` performs an idempotent customer conversion.
- `FxQuoteService` supports all directed USD/EUR/INR pairs, caches each snapshot
  for one hour, allows a failed refresh to reuse a snapshot for at most two hours,
  and coalesces concurrent refreshes for the same pair.
- FX mode is selected with `fluxpay.fx-mode`: `live` uses the configured
  Frankfurter-compatible endpoint with three-second timeouts, while `mock` (or
  `solo`) derives deterministic inverse and cross rates without network access.
- A conversion obtains one FX snapshot and posts a balanced journal in both
  currencies: customer source debit, source clearing credit, optional fee revenue
  credit, target clearing debit and customer target credit. A fee line rounded to
  zero is omitted.
- Completed operations are replayed before any FX call. The stored response keeps
  the exact rate, calculated amounts and snapshot metadata returned originally.

### FX configuration

- `fluxpay.fx-mode=live` selects live rates; use `mock` for local/offline work.
- `fluxpay.fx-provider-url` is the live provider base URL.
- `fluxpay.fx-system-user-id` identifies the owner of FX clearing and fee-revenue
  wallets; it falls back to `fluxpay.demo-system-user-id` when omitted.
- The configured system user needs `FX_CLEARING` wallets for source and target
  currencies and a `FEE_REVENUE` wallet in the source currency when the fee is
  nonzero.

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

The suite contains 132 cases, including calculation, repository/schema,
legacy-preflight, journal/writer, demo-funding, FX provider/cache, conversion
service, atomic conversion posting and authenticated controller coverage. Expected
constraint-rejection and concurrency tests may log Oracle errors even when
assertions pass. Flyway 10.22.0 reports a compatibility warning for Oracle 23.26;
these checks run against the real installed database.

## Next steps (not implemented yet)

1. The remaining authenticated wallet-list and ledger-history endpoints, including
   ownership, pagination, serialization and error tests.
2. Reconciliation/seed scripts and frontend wallet integration.

`PersistentLedgerWriter.append(...)` remains the only runtime mutation path for
posted balances. Demo receive and FX conversion are implemented; the remaining
read APIs are future work. No shared contract, security, POM, router or other
member's code is changed.
