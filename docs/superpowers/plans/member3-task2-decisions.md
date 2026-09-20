# Member 3 Task 2: fees, quotes, and holds

This step updates the existing wallet conversion flow. Transfer, withdrawal, and
bank top-up endpoints remain Task 3 work.

## Calculation policy

The default conversion fee remains 0.5% of the source amount, with per-source-
currency configuration replacing the hardcoded math constant. Fee and credited
amount rounding uses `HALF_UP`, as specified in the team plan. Business decimal
places come from `currencies`; the initial USD, EUR, and INR rows specify two.

Oracle `NUMBER(19,4)` storage is retained. This is different from business
rounding: existing rows and completed-operation responses must not be rewritten.
Other tracks' already accepted payment quotes and refunds retain their contracts.

## Quote acceptance

Stale FX data can still be shown by a preview, but must not fund a new conversion.
A conversion requires a valid server snapshot for the requested pair. A rejected
quote produces `REQUOTE_REQUIRED`; completed idempotent retries return the saved
response without obtaining a new quote. Accepted rate precision must match the
rate stored with the journal, and the journal records a quote identifier.
The accepted rate is normalized to the ledger's eight-place rate precision before
calculation. Since the provider snapshot has no quote ID, a deterministic UUID
identifies its pair, accepted rate, and fetch timestamp across retries.

## Hold boundary

For the current synchronous conversion, the hold, debit, and release/consumption
belong to one transaction. Only this operation's reservation may be consumed;
pre-existing holds remain in place. A failed posting rolls back its reservation
and ledger mutations together. This step does not introduce asynchronous holds,
hold endpoints, or a separate durable hold lifecycle.

Lock order remains journal-reference bucket first, then posting wallets in the
same canonical order. A source reservation must not reverse that order.
The journal service owns the reserved-posting path and checks replay before
reserving. Reservation metadata identifies exactly one customer debit, which the
ledger writer consumes without spending money reserved by another operation.

## Compatibility and verification

Use focused math, conversion, ledger, and transaction tests. Do not reset or
migrate the application's database as part of this implementation step. The
existing Task 1 upgrade notes still apply when the branch is deployed later.
