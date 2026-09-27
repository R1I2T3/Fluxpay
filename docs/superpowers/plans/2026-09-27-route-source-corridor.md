# Route Source Corridor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mandatory `sourceCurrency` plus nullable `sourceCountry` to payout routes with schema reset.

**Architecture:** Edit `V003` baseline and seeds directly (no backfill). Extend `TransferRoute` entity, `TransferRoutingContext`, `RouteEligibilityService`, admin API, and admin UI. Source comes from `Payment.sourceCurrency` / wallet `from` currency; `sourceCountry` is `NULL` wildcard in phase 1.

**Tech Stack:** Java 17 + Spring Boot + JPA (Oracle SQL), TypeScript + Knockout (Oracle JET), JUnit 5 + AssertJ + Mockito, node:test.

**Spec:** `docs/superpowers/specs/2026-09-27-route-source-corridor-design.md`

## Global Constraints

- Schema reset only: edit `V003__routing_payments_and_quotes.sql` and seeds, no new ALTER migration, no backfill UPDATE.
- `source_currency VARCHAR2(3) NOT NULL`, `source_country VARCHAR2(2) NULL`.
- `sourceCurrency` mandatory ISO-4217 `[A-Z]{3}`; `sourceCountry` null or ISO-3166 `[A-Z]{2}`.
- Eligibility: exact `sourceCurrency` match; `sourceCountry` matches only when route sets it.
- `routeCode` immutable; provider/destination/source binding immutable after use (`409 ROUTING_BINDING_IMMUTABLE`).
- Every admin write keeps `ADMIN` role + `version` optimistic locking (`409 STALE_ROUTE`).

---

### Task 1: Schema reset + seeds

**Files:**
- Modify: `backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql:28-58`
- Modify: `backend/src/main/resources/db/migration/V603__m5_seed_data.sql:222-285`
- Modify: `scripts/seed-local.py:39-145,1085-1110`
- Modify: `backend/src/test/java/com/fluxpay/repository/MigrationContractTest.java:19-47`
- Modify: `backend/src/test/java/com/fluxpay/repository/SeedMigrationContractTest.java:61-77`

**Interfaces:**
- Consumes: nothing.
- Produces: columns `source_currency`, `source_country` available to JPA; seed rows carry explicit sources for later tasks.

- [ ] **Step 1: Write the failing contract assertions**

In `MigrationContractTest.transferCatalogueReplacesLegacyRoutes`, add:

```java
.contains("source_currency VARCHAR2(3) NOT NULL")
.contains("source_country VARCHAR2(2)")
.contains("chk_transfer_route_source_currency")
```

In `SeedMigrationContractTest.seedsTransferRoutesWithInsertOnlyMerges`, add:

```java
assertThat(sql).contains("source_currency").contains("'USD'");
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -pl . -Dtest=MigrationContractTest,SeedMigrationContractTest test` in `backend/`
Expected: FAIL — `source_currency` not found.

- [ ] **Step 3: Reset V003 baseline**

In `V003__routing_payments_and_quotes.sql`, inside `CREATE TABLE transfer_routes (` after `destination_country`:

```sql
source_country VARCHAR2(2),
source_currency VARCHAR2(3) NOT NULL,
```

Add checks alongside existing `chk_transfer_route_*`:

```sql
CONSTRAINT chk_transfer_route_source_currency CHECK (REGEXP_LIKE(source_currency, '^[A-Z]{3}$')),
CONSTRAINT chk_transfer_route_source_country CHECK (source_country IS NULL OR REGEXP_LIKE(source_country, '^[A-Z]{2}$'))
```

- [ ] **Step 4: Reset seeds and script**

`V603__m5_seed_data.sql`: every `MERGE INTO transfer_routes` column list becomes `(id, provider_id, route_code, route_name, destination_type, destination_country, source_country, source_currency, payout_currency, ...)`. Values: external `IN/INR` rows use `NULL, 'USD'`; `FLUXPAY_INTERNAL NULL/INR` uses `NULL, 'INR'`.

`scripts/seed-local.py`: extend `ROUTES` tuples with `source_country, source_currency` after `country, currency`:

```python
("BANK_STANDARD", "HDFC INR Standard", "BANK_ALPHA", "EXTERNAL_ACCOUNT", "IN", None, "USD", "INR", "5.0000", "0.500000", 240, "99.00", 0, 0),
```

Internal rows use `None, "INR"` for `INR`, `None, "USD"` for `USD`, `None, "EUR"` for `EUR`. Update the `MERGE INTO transfer_routes` column list and `VALUES` to include `:source_country, :source_currency`, and unpack `country, source_country, source_currency, currency` in the loop.

- [ ] **Step 5: Run contract tests**

Run: `mvn -Dtest=MigrationContractTest,SeedMigrationContractTest test` in `backend/`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql backend/src/main/resources/db/migration/V603__m5_seed_data.sql scripts/seed-local.py backend/src/test/java/com/fluxpay/repository/MigrationContractTest.java backend/src/test/java/com/fluxpay/repository/SeedMigrationContractTest.java
git commit -m "feat(routes): reset schema with source corridor columns and seeds"
```

### Task 2: Domain — TransferRoute + TransferRoutingContext

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/beans/TransferRoute.java:44-48,92-137,188-223,289-312,334-340,414-420`
- Modify: `backend/src/main/java/com/fluxpay/dto/TransferRoutingContext.java:13-52`
- Modify: `backend/src/test/java/com/fluxpay/service/TransferRouteTestFixtures.java:21-59`
- Test: `backend/src/test/java/com/fluxpay/service/RouteEligibilityServiceTest.java`, `backend/src/test/java/com/fluxpay/service/TransferRouteServiceTest.java` (updated, not new)

**Interfaces:**
- Consumes: Task 1 columns.
- Produces: `TransferRoute.create(id, provider, routeCode, name, destinationType, destinationCountry, sourceCountry, sourceCurrency, payoutCurrency, baseFee, fxSpreadPercentage, estimatedMinutes, configuredSuccessRate, min, max, active, systemProtected, now)`; `route.update(provider, name, destinationType, destinationCountry, sourceCountry, sourceCurrency, ...)`; `route.sourceCurrency(): String`, `route.sourceCountry(): String|null`; `new TransferRoutingContext(sourceCurrency, sourceCountry, destinationType, destinationCountry, payoutCurrency, gross, marketRate)`.

- [ ] **Step 1: Write failing domain test**

Add to `RouteEligibilityServiceTest`:

```java
@Test
void rejectsBlankSourceCurrency() {
  TransferProvider p = provider(RailType.BANK_NETWORK, true);
  org.assertj.core.api.Assertions.assertThatThrownBy(() ->
    TransferRoute.create(UUID.randomUUID(), p, "SRC_BAD", "bad",
      DestinationType.EXTERNAL_ACCOUNT, "IN", null, "US",
      new BigDecimal("5.0000"), new BigDecimal("0.5"), 60,
      new BigDecimal("99.00"), null, null, true, false, NOW))
    .isInstanceOf(IllegalArgumentException.class);
}
```

Note: this uses the NEW 18-arg `create` signature, so compilation fails before the fix.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -Dtest=RouteEligibilityServiceTest#rejectsBlankSourceCurrency test` in `backend/`
Expected: FAIL — compilation error, no such `create` overload.

- [ ] **Step 3: Implement entity + context**

`TransferRoute.java`: add

```java
@Column(name = "source_country", length = 2)
private String sourceCountry;

@Column(name = "source_currency", nullable = false, length = 3)
private String sourceCurrency;
```

Insert `String sourceCountry, String sourceCurrency` params after `destinationCountry` in `create()` and `update()`, wire:

```java
route.sourceCountry = normalizeSourceCountry(sourceCountry);
route.sourceCurrency = normalizeSourceCurrency(sourceCurrency);
```

Add:

```java
private static String normalizeSourceCurrency(String currency) {
  String normalized = requireText(currency, "sourceCurrency").toUpperCase(Locale.ROOT);
  if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("sourceCurrency must be ISO-4217");
  return normalized;
}
private static String normalizeSourceCountry(String country) {
  if (country == null || country.isBlank()) return null;
  String normalized = country.trim().toUpperCase(Locale.ROOT);
  if (!normalized.matches("[A-Z]{2}")) throw new IllegalArgumentException("sourceCountry must be ISO-3166 alpha-2");
  return normalized;
}
```

Add getters `sourceCurrency()`, `sourceCountry()`, `getSourceCurrency()`, `getSourceCountry()`. Update `seed()` to pass `null, "USD"`.

`TransferRoutingContext.java`: change record to

```java
public record TransferRoutingContext(
    String sourceCurrency,
    String sourceCountry,
    DestinationType destinationType,
    String destinationCountry,
    String payoutCurrency,
    BigDecimal gross,
    BigDecimal marketRate) {
```

Normalize source with same rules (mandatory currency, nullable country).

- [ ] **Step 4: Fix fixtures + run domain tests**

Update `TransferRouteTestFixtures.externalRoute` / `inactiveExternalRoute` and `deleteSystemProtectedRouteArchives` inline `create()` calls to pass `null, "USD"` before `"INR"`. Update all `TransferRoute.create` / `route.update` call sites in `RouteEligibilityServiceTest`, `TransferRouteServiceTest`, `TransferRouteAdminControllerTest`, `RouteCatalogServiceTest`, `RouteRecommenderTest` the same way, and `TransferRoutingContext` constructions in tests to pass `("USD", null, ...)` first.

Run: `mvn -Dtest=RouteEligibilityServiceTest,TransferRouteServiceTest test` in `backend/`
Expected: PASS (eligibility still old behavior until Task 3; new validation passes).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/beans/TransferRoute.java backend/src/main/java/com/fluxpay/dto/TransferRoutingContext.java backend/src/test/java/com/fluxpay/service/TransferRouteTestFixtures.java backend/src/test/java/com/fluxpay/service/RouteEligibilityServiceTest.java backend/src/test/java/com/fluxpay/service/TransferRouteServiceTest.java backend/src/test/java/com/fluxpay/controller/TransferRouteAdminControllerTest.java
git commit -m "feat(routes): add sourceCurrency and sourceCountry to domain"
```

### Task 3: Matching + service + callers

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/service/RouteEligibilityService.java:32-57`
- Modify: `backend/src/main/java/com/fluxpay/service/TransferRouteService.java:27-56,111-130,150-178`
- Modify: `backend/src/main/java/com/fluxpay/service/QuoteService.java:122-131`
- Modify: `backend/src/main/java/com/fluxpay/service/RouteCatalogService.java:59-66`
- Modify: `backend/src/main/java/com/fluxpay/service/WalletTransferRoutingService.java:160-167`
- Modify: `backend/src/main/java/com/fluxpay/dto/TransferRouteApi.java:19-94`
- Test: `backend/src/test/java/com/fluxpay/service/RouteEligibilityServiceTest.java`

**Interfaces:**
- Consumes: Task 2 constructors.
- Produces: eligibility enforces source; `CreateRoute`/`UpdateRoute` carry `sourceCountry, sourceCurrency`; callers supply payment/wallet source.

- [ ] **Step 1: Write failing eligibility tests**

Append to `RouteEligibilityServiceTest`:

```java
@Test
void excludesSourceCurrencyMismatch() {
  TransferProvider p = provider(RailType.BANK_NETWORK, true);
  TransferRoute usd = external(p, "USD_INR", null, "USD", "IN", "INR", rate("99.00"));
  assertThat(service.filter(List.of(usd), externalContext("AED", null, "IN", "INR"))).isEmpty();
  assertThat(service.filter(List.of(usd), externalContext("USD", null, "IN", "INR"))).containsExactly(usd);
}

@Test
void nullSourceCountryIsWildcardButPinnedCountryMustMatch() {
  TransferProvider p = provider(RailType.BANK_NETWORK, true);
  TransferRoute wildcard = external(p, "WILD", null, "USD", "IN", "INR", rate("99.00"));
  TransferRoute pinned = external(p, "PINNED", "US", "USD", "IN", "INR", rate("99.00"));
  assertThat(service.filter(List.of(wildcard, pinned), externalContext("USD", "US", "IN", "INR"))).containsExactly(wildcard, pinned);
  assertThat(service.filter(List.of(wildcard, pinned), externalContext("USD", "AE", "IN", "INR"))).containsExactly(wildcard);
}
```

Helpers change to `external(provider, code, sourceCountry, sourceCurrency, destCountry, payoutCurrency, rate)` and `externalContext(sourceCurrency, sourceCountry, destCountry, payoutCurrency)`.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -Dtest=RouteEligibilityServiceTest test` in `backend/`
Expected: FAIL — helpers/methods missing.

- [ ] **Step 3: Implement matching + service pass-through**

`RouteEligibilityService.eligible`: after payout check add:

```java
if (!route.getSourceCurrency().equals(context.sourceCurrency())) return false;
if (route.getSourceCountry() != null && !route.getSourceCountry().equals(context.sourceCountry())) return false;
```

`TransferRouteApi`: add `String sourceCountry, String sourceCurrency` to `RouteEntry` (after `destinationCountry`), `CreateRouteRequest`, `UpdateRouteRequest`; extend `toEntry()`.

`TransferRouteService`: add `String sourceCountry, String sourceCurrency` to `CreateRoute`/`UpdateRoute` after `destinationCountry`; pass to `TransferRoute.create` / `route.update`. Extend `bindingChanged` to also compare `sourceCurrency` and `sourceCountry`:

```java
|| !Objects.equals(command.sourceCurrency(), route.sourceCurrency())
|| !Objects.equals(command.sourceCountry(), route.sourceCountry())
```

Add `import java.util.Objects;` if missing.

Callers: `QuoteService.routingContext` becomes `new TransferRoutingContext(payment.sourceCurrency(), null, DestinationType.EXTERNAL_ACCOUNT, destination.country(), destination.currency(), payment.sourceAmount(), marketRate)`. Same pattern in `RouteCatalogService` with `payment.sourceCurrency()` and in `WalletTransferRoutingService.prepare` with `from`.

- [ ] **Step 4: Run service tests**

Run: `mvn -Dtest=RouteEligibilityServiceTest,TransferRouteServiceTest,RouteCatalogServiceTest,RouteRecommenderTest test` in `backend/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/service/RouteEligibilityService.java backend/src/main/java/com/fluxpay/service/TransferRouteService.java backend/src/main/java/com/fluxpay/service/QuoteService.java backend/src/main/java/com/fluxpay/service/RouteCatalogService.java backend/src/main/java/com/fluxpay/service/WalletTransferRoutingService.java backend/src/main/java/com/fluxpay/dto/TransferRouteApi.java backend/src/test/java/com/fluxpay/service/RouteEligibilityServiceTest.java
git commit -m "feat(routes): enforce source corridor in eligibility and services"
```

### Task 4: Admin API validation

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/controller/TransferRouteAdminController.java:94-146,230-253`
- Modify: `backend/src/test/java/com/fluxpay/controller/TransferRouteAdminControllerTest.java:66-80,112-130`

**Interfaces:**
- Consumes: Task 3 service records.
- Produces: `POST/PUT /api/admin/routes` accept `sourceCurrency` (required) + `sourceCountry` (optional), return them in `RouteEntry`.

- [ ] **Step 1: Write failing controller test**

Change `CREATE_BODY` to include `"sourceCountry":null,"sourceCurrency":"USD"` and add:

```java
@Test
void blankSourceCurrencyIsBadRequestWithoutServiceInvocation() throws Exception {
  when(authorizer.isAdmin(any())).thenReturn(true);
  mvc.perform(post("/api/admin/routes").header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
      .contentType(MediaType.APPLICATION_JSON)
      .content(CREATE_BODY.formatted(P_HDFC).replace("\"sourceCurrency\":\"USD\"", "\"sourceCurrency\":\"US\"")))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("INVALID_TRANSFER_ROUTE"));
  verify(service, never()).create(any());
}
```

Update `setUp()` route creation to pass `null, "USD"`.

- [ ] **Step 2: Run to verify failure**

Run: `mvn -Dtest=TransferRouteAdminControllerTest test` in `backend/`
Expected: FAIL — controller ignores/rejects new fields.

- [ ] **Step 3: Implement controller**

Add:

```java
private static String normalizeSourceCurrency(String currency) {
  if (currency == null || currency.isBlank()) throw invalid("sourceCurrency must not be blank");
  String normalized = currency.trim().toUpperCase(Locale.ROOT);
  if (!normalized.matches("[A-Z]{3}")) throw invalid("sourceCurrency must be ISO-4217");
  return normalized;
}
private static String normalizeSourceCountry(String country) {
  if (country == null || country.isBlank()) return null;
  String normalized = country.trim().toUpperCase(Locale.ROOT);
  if (!normalized.matches("[A-Z]{2}")) throw invalid("sourceCountry must be ISO-3166 alpha-2");
  return normalized;
}
```

Thread `normalizeSourceCountry(body.sourceCountry()), normalizeSourceCurrency(body.sourceCurrency())` into `service.create` / `service.update` after `destinationCountry`.

- [ ] **Step 4: Run controller tests**

Run: `mvn -Dtest=TransferRouteAdminControllerTest test` in `backend/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/TransferRouteAdminController.java backend/src/test/java/com/fluxpay/controller/TransferRouteAdminControllerTest.java
git commit -m "feat(routes): validate source corridor in admin API"
```

### Task 5: Admin UI

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/services/flux-api.ts:659-726`
- Modify: `frontend/fluxpay-ui/src/ts/services/routing-workspace.ts:80-95,387-475,531-582`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-routes.html:78-127,170-190,248-262,275-412`
- Modify: `frontend/fluxpay-ui/src/ts/services/route-analysis.ts:4-77,79-101`
- Modify: `frontend/fluxpay-ui/tests/admin-routing-console.test.cjs:18-28,97-116,232-325`

**Interfaces:**
- Consumes: Task 4 API shapes.
- Produces: admin can create/edit/filter/preview routes by source; preview/matrix enforce source.

- [ ] **Step 1: Write failing UI test**

In `admin-routing-console.test.cjs`, extend the `route` fixture with `sourceCountry: null, sourceCurrency: 'USD'`, and add:

```js
test('route payload requires a three-letter source currency', async () => {
  const {page} = routingWorkspace();
  await page.loadAll();
  page.newRoute();
  page.routeCountry('IN');
  page.routeCurrency('INR');
  page.routeSourceCurrency('US');
  page.routeFee('5'); page.routeSpread('0.5'); page.routeEta('60'); page.routeReliability('99');
  page.requestRouteSave();
  assert.match(page.routeError(), /source currency/i);
  page.dispose();
});
```

Extend `analysisRoute()` with `sourceCurrency: 'USD', sourceCountry: null` and add a case asserting `evaluateRouteEligibility` with `sourceCurrency: 'AED'` yields `'Source currency does not match.'`.

- [ ] **Step 2: Run to verify failure**

Run: `npm test` in `frontend/fluxpay-ui/`
Expected: FAIL — `routeSourceCurrency` undefined, source reason missing.

- [ ] **Step 3: Implement frontend**

`flux-api.ts`: add `sourceCountry: string | null; sourceCurrency: string;` after `destinationCountry` in `TransferRoute`, `CreateRouteRequest`, `UpdateRouteRequest`.

`routing-workspace.ts`: add `routeSourceCountry = ko.observable(''); routeSourceCurrency = ko.observable('');`; wire `newRoute()` clear, `editRoute()` populate; in `routePayload()` add:

```ts
const sourceCurrency = this.routeSourceCurrency().trim().toUpperCase();
if (!/^[A-Z]{3}$/.test(sourceCurrency)) invalid('Enter the three-letter ISO source currency.');
const sourceCountryRaw = this.routeSourceCountry().trim().toUpperCase();
if (sourceCountryRaw && !/^[A-Z]{2}$/.test(sourceCountryRaw)) invalid('Source country uses a two-letter ISO code.');
```

Return `sourceCountry: sourceCountryRaw || null, sourceCurrency`. Add both to `requestRouteSave()` diff labels/fields and to `filteredRoutes` search string.

`admin-routes.html`: add table `<th>Source</th>` + `<td data-bind="text:(sourceCountry||'—')+' / '+sourceCurrency">`; editor row inputs `Source currency (ISO)` required bound to `routeSourceCurrency` and `Source country (ISO)` optional bound to `routeSourceCountry`; extend review corridor line and preview form with source inputs.

`route-analysis.ts`: extend `EligibilityInput` with `sourceCurrency: string; sourceCountry?: string | null`; validate source formats; add reasons `'Source currency does not match.'` and `'Source country does not match.'` (latter only when route pins a country); include source in `buildCorridorMatrix` grouping key (`sourceCountry||GLOBAL / sourceCurrency -> destCountry / payoutCurrency` shown as `US/USD -> IN/INR` labels or extra columns).

- [ ] **Step 4: Run UI tests + typecheck**

Run: `npm test` and `npm run typecheck` in `frontend/fluxpay-ui/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/services/flux-api.ts frontend/fluxpay-ui/src/ts/services/routing-workspace.ts frontend/fluxpay-ui/src/ts/views/admin-routes.html frontend/fluxpay-ui/src/ts/services/route-analysis.ts frontend/fluxpay-ui/tests/admin-routing-console.test.cjs
git commit -m "feat(routes): admin UI sets source corridor"
```

### Task 6: Docs + full verification

**Files:**
- Modify: `docs/api-catalog.md:213-215,228-231`
- Test: full `backend` suite + `frontend` suite (no new files)

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: documented source corridor; green build.

- [ ] **Step 1: Update API docs**

Change endpoint 32 sample body to include `"sourceCountry":null,"sourceCurrency":"USD"` and endpoint 31 sample output likewise. Update eligibility paragraph: `Eligibility keeps routes whose provider and route are active and unarchived, whose source currency (and pinned source country) plus destination corridor match, and whose rail is installed and compatible.`

- [ ] **Step 2: Run backend suite**

Run: `mvn test` in `backend/`
Expected: PASS.

- [ ] **Step 3: Run frontend suite**

Run: `npm test` and `npm run typecheck` in `frontend/fluxpay-ui/`
Expected: PASS.

- [ ] **Step 4: Manual smoke (fresh DB)**

Rebuild DB from reset baseline, run `scripts/seed-local.py`, log in as admin, create `USD -> IN/INR` and `AED -> IN/INR`, quote a `USD` payment and an `AED` payment, confirm only the matching source route ranks.

- [ ] **Step 5: Commit**

```bash
git add docs/api-catalog.md
git commit -m "docs(routes): document source corridor"
```
