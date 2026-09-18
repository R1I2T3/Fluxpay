# Admin-Managed Transfer Providers, Routes, and Smart Routing

**Date:** 2026-09-18  
**Status:** Approved in conversation; awaiting review of this written specification

## Summary

FluxPay currently couples three seeded payout route codes to three Spring `PayoutProvider`
implementations. Administrators can edit a route's commercial values, but cannot create providers or
routes. Execution then looks up a provider bean by route code, so adding a database row without a
matching Java bean creates an unusable route.

This design replaces that coupling with a shared transfer-routing catalogue:

- A finite registry of trusted `TransferRail` implementations remains shipped as application code.
- Administrators manage provider records such as HDFC Bank or SBI.
- Administrators manage multiple routes beneath each provider.
- Many providers and routes can reuse the same rail implementation.
- Internal wallet transfers and external transfers use the same catalogue while retaining different
  execution semantics.
- Smart routing filters candidates by transfer context, blends configured and observed reliability,
  ranks them deterministically, and persists only the top three quotes.

No runtime business logic will depend on route codes such as `STANDARD_BANK`, `INSTANT_PAYOUT`, or
`LOCAL_PARTNER`. Those strings may appear only in the data migration that preserves existing rows.

## Goals

1. Give administrators full CRUD control over transfer providers and routes from the dashboard.
2. Allow one code-shipped rail type to serve many providers, and one provider to own many routes.
3. Support both internal-wallet and external-recipient destinations through a shared route model.
4. Preserve existing quote, attempt, and route history during migration.
5. Remove route-code-specific pricing and execution behavior.
6. Improve smart routing with contextual eligibility, observed reliability, deterministic ranking,
   and a maximum of three returned quotes.
7. Preserve idempotency, reconciliation, optimistic locking, and audit-safe deletion behavior.

## Non-goals

- Installing Java code or arbitrary connectors through the dashboard.
- Configuring arbitrary HTTP endpoints, request mappings, scripts, or provider credentials.
- Provider health checks, secret management, or unavailable-connector diagnostics.
- Machine-learning ranking or administrator-configurable scoring weights.
- Enforcing provider diversity in the top three results.
- Reimplementing the wallet-to-wallet transfer feature that is already in progress.

## Dependency on the Wallet-Transfer Work

The wallet-to-wallet feature is an implementation prerequisite. Before this design is implemented,
its branch must be integrated and its migrations, destination model, idempotency boundary, and
terminal outcome recording inspected.

This work will not create a competing wallet-transfer workflow. It will adapt that workflow to the
shared catalogue and the `INTERNAL_LEDGER` rail. If the incoming work uses different class or table
names, the implementation will preserve its money-movement semantics while applying the domain
contracts and behavior defined here.

## Domain Terminology

### Transfer rail

A `TransferRail` is trusted, code-shipped execution behavior. A rail is selected by a finite
`RailType`, not by an administrator-created route code.

Initial rail types are:

| Rail type | Purpose | Initial implementation |
| --- | --- | --- |
| `INTERNAL_LEDGER` | Deliver to another FluxPay wallet | Adapter over the in-progress wallet-transfer workflow |
| `BANK_NETWORK` | Deliver through a conventional bank network | Renamed standard-bank simulator |
| `REAL_TIME_NETWORK` | Deliver through a real-time payment network | Renamed instant-payment simulator |
| `PARTNER_NETWORK` | Deliver through a local or regional partner | Renamed local-partner simulator |

The implementation class may say `Simulated`, but simulation language is not exposed in the
business model. Adding a new rail type requires application code and deployment.

### Transfer provider

A `TransferProvider` is an administrator-managed institution or platform that uses one rail type.
Examples are FluxPay, HDFC Bank, and SBI. A provider has many routes.

### Transfer route

A `TransferRoute` is an administrator-managed commercial and eligibility configuration beneath one
provider. Examples include `HDFC_INR_STANDARD` and `HDFC_INR_EXPRESS`. A route belongs to exactly one
provider.

## Architecture

The application builds a registry of its Spring `TransferRail` implementations at startup:

```text
RailType -> TransferRail
```

The database holds providers and routes:

```text
TransferRoute -> TransferProvider -> RailType -> TransferRail
```

Execution no longer performs `routeCode -> Spring bean`. It loads the selected route, follows its
provider, and resolves the provider's rail type in the registry.

The rail contract accepts immutable execution context rather than querying mutable catalogue data
mid-delivery:

```text
TransferRail.execute(
  durable command,
  provider snapshot,
  route snapshot,
  internal-wallet or external-recipient destination snapshot
)
```

The durable command retains the existing stable attempt/operation idempotency key. The internal rail
delegates to the wallet-transfer workflow; external rails return completed, definitively failed, or
uncertain outcomes under the current reconciliation rules.

Each rail declares the destination types it supports. The initial capability mapping is:

- `INTERNAL_LEDGER`: `INTERNAL_WALLET`
- `BANK_NETWORK`: `EXTERNAL_ACCOUNT`
- `REAL_TIME_NETWORK`: `EXTERNAL_ACCOUNT`
- `PARTNER_NETWORK`: `EXTERNAL_ACCOUNT`

An incompatible provider/route combination is rejected on create, update, and activation.

## Persistence Model

### `transfer_providers`

| Column | Meaning |
| --- | --- |
| `id` | UUID primary key |
| `provider_code` | Unique uppercase business code; immutable |
| `provider_name` | Administrator-editable display name |
| `rail_type` | Code-shipped `RailType` |
| `active` | Whether the provider may receive new traffic |
| `system_protected` | Prevents permanent deletion of system-backed providers |
| `version` | Optimistic-lock version |
| `created_at`, `updated_at` | Audit timestamps |
| `archived_at` | Set when a historically used provider is archived |

Provider codes use `[A-Z][A-Z0-9_]{2,49}` and are normalized to uppercase before uniqueness checks.
The rail type may change only while none of the provider's routes has been referenced by a quote or
execution attempt.

### `transfer_routes`

The migration evolves the current `payout_routes` catalogue into `transfer_routes` after the
wallet-transfer work has landed.

| Column | Meaning |
| --- | --- |
| `id` | Existing UUID primary key |
| `provider_id` | Required foreign key to `transfer_providers` |
| `route_code` | Existing unique uppercase code; immutable |
| `route_name` | Administrator-editable display name |
| `destination_type` | `INTERNAL_WALLET` or `EXTERNAL_ACCOUNT` |
| `destination_country` | ISO-3166 alpha-2 country; required externally, optional internally |
| `payout_currency` | Required ISO-4217 destination currency |
| `base_fee` | Source-currency customer fee |
| `fx_spread_percentage` | Spread applied to the market rate |
| `estimated_minutes` | Expected delivery time |
| `configured_success_rate` | Administrator-entered reliability prior, from 0 through 100 |
| `minimum_recipient_amount` | Optional inclusive lower payout limit in `payout_currency` |
| `maximum_recipient_amount` | Optional inclusive upper payout limit in `payout_currency` |
| `active` | Whether the route may receive new traffic |
| `system_protected` | Prevents permanent deletion of system-backed routes |
| `version` | Optimistic-lock version |
| `created_at`, `updated_at` | Audit timestamps |
| `archived_at` | Set when a historically used route is archived |

Route codes use the same normalization rule as provider codes. A route's provider may change only
before the route has been referenced by a quote or execution attempt. Country codes and currencies
are normalized to uppercase. Amount limits must be positive when present, and the maximum must be
greater than or equal to the minimum.

The current `route_type` column and its `STANDARD`/`INSTANT`/`LOCAL_PARTNER` constraint are removed;
rail behavior belongs to the provider, and destination behavior belongs to the route.

### `transfer_route_outcomes`

Smart routing needs terminal outcomes from both internal and external execution without coupling the
ranking service to two different attempt schemas. A small append-only projection records:

| Column | Meaning |
| --- | --- |
| `id` | UUID primary key |
| `route_id` | Foreign key to `transfer_routes` |
| `execution_reference` | Unique wallet operation or external attempt reference |
| `outcome` | `COMPLETED` or `FAILED` |
| `occurred_at` | Terminal outcome time |

The internal wallet workflow and external payout finalization record one row transactionally when
an execution reaches a terminal state. The unique execution reference makes recording idempotent.
Uncertain, processing, and initiated operations do not enter this projection. Existing terminal
payout attempts are backfilled during migration.

### Quote ranking snapshot

Each newly generated payment quote persists enough information to reproduce its recommendation:

- provider identifier
- route identifier/code
- effective reliability at quote time
- ranking score
- ranking position
- already-frozen rate, spread, fee, recipient amount, and ETA

Older quote rows remain readable. Newly generated quote sets contain at most three rows.

## Administration API

All endpoints require the `ADMIN` role and retain the explicit persistent admin check used by the
existing route controller.

### Rail metadata

- `GET /api/admin/rail-types`

Returns the finite installed rail types, display labels, and supported destination types. It does
not accept configuration or credentials.

### Provider endpoints

- `GET /api/admin/providers`
- `POST /api/admin/providers`
- `PUT /api/admin/providers/{providerId}`
- `DELETE /api/admin/providers/{providerId}`

Create accepts provider code, name, rail type, and active status. Update accepts name, rail type,
active status, and expected version; immutable and used-field rules are enforced by the service.

### Route endpoints

- `GET /api/admin/routes`
- `POST /api/admin/routes`
- `PUT /api/admin/routes/{routeId}`
- `DELETE /api/admin/routes/{routeId}`

Create accepts provider, route identity, eligibility, payout limits, commercial values, and active
status. Update accepts mutable fields plus the expected version.

The existing customer quote and recommendation endpoints remain the route-selection entry points.
They return only routes eligible for the referenced transfer. A general active-route catalogue may
remain for informational UI, but it is never trusted for quoting or execution.

### Delete and archive result

`DELETE` returns an explicit result with disposition `DELETED` or `ARCHIVED`:

- An unused, non-system route is permanently deleted.
- A used or system-protected route is deactivated and archived.
- An unused, non-system provider with no routes is permanently deleted.
- A used or system-protected provider is deactivated and archived.
- A provider with child routes must have those routes removed or archived first; deletion never
  cascades unexpectedly.

Archived records remain available to admin history and reconciliation but cannot receive new
traffic.

## Admin Dashboard

The existing payout catalogue becomes a **Transfer routing** workspace with **Providers** and
**Routes** sections.

### Providers section

- Table columns: code, name, rail type, active/archive status, route count, version.
- Create/edit form: code, name, rail type, active status.
- Rail type is selected from `GET /api/admin/rail-types`.
- Used or system-protected records show an archive action instead of promising physical deletion.

### Routes section

- Table columns: code, name, provider, destination, country/currency, fee/spread, ETA, configured and
  effective reliability, limits, and status.
- Filters: provider, destination type, country, currency, and active/archive status.
- Create/edit form contains all route fields described in the persistence model.
- Provider choices are limited to compatible rail/destination combinations.
- The UI explains stale-version conflicts and refreshes the edited record rather than silently
  overwriting another administrator's change.

FluxPay's internal provider and routes are visible and editable but marked **System protected** and
cannot be permanently deleted.

## Smart-Routing Pipeline

### 1. Candidate eligibility

For the transfer being quoted, the service loads providers, routes, and terminal outcome aggregates
in bulk. A candidate survives only when:

1. The provider and route are active and not archived.
2. The route destination type matches the transfer destination.
3. The route country matches the external recipient country; internal routes may use a null country
   as an all-country wildcard.
4. The route payout currency matches the destination currency.
5. The registered rail supports the destination type.
6. Pricing produces positive net and recipient amounts.
7. The recipient amount falls within the route's optional inclusive limits.

An invalid candidate is excluded without failing otherwise valid candidates. If none remain, the
request returns `422 NO_ELIGIBLE_ROUTES`.

### 2. Route-driven pricing

Fees and spreads come entirely from the route row. The current `INSTANT_PAYOUT` surcharge in
`QuotePricingPolicy` is removed. Quote values remain frozen when the quote is created.

### 3. Blended reliability

Configured success rate acts as a prior equivalent to 20 terminal attempts. For each route:

```text
configuredProbability = configuredSuccessRate / 100
terminalAttempts = completedCount + failedCount
effectiveProbability =
  (configuredProbability * 20 + completedCount)
  / (20 + terminalAttempts)
effectiveReliability = effectiveProbability * 100
```

Only terminal completed and failed outcomes count. The prior prevents a new route from jumping to
0% or 100% after one outcome while allowing observations to dominate over time.

### 4. Preference ranking

`CHEAPEST` sorts by:

1. recipient amount descending
2. effective reliability descending
3. ETA ascending
4. route code ascending

`FASTEST` sorts by:

1. ETA ascending
2. recipient amount descending
3. effective reliability descending
4. route code ascending

`BALANCED` retains the current normalized weights:

- recipient amount: 45%
- inverse ETA: 30%
- effective reliability: 25%

When every candidate has the same value for a dimension, that normalized dimension contributes its
full weight equally. Balanced ties use route code ascending.

### 5. Top-three selection

After ranking, only the first three candidates are persisted and returned. Multiple selected routes
may belong to the same provider. The highest-ranked quote is marked recommended.

The response explanation includes the winning route's recipient amount, ETA, effective reliability,
and preference-specific score. It does not claim causal precision beyond these deterministic inputs.

## Execution and Reconciliation

New execution performs these checks before reserving delivery:

1. The selected quote belongs to the transfer and remains current.
2. Its route and provider still exist, are active, and are not archived.
3. The destination still matches the frozen transfer/recipient snapshot.
4. The provider's rail type resolves in the code-shipped registry.

The reservation snapshot stores route id/code, provider id/code, rail type, destination snapshot,
and the durable idempotency key. The rail executes outside the database transaction under the
existing reserve/deliver/finalize boundary.

Deactivation prevents new quotes, confirmations, switches, and initial executions. It does not
prevent reconciliation of an already reserved uncertain operation. Reconciliation uses the stored
rail and provider/route snapshots and may therefore use archived catalogue records. A rail type must
not be removed from deployed code while an operation using it can still require reconciliation.

## Validation and Error Handling

Representative error behavior is:

| Condition | Status | Code |
| --- | --- | --- |
| Invalid fields or incompatible rail/destination | 400 | `INVALID_TRANSFER_ROUTE` |
| Duplicate provider code | 409 | `PROVIDER_CODE_CONFLICT` |
| Duplicate route code | 409 | `ROUTE_CODE_CONFLICT` |
| Stale optimistic-lock version | 409 | `STALE_PROVIDER` or `STALE_ROUTE` |
| Attempt to change a used binding | 409 | `ROUTING_BINDING_IMMUTABLE` |
| Provider still has child routes | 409 | `PROVIDER_HAS_ROUTES` |
| No eligible route survives filtering | 422 | `NO_ELIGIBLE_ROUTES` |
| Required rail is not installed | 503 | `TRANSFER_RAIL_UNAVAILABLE` |

Provider and route activation is rejected when the rail/destination combination is incompatible.
Execution repeats critical compatibility checks so a malformed database row cannot move funds.

## Migration and Compatibility

Implementation begins only after integrating the wallet-transfer branch and selecting the next
available Flyway migration number. The migration will:

1. Create `transfer_providers` and `transfer_route_outcomes`.
2. Evolve/rename `payout_routes` to `transfer_routes` without changing existing route UUIDs or
   codes.
3. Add provider, destination, corridor, limit, archive, and protection fields.
4. Backfill the three existing rows to providers using `BANK_NETWORK`, `REAL_TIME_NETWORK`, and
   `PARTNER_NETWORK`.
5. Attach the wallet-transfer implementation's internal route records to a system-protected FluxPay
   provider using `INTERNAL_LEDGER`.
6. Backfill terminal external attempts into `transfer_route_outcomes` idempotently.
7. Add quote ranking snapshot columns while keeping older quote rows readable.
8. Remove the obsolete route-type constraint after all rows have been mapped.
9. Preserve foreign keys from quotes and attempts to the renamed/evolved route table.

Runtime seed logic will seed only baseline demonstration data. It will no longer define the complete
set of possible business routes, and rerunning it will not overwrite administrator-created records.

## Component Boundaries

- `RailRegistry`: owns the immutable `RailType -> TransferRail` mapping and capability metadata.
- `TransferProviderService`: provider validation, CRUD, optimistic locking, and deletion/archive
  decisions.
- `TransferRouteService`: route validation, CRUD, usage checks, and catalogue queries.
- `RouteEligibilityService`: pure contextual filtering; it does not price or execute.
- `RoutePricingService`: pure route-driven quote economics.
- `RouteReliabilityService`: bulk outcome aggregation and the configured-prior formula.
- `RouteRecommender`: deterministic preference ranking and top-three selection.
- `RouteOutcomeRecorder`: idempotent terminal outcome projection used by internal and external flows.
- Rail implementations: execute one durable command; they do not own catalogue CRUD or ranking.

These boundaries keep administrator configuration, ranking policy, and money movement independently
testable.

## Testing Strategy

### Migration tests

- Existing route UUIDs/codes, quotes, attempts, and foreign keys survive.
- Existing rows receive the correct provider and rail mapping.
- Historical terminal attempts are backfilled exactly once.
- Internal routes from the wallet-transfer work map to the protected FluxPay provider.

### Provider and route tests

- Admin authorization and explicit persistent-role checks.
- Create, read, update, delete, and archive behavior.
- Code normalization and uniqueness.
- Optimistic locking.
- Rail/destination compatibility.
- Field immutability after first use.
- Provider-with-routes deletion conflict.
- System-protected archive behavior.

### Smart-routing tests

- Internal versus external eligibility.
- Country, currency, provider, active/archive, and payout-limit filtering.
- Invalid candidate exclusion and no-candidate error.
- Configured prior plus observed terminal outcomes.
- Processing/uncertain outcome exclusion.
- `CHEAPEST`, `FASTEST`, and `BALANCED` ordering.
- Deterministic ties.
- At most three quotes, including multiple routes from one provider.
- Route-driven pricing with no route-code conditionals.

### Execution and recovery tests

- Multiple providers and routes share one rail implementation.
- Execution receives the selected provider, route, and destination snapshots.
- Internal execution delegates to the wallet-transfer workflow.
- New work rejects inactive or archived catalogue records.
- Reconciliation can use archived records for an existing reservation.
- A missing rail fails before delivery.
- Outcome projection is transactional and idempotent.

### Frontend and acceptance tests

- Provider and route forms, validation, filters, edit, activate/deactivate, and archive confirmation.
- Stale-version conflicts refresh instead of overwriting.
- HDFC Bank and SBI can share `BANK_NETWORK`.
- HDFC Bank can own standard and express routes.
- Quote generation returns only the top three eligible results.
- Both an external transfer and the integrated internal-wallet flow complete through the shared
  catalogue.

## Acceptance Criteria

1. An administrator can perform provider and route CRUD from the dashboard.
2. One rail type can serve multiple providers, and one provider can serve multiple routes.
3. Used and system-protected records preserve history through archival.
4. Internal and external transfers select only context-compatible routes.
5. Runtime pricing and execution contain no business logic keyed to route-code literals.
6. Smart routing blends configured and observed reliability and returns no more than three quotes.
7. Existing historical quotes and attempts remain readable and correctly linked.
8. Idempotency and uncertain-delivery reconciliation remain intact.
9. The full backend and frontend verification suites pass after integration with the wallet-transfer
   work.
