# Route Source Corridor Design — 2026-09-27

## Goal
Allow admin to set source + destination on payout routes. Today routes match only on `destinationType + destinationCountry + payoutCurrency`. After this change a route is a full corridor: `sourceCurrency (+ future sourceCountry)` -> `destinationCountry + payoutCurrency`.

Approved: Approach A (mandatory `sourceCurrency`, nullable `sourceCountry`), with schema reset (edit baseline, no backfill migration).

## Decisions (user-approved)
- Scope: source = country + currency long-term, currency now / country later.
- `sourceCurrency`: mandatory (`[A-Z]{3}` ISO-4217), exact match in eligibility.
- `sourceCountry`: nullable (`[A-Z]{2}` ISO-3166), `NULL` = wildcard in phase 1; exact match only when route sets it.
- No backfill: reset schema — edit `V003` baseline + `V603` seeds directly instead of new `ALTER TABLE ... DEFAULT` migration.
- No sender-country plumbing now: payments already carry `sourceCurrency` (`payments.currency`); users/wallets store no country, so `sourceCountry` stays unused until sender country is tracked.

## Architecture
```
Admin UI (admin-routes.html + routing-workspace.ts + flux-api.ts)
  -> POST/PUT /api/admin/routes (TransferRouteAdminController)
  -> TransferRouteService.CreateRoute/UpdateRoute
  -> TransferRoute entity (transfer_routes)
  -> RouteEligibilityService.filter + TransferRoutingContext
  <- QuoteService / RouteCatalogService / WalletTransferRoutingService build context from payment/wallet
```

## Data — schema reset
Edit `backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql`:
- `transfer_routes`: add `source_currency VARCHAR2(3) NOT NULL`, `source_country VARCHAR2(2) NULL`.
- Checks: `source_currency` matches `^[A-Z]{3}$` via app-level + `CHECK (REGEXP_LIKE(source_currency,'^[A-Z]{3}$'))`; `source_country` either `NULL` or `^[A-Z]{2}$`.
- No `DEFAULT`, no `UPDATE ... WHERE NULL` backfill — fresh DB rebuild.

Edit `backend/src/main/resources/db/migration/V603__m5_seed_data.sql`:
- All `MERGE INTO transfer_routes` inserts include `source_currency, source_country`.
- Values: external `IN/INR` seed routes set `source_currency='USD', source_country=NULL`; internal wallet seed routes set `source_currency=payout_currency` (e.g. `INR->INR` row; add `USD->USD`, `EUR->EUR` rows to match api-catalog wallet list). Admin adjusts sources after reset.

## Components
1. `beans/TransferRoute.java`: fields `sourceCurrency`, `sourceCountry`; `create(...)` / `update(...)` signatures extended; `normalizeCurrency(sourceCurrency)` mandatory, `normalizeSourceCountry(sourceCountry)` nullable-wildcard; getters `sourceCurrency()`, `sourceCountry()`, `getSourceCurrency()`, `getSourceCountry()`; update `seed()` fixture.
2. `dto/TransferRouteApi.java`: `RouteEntry`, `CreateRouteRequest`, `UpdateRouteRequest` add `sourceCurrency`, `sourceCountry`; `toEntry()` maps them.
3. `dto/TransferRoutingContext.java`: add `sourceCurrency`, `sourceCountry` (nullable); normalize same rules; update javadoc (eligibility matches source + destination).
4. `service/TransferRouteService.java`: `CreateRoute`/`UpdateRoute` records add both fields; pass through to entity.
5. `controller/TransferRouteAdminController.java`: parse `normalizeCurrency(body.sourceCurrency())` mandatory 400 `INVALID_TRANSFER_ROUTE` on blank/bad; `normalizeSourceCountry()` nullable; include in create/update calls; update class javadoc.
6. `service/RouteEligibilityService.java`: after destination checks, require `route.getSourceCurrency().equals(context.sourceCurrency())`; if `route.getSourceCountry()!=null` require equals `context.sourceCountry()`.
7. Callers build context with source:
   - `service/QuoteService.java:122-131 routingContext()`: `new TransferRoutingContext(from=p.sourceCurrency(), sourceCountry=null, destType, destCountry, payoutCurrency, gross, rate)`.
   - `service/RouteCatalogService.java:59-66`: same from `payment.sourceCurrency()`.
   - `service/WalletTransferRoutingService.java:160-167 prepare()`: `from` as source, `sourceCountry=null`.
8. Frontend `fluxpay-ui/src/ts/services/flux-api.ts:659-726`: `TransferRoute`, `CreateRouteRequest`, `UpdateRouteRequest` add `sourceCurrency: string`, `sourceCountry: string|null`.
9. Frontend `services/routing-workspace.ts`: observables `routeSourceCurrency`, `routeSourceCountry`; `newRoute()` clears, `editRoute()` populates; `routePayload()` validates `/^[A-Z]{3}$/` mandatory + `/^[A-Z]{2}$/` optional; `requestRouteSave()` diff labels `Source currency/country`; filters/search include source.
10. Frontend `views/admin-routes.html`: table columns `Source`, editor inputs `Source currency (ISO) required` + `Source country (ISO) optional`, catalogue/compare/matrix/preview show source; review line includes `sourceCurrency/sourceCountry`.
11. Docs `docs/api-catalog.md:213-215,228-231`: sample bodies + eligibility paragraph mention source matching.

## Data flow
- Admin creates `USD -> IN/INR`: UI validates -> `POST /api/admin/routes {sourceCurrency:USD, sourceCountry:null, destinationCountry:IN, payoutCurrency:INR...}` -> controller normalizes -> service persists.
- Quote for payment `USD -> INR/IN`: `QuoteService` builds `TransferRoutingContext(USD,null,EXTERNAL,IN,INR,...)` -> `SmartRoutingService` -> `RouteEligibilityService` keeps only `sourceCurrency=USD` routes -> ranking/pricing unchanged.

## Error handling
- Blank/bad `sourceCurrency` -> `400 INVALID_TRANSFER_ROUTE` (controller + entity `IllegalArgumentException` mapped via service).
- Bad `sourceCountry` when non-empty -> same `400`.
- Source mismatch at quote time is not an error — route is filtered out; empty candidate set yields existing no-route behavior.
- Optimistic locking unchanged (`version`, `409 STALE_ROUTE`); `routeCode` still immutable; provider/destination binding immutability extended to include source (update `bindingChanged` to compare source fields).

## Testing
- Update `MigrationContractTest`, `SeedMigrationContractTest` (no `payout_routes`, new columns exist, seeds include source).
- New/updated: entity normalize tests (bad source 400), service create/update pass-through, `RouteEligibilityService` (match/mismatch source, null sourceCountry wildcard, set sourceCountry exact), controller validation, `QuoteService` context carries source, frontend payload validation.
- Manual: rebuild DB from reset baseline, seed, create `USD->IN/INR` + `AED->IN/INR` in admin UI, quote each source, verify only matching route ranks.

## Out of scope
- Sender-country tracking (users/wallets/payments), mandatory `sourceCountry`, FX changes, pricing/ranking changes, backfill migration.
