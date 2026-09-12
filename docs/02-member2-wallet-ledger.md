# Member 2: wallet and ledger backend progress

This implementation follows the revised team specification of 2026-09-10 and its
horizontal packages. It provides the complete five-endpoint Member 2 wallet API,
backed by Oracle wallet, ledger and idempotency persistence.

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
  requests and response snapshots. Funding and conversion services use them for
  exact replay and concurrent-request handling.
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
- Step 6: authenticated `GET /api/wallets` returns only the signed user's customer
  wallets, ordered by currency, with wallet ID, currency, held balance and
  available balance represented as exact decimal strings.
- Authenticated `GET /api/wallets/{walletId}/ledger` returns that customer wallet's
  entries in stable `created_at DESC, id DESC` pages. Page numbers start at zero,
  the default size is 20, and accepted sizes are 1 through 100.
- Missing, foreign and system wallet IDs all return the same non-leaking 404.
  Ledger responses omit internal idempotency keys and never expose another wallet's
  entries or the system-side lines of a journal.
- Step 7: `scripts/seed_m2.py` funds an existing authenticated demo user through
  the public wallet API with deterministic idempotency keys, then prints the
  user's current balances. It does not create users or system wallets and never
  prints the bearer token.
- `scripts/check_m2_ledger.py` performs a read-only Oracle check. It reports
  journal debit/credit imbalances independently per currency and separately lists
  legacy entries with no journal reference. It does not update data or claim that
  ledger history reconciles balances created before journaling existed.

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
zero held funds and may carry signed balances; the posting services enforce their
accounting use. Accounts must always be explicitly assigned a role.

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

The suite contains 146 cases, including calculation, repository/schema,
legacy-preflight, journal/writer, demo-funding, FX provider/cache, conversion
service, atomic conversion posting, wallet/ledger reads and authenticated controller
coverage. Expected constraint-rejection and concurrency tests may log Oracle errors
even when assertions pass. Flyway 10.22.0 reports a compatibility warning for
Oracle 23.26; these checks run against the real installed database.

### Local M2 scripts

Demo seeding requires the backend to be running with demo funding enabled, an
existing customer user, and `DEMO_CLEARING` system wallets for USD, INR and EUR.
Set the authenticated user's UUID and signed bearer token in the current
PowerShell session, then run:

```powershell
$env:M2_USER_ID="<existing-user-uuid>"
$env:M2_BEARER_TOKEN="<signed-jwt>"
python scripts\seed_m2.py
Remove-Item Env:M2_BEARER_TOKEN
```

The script requests USD 500, INR 10000 and EUR 50. Its stable idempotency keys
make an exact rerun replay the same three operations rather than applying them a
second time; it does not top balances up to those amounts.

Install the Oracle Python driver once, then run the reconciliation report using
the private Oracle settings already stored in `.env`:

```powershell
python -m pip install oracledb
python scripts\check_m2_ledger.py
```

The ledger check returns exit code 0 when all grouped journals balance per
currency, 1 when it finds an imbalance, and 2 for configuration or runtime
errors. Ungrouped legacy entries are reported separately and do not by themselves
change the grouped-journal result. This is an on-demand report, not a scheduled
job.

The Python script suite runs with:

```powershell
python -m unittest discover -s tests -v
```

It contains 16 cases, including six tests for API-only seeding, token secrecy,
read-only Oracle access, imbalance detection and ungrouped-entry reporting.

## Next steps (not implemented yet)

1. Frontend wallet integration.

`PersistentLedgerWriter.append(...)` remains the only runtime mutation path for
posted balances. Demo receive and FX conversion are implemented; the wallet-list
and ledger-history APIs are read-only. No shared contract, security, POM, router or
other member's code is changed.
