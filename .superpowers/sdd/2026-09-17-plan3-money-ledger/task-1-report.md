# Task 1 report — ledger foundation

## Outcome

Implemented V007/V008, bank-account and currency metadata, FX rate/quote persistence,
the `FX_GAIN_LOSS` wallet role, and concurrency-safe journal identity. Existing
`LedgerWriter` signatures and two-argument `LedgerJournalService.post` call semantics
remain intact; those calls are categorized as `LEGACY`.

Journal references are unique on `ledger_journals`, not ledger lines. A 64-row lock
table serializes references by a stable bucket before header lookup/insert. New
journals store a SHA-256 fingerprint of category and an order-independent,
length-delimited canonical line set. Exact replay returns before balance mutation;
different payload, line set, or category raises `LedgerIdempotencyConflictException`.
Historical headers are backfilled with a nullable hash and compare their complete
persisted line set on replay. Standalone historical rows with a NULL reference remain
valid.

## RED evidence

- `LedgerJournalServiceTest` before header enforcement: 10 tests, 2 failures.
  `rejectsAnExistingJournalReferenceWithDifferentPayload` allowed a second posting;
  `rejectsSubsetAndSupersetReplays` demonstrated incomplete journal identity.
- Delimiter-collision regression before per-field encoding: 1 test, 1 failure;
  changing the boundary between idempotency key and narration incorrectly appeared
  identical.
- First Oracle run applied both migrations successfully, then JPA validation rejected
  CHAR/VARCHAR mapping mismatch. Entity column definitions were aligned to the
  required CHAR schema and the same migrated schema was reused without reset.

## GREEN evidence

Java 17 with `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Temp`:

- Focused H2 journal test: `LedgerJournalServiceTest` — 13 tests, 0 failures/errors.
- Final local accounting set: `LedgerJournalServiceTest, PaymentAccountingTest,
  RefundJournalServiceTest, PersistentLedgerWriterPrimaryTest,
  PersistentLedgerWriterClockTest` — 40 tests, 0 failures/errors/skips.
- Compatibility run: `PayoutLifecycleIntegrationTest` — 57 tests passed in the prior
  focused run; its only companion failure was a stale direct test insert subsequently
  corrected and covered by the final local run.
- Dedicated Oracle upgrade/test: `python -B logs/member3-oracle-tests.py
  LedgerJournalOracleTest` — V007/V008 validated on the existing V605 test schema;
  7 tests, 0 failures/errors/skips, including concurrent identical replay.
- Dedicated Oracle writer test: `python -B logs/member3-oracle-tests.py
  PersistentLedgerWriterOracleTest` — 10 tests, 0 failures/errors/skips.
- Spotless check restricted to changed Java files — success.
- `git diff --check` — no whitespace errors.

The prepared historical `M3-UPGRADE-LEGACY` two-line journal was backfilled as
`LEGACY`; both USD lines and balances were retained. No application schema was used,
no database was reset, and no application process was restarted.

## Files and compatibility

- Added V007 bank accounts and V008 currency/journal correctness migrations.
- Added bank-account, currency, journal header/lock entities and repositories.
- Extended `LedgerEntry`, `LedgerJournalLine`, and posting context with paired nullable
  rate/quote metadata while retaining their existing constructors.
- Updated manual test fixtures that construct the journal service or insert journal
  rows directly. No payout/compliance/copilot controller or frontend file changed.

## Remaining concerns

- Existing V601-V605 deployments must explicitly enable Flyway out-of-order for this
  controlled upgrade; the production default remains false. See the committed member3
  upgrade notes.
- Sixty-four lock buckets intentionally trade a small amount of unrelated-journal
  contention for cross-node correctness without relying on privileged Oracle locks.
- Historical headers retain NULL fingerprints; their exact replay path reads and
  compares all persisted lines. Newly posted journals use constant-size hashes.
- Task 1 intentionally retains NUMBER(19,4) posting behavior. Currency-scale business
  rounding belongs to Task 2.
