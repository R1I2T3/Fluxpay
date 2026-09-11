# FluxPay M2 FX Conversion Design

**Scope:** Complete the backend FX snapshot, preview, and wallet-conversion slice from
the team Member 2 specification. Wallet listing, ledger-list APIs, scripts, and
frontend work remain separate.

## Boundaries

- Continue on `member2-wallet-ledger` and update PR #3.
- Preserve `FxRateProvider.rate(String, String)`, `LedgerWriter.append(...)`, the
  existing migrations, `pom.xml`, shared security, Kafka, and frontend files.
- Support only directed pairs between USD, EUR, and INR; reject identical currencies.
- Send and store monetary/rate values as decimal strings. Money is scale 4 with
  `HALF_EVEN`; a request with more than four decimal places is rejected, not rounded.

## FX snapshots and cache

`FxSnapshotSource` fetches one immutable `FxSnapshot`. `FrankfurterFxProvider` is
the live HTTP adapter and `M2MockFxRateProvider` is a deterministic offline source.
`M2FxConfig` selects the source with `fluxpay.fx-mode` (`live` by default, `mock`
for solo/demo), supplies a UTC clock, the configured provider URL, and the system
account UUID. The FX system UUID uses `fluxpay.fx-system-user-id`, falling back to
the existing demo-system UUID for local compatibility.

`FxQuoteService` implements the frozen `FxRateProvider` and exposes the same cached
snapshot to preview and conversion. A concurrent map contains at most the six valid
directed pairs. A per-pair monitor permits one refresh at a time. A snapshot is fresh
for one hour; a refresh failure may return a prior snapshot up to two hours old with
`stale=true`; no usable snapshot raises `FxUnavailableException`. The live adapter
uses Java's existing HTTP client with three-second connect/request timeouts and
validates the returned pair and positive rate. The mock source defines USD/INR
83.50 and USD/EUR 0.92 and derives inverse/cross pairs at high precision.

## Conversion and accounting

`WalletConversionService` authenticates/normalizes the request, checks the
`CONVERT` operation before any FX lookup, returns an exact completed snapshot on
replay, and rejects a changed payload. New work obtains exactly one `FxSnapshot`
and calls the separately proxied transactional `WalletPostingService`.

The posting transaction claims the unique operation, finds the owned source wallet,
creates the owned target wallet if needed, finds the configured system user's two
`FX_CLEARING` wallets and source-currency `FEE_REVENUE` wallet, and posts one journal:

1. debit customer source by gross;
2. credit source FX clearing by net;
3. credit source fee revenue by fee when the rounded fee is positive;
4. debit target FX clearing by credited amount;
5. credit customer target by credited amount.

The journal balances independently in each currency. Every line uses a deterministic
operation-derived key. The existing persistent writer remains the only balance
mutation path, enforces available funds, and orders locks canonically. The response
stores the exact rate, fetch time, stale/mock flags, monetary results, wallet IDs,
and journal reference. A failure rolls back the operation, target-wallet creation,
ledger rows, and all balances.

## HTTP and errors

- `GET /api/fx/rate?from=USD&to=INR` returns an authenticated advisory snapshot.
- `POST /api/wallets/convert` requires authentication and `Idempotency-Key`.
- Invalid pairs/amounts/keys return 400; idempotency/race conflicts return 409;
  insufficient available funds return 422; unavailable FX or missing configured
  system accounts return 503. Existing API envelopes and correlation IDs are used.

## Verification

Unit tests cover every mock pair, live-response validation, fresh/stale/expired and
single-flight cache behavior, request normalization, replay-before-FX, conflicts,
and error mapping. Isolated Oracle tests prove the five-line example, per-currency
balance, exact replay, rollback, and concurrency behavior. The complete M2 Oracle
runner and Spotless/diff checks must pass before commit and push.
