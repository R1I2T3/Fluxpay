# Admin-Managed Transfer Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace route-code-bound payout providers with admin-managed transfer providers and routes backed by trusted code-shipped rails, then use the catalogue for external quotes and internal wallet transfers with learned top-three smart routing.

**Architecture:** Persist providers, routes, and terminal route outcomes in Oracle; resolve execution as `route -> provider -> RailType -> TransferRail`. Keep admin CRUD, eligibility, pricing, reliability, ranking, and execution in separate services, and use one `SmartRoutingService` from both external quote generation and the wallet-transfer workflow.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Data JPA, Oracle/Flyway 10.22, JUnit 5/Mockito/AssertJ/MockMvc, Oracle JET 16.1, Knockout, TypeScript 5.3.2, Node test runner.

**Spec:** `docs/superpowers/specs/2026-09-18-admin-transfer-routing-design.md`

## Global Constraints

- Integrate the wallet-to-wallet work described by `docs/superpowers/plans/2026-09-17-plan3-money-ledger.md` before Task 9. Do not create a second ledger-transfer implementation.
- Use a clean local schema reset. Do not add legacy data migration, dual reads, route-code mapping, or history backfill.
- Code-shipped rail types are exactly `INTERNAL_LEDGER`, `BANK_NETWORK`, `REAL_TIME_NETWORK`, and `PARTNER_NETWORK`.
- Administrators configure providers and routes, never executable code, endpoints, scripts, credentials, or secrets.
- One provider selects one rail type and owns many routes; many providers may select the same rail type.
- Provider and route codes are uppercase, unique, and immutable. Rail/provider bindings become immutable after first use.
- Used and system-protected records archive; only unused, non-system records may be physically deleted.
- External quote generation persists at most three ranked quotes. Multiple winners may belong to the same provider.
- Wallet-to-wallet transfer remains single-call: rank internal candidates with `BALANCED`, execute the winner, and return its routing metadata.
- Configured success rate is a 20-attempt prior. Only terminal `COMPLETED` and `FAILED` outcomes affect learned reliability.
- Preserve reserve/deliver/finalize idempotency and allow archived catalogue records during reconciliation of an already reserved operation.
- Use `HALF_EVEN` for existing payment quote calculations; never use floating-point money or scores.
- Run `spotless:apply` before Java commits. Do not edit generated frontend `src/js` files.

## File Structure

- `beans/TransferProvider.java`, `TransferRoute.java`, `TransferRouteOutcome.java`: persistent catalogue and terminal projection.
- `common/contracts/TransferRail.java` and `service/RailRegistry.java`: trusted execution boundary and finite registry.
- `service/TransferProviderService.java` and `TransferRouteService.java`: CRUD, validation, locking, and archive rules.
- `service/RouteEligibilityService.java`, `RouteReliabilityService.java`, `RoutePricingService.java`, `RouteRecommender.java`, `SmartRoutingService.java`: the routing pipeline.
- `controller/TransferProviderAdminController.java`, `TransferRouteAdminController.java`, `TransferRailAdminController.java`: admin API.
- `frontend/fluxpay-ui/src/ts/services/routing-workspace.ts`: isolated admin UI state.
- `scripts/seed-local.py`: insert-only demonstration providers and routes.

---

### Task 1: Fresh Transfer Catalogue Schema and Persistent Model

**Files:**
- Modify: `backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql`
- Create: `backend/src/main/java/com/fluxpay/beans/TransferProvider.java`
- Rename/Modify: `backend/src/main/java/com/fluxpay/beans/PayoutRoute.java` -> `backend/src/main/java/com/fluxpay/beans/TransferRoute.java`
- Create: `backend/src/main/java/com/fluxpay/beans/TransferRouteOutcome.java`
- Create: `backend/src/main/java/com/fluxpay/domain/RailType.java`
- Create: `backend/src/main/java/com/fluxpay/domain/DestinationType.java`
- Create: `backend/src/main/java/com/fluxpay/domain/RouteOutcome.java`
- Create: `backend/src/main/java/com/fluxpay/repository/TransferProviderRepository.java`
- Rename/Modify: `backend/src/main/java/com/fluxpay/repository/PayoutRouteRepository.java` -> `backend/src/main/java/com/fluxpay/repository/TransferRouteRepository.java`
- Create: `backend/src/main/java/com/fluxpay/repository/TransferRouteOutcomeRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/PayoutAttempt.java`
- Modify: every current Java reference returned by `rg -l "PayoutRoute|PayoutRouteRepository" backend/src/main backend/src/test`
- Test: `backend/src/test/java/com/fluxpay/repository/MigrationContractTest.java`
- Test: `backend/src/test/java/com/fluxpay/repository/FreshBaselineOracleTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/beans/TransferCatalogueTest.java`

**Interfaces:**
- Consumes: the existing V003 payments/quotes/attempts baseline.
- Produces: provider, route, terminal outcome entities/repositories and the three routing enums.

- [ ] **Step 1: Write failing fresh-schema contracts**

```java
@Test
void transferCatalogueReplacesLegacyRoutes() throws IOException {
  String sql = resource("V003__routing_payments_and_quotes.sql");
  assertThat(sql)
      .contains("CREATE TABLE transfer_providers (")
      .contains("CREATE TABLE transfer_routes (")
      .contains("provider_id RAW(16) NOT NULL REFERENCES transfer_providers(id)")
      .contains("CREATE TABLE transfer_route_outcomes (")
      .contains("transfer_route_id RAW(16) NOT NULL REFERENCES transfer_routes(id)")
      .doesNotContain("CREATE TABLE payout_routes (")
      .doesNotContain("chk_payout_route_type");
}
```

Update `FreshBaselineOracleTest` to expect `TRANSFER_PROVIDERS`, `TRANSFER_ROUTES`, and `TRANSFER_ROUTE_OUTCOMES` and not `PAYOUT_ROUTES`.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=MigrationContractTest,FreshBaselineOracleTest' test
```

Expected: the migration contract fails on the old route schema; Oracle remains guarded when its environment is absent.

- [ ] **Step 3: Write entity invariant tests**

```java
@Test
void codesNormalizeAndUsedIdentityCanBeArchived() {
  Instant now = Instant.parse("2026-09-18T00:00:00Z");
  TransferProvider provider = TransferProvider.create(
      UUID.randomUUID(), "hdfc_bank", "HDFC Bank",
      RailType.BANK_NETWORK, true, false, now);
  TransferRoute route = TransferRoute.create(
      UUID.randomUUID(), provider, "hdfc_inr_standard", "HDFC INR Standard",
      DestinationType.EXTERNAL_ACCOUNT, "in", "inr",
      new BigDecimal("5.0000"), new BigDecimal("0.500000"), 60,
      new BigDecimal("99.00"), new BigDecimal("1.0000"),
      new BigDecimal("500000.0000"), true, false, now);

  assertThat(provider.code()).isEqualTo("HDFC_BANK");
  assertThat(route.code()).isEqualTo("HDFC_INR_STANDARD");
  route.archive(now.plusSeconds(1));
  assertThat(route.active()).isFalse();
  assertThat(route.archivedAt()).isEqualTo(now.plusSeconds(1));
}
```

Add negative cases for malformed codes, external routes without country, invalid ETA/rate/spread, and reversed limits.

- [ ] **Step 4: Implement the final V003 tables**

Create `transfer_providers`, `transfer_routes`, and `transfer_route_outcomes` with the exact fields and checks from the spec. Point `payment_quotes` at `transfer_routes(route_code)` during this task; Task 7 replaces that with final id/snapshot columns. Rename `payout_route_id` to `transfer_route_id` in `payout_attempts`.

- [ ] **Step 5: Implement entities/repositories and mechanically rename route types**

Map `TransferRoute.provider` with required lazy `@ManyToOne`. Repository signatures:

```java
Optional<TransferProvider> findByProviderCode(String providerCode);
List<TransferProvider> findAllByOrderByProviderCodeAsc();

Optional<TransferRoute> findByRouteCode(String routeCode);
List<TransferRoute> findAllByOrderByRouteCodeAsc();
List<TransferRoute> findByProviderIdOrderByRouteCodeAsc(UUID providerId);
boolean existsByProviderId(UUID providerId);
```

Update every result of:

```powershell
rg -l "PayoutRoute|PayoutRouteRepository" backend/src/main backend/src/test
```

Keep behavior unchanged while renaming. `PayoutAttempt.routeId()` remains the Java accessor.

- [ ] **Step 6: Format, verify, commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=TransferCatalogueTest,MigrationContractTest,RouteCatalogServiceTest,QuoteServiceTest,PayoutAttemptTest' test
git add backend/src/main backend/src/test backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql
git commit -m "refactor(routing): add transfer provider and route model"
```

### Task 2: Trusted Transfer Rail Registry

**Files:**
- Create: `backend/src/main/java/com/fluxpay/common/contracts/TransferRail.java`
- Create: `backend/src/main/java/com/fluxpay/domain/TransferDestination.java`
- Create: `backend/src/main/java/com/fluxpay/domain/InternalWalletDestination.java`
- Create: `backend/src/main/java/com/fluxpay/domain/ExternalAccountDestination.java`
- Create: `backend/src/main/java/com/fluxpay/dto/TransferProviderSnapshot.java`
- Create: `backend/src/main/java/com/fluxpay/dto/TransferRouteSnapshot.java`
- Create: `backend/src/main/java/com/fluxpay/dto/TransferRailCommand.java`
- Create: `backend/src/main/java/com/fluxpay/dto/TransferRailResult.java`
- Create: `backend/src/main/java/com/fluxpay/service/RailRegistry.java`
- Create: `backend/src/main/java/com/fluxpay/development/SimulatedBankNetworkRail.java`
- Create: `backend/src/main/java/com/fluxpay/development/SimulatedRealTimeNetworkRail.java`
- Create: `backend/src/main/java/com/fluxpay/development/SimulatedPartnerNetworkRail.java`
- Create Test: `backend/src/test/java/com/fluxpay/service/RailRegistryTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/development/TransferRailSimulationTest.java`

**Interfaces:**
- Consumes: Task 1 enums.
- Produces: `TransferRail.type()`, `supportedDestinations()`, `execute(command)`, and `RailRegistry.requireCompatible(type,destination)`.

- [ ] **Step 1: Write failing registry tests**

```java
@Test
void duplicateTypesFailAndCapabilitiesAreEnforced() {
  TransferRail bank = fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
  assertThat(new RailRegistry(List.of(bank)).require(RailType.BANK_NETWORK)).isSameAs(bank);
  assertThatThrownBy(() -> new RailRegistry(List.of(bank, bank)))
      .isInstanceOf(IllegalStateException.class).hasMessageContaining("BANK_NETWORK");
  assertThatThrownBy(() ->
      new RailRegistry(List.of(bank))
          .requireCompatible(RailType.BANK_NETWORK, DestinationType.INTERNAL_WALLET))
      .isInstanceOfSatisfying(
          BusinessException.class,
          error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
}
```

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=RailRegistryTest,TransferRailSimulationTest' test
```

- [ ] **Step 3: Implement the rail boundary**

```java
public interface TransferRail {
  RailType type();
  Set<DestinationType> supportedDestinations();
  TransferRailResult execute(TransferRailCommand command);
}

public record TransferRailCommand(
    UUID transferId, UUID attemptId, UUID senderUserId,
    BigDecimal sourceAmount, String sourceCurrency,
    BigDecimal recipientAmount, String targetCurrency,
    TransferProviderSnapshot provider, TransferRouteSnapshot route,
    TransferDestination destination, int attemptNumber,
    BigDecimal customerFee, BigDecimal offeredRate, String idempotencyKey) {}
```

Use a sealed `TransferDestination` implemented by `InternalWalletDestination(UUID userId, UUID walletId, String currency)` and `ExternalAccountDestination(String accountReference, String bankName, String country, String currency)`. `TransferRailResult` retains `COMPLETED`, `FAILED`, and `UNCERTAIN` plus provider reference/error/fee validation. Add validated static factories `completed(reference, fee)`, `failed(code, message, fee)`, and `uncertain(code, message, fee)` so every rail constructs legal results consistently.

- [ ] **Step 4: Implement registry and simulation rails**

Use an unmodifiable `EnumMap`; duplicate types fail at construction. Missing types return `503 TRANSFER_RAIL_UNAVAILABLE`. Bank simulation uses `BANK_NETWORK[:count]` failure injection; real-time and partner simulations succeed deterministically. Remove the partner hardcoded amount ceiling because route limits own it. Keep all three behind `fluxpay.development.simulated-payouts-enabled=true`.

- [ ] **Step 5: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=RailRegistryTest,TransferRailSimulationTest,DevelopmentDefaultsTest' test
git add backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay
git commit -m "feat(routing): add trusted transfer rail registry"
```

### Task 3: Provider and Route Lifecycle Services

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/RoutingUsageService.java`
- Create: `backend/src/main/java/com/fluxpay/service/TransferProviderService.java`
- Create: `backend/src/main/java/com/fluxpay/service/TransferRouteService.java`
- Create: `backend/src/main/java/com/fluxpay/dto/DeletionResult.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/PaymentQuoteRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/PayoutAttemptRepository.java`
- Test: `backend/src/test/java/com/fluxpay/service/TransferProviderServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/TransferRouteServiceTest.java`

**Interfaces:**
- Consumes: Task 1 repositories and Task 2 capabilities.
- Produces: transactional CRUD, `DeletionResult(DELETED|ARCHIVED,id)`, and post-use binding protection.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void usedProviderArchivesAndCannotChangeRail() {
  when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
  when(usage.providerUsed(PROVIDER_ID)).thenReturn(true);

  assertThatThrownBy(() ->
      service.update(PROVIDER_ID,
          new UpdateProvider("HDFC Bank", RailType.REAL_TIME_NETWORK, true, provider.version())))
      .isInstanceOfSatisfying(
          BusinessException.class,
          error -> assertThat(error.code()).isEqualTo("ROUTING_BINDING_IMMUTABLE"));

  assertThat(service.delete(PROVIDER_ID, provider.version()).disposition())
      .isEqualTo(DeletionResult.Disposition.ARCHIVED);
}
```

Route tests cover binding changes before/after use, commercial edits after use, invalid corridor/limits, incompatible rail, inactive provider activation, hard delete, archive, and stale version.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=TransferProviderServiceTest,TransferRouteServiceTest' test
```

- [ ] **Step 3: Add usage queries and exact command records**

Add `existsByRoute(String routeCode)` to `PaymentQuoteRepository` and
`existsByRouteId(UUID)` to the attempt and outcome repositories. Because route codes are immutable,
`RoutingUsageService` can use the code until Task 7 replaces the quote FK with `route_id`. Define
`CreateProvider`/`UpdateProvider` and `CreateRoute`/`UpdateRoute` records in their services. Route
create fields are provider id, identity, destination/corridor, fee/spread/ETA/reliability, optional
limits, and active. Route update omits code and includes version.

- [ ] **Step 4: Implement CRUD/archive rules**

Use `Clock` for timestamps. Require exact version before mutation. Provider deletion conflicts with `PROVIDER_HAS_ROUTES` while nonarchived children exist. Unused non-system records call repository `delete`; used or system records call `archive`. Reject incompatible provider rail/destination on create, update, and activation.

- [ ] **Step 5: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=TransferProviderServiceTest,TransferRouteServiceTest' test
git add backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay/service
git commit -m "feat(routing): add provider and route lifecycle services"
```

### Task 4: Admin CRUD and Rail Metadata HTTP API

**Files:**
- Create: `backend/src/main/java/com/fluxpay/dto/TransferProviderApi.java`
- Create: `backend/src/main/java/com/fluxpay/dto/TransferRouteApi.java`
- Create: `backend/src/main/java/com/fluxpay/controller/TransferProviderAdminController.java`
- Create: `backend/src/main/java/com/fluxpay/controller/TransferRouteAdminController.java`
- Create: `backend/src/main/java/com/fluxpay/controller/TransferRailAdminController.java`
- Delete after replacement: `backend/src/main/java/com/fluxpay/controller/RouteAdminController.java`
- Modify: `backend/src/main/java/com/fluxpay/web/advice/PayoutApiExceptionHandler.java`
- Replace Test: `backend/src/test/java/com/fluxpay/controller/RouteAdminControllerContractTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/controller/TransferProviderAdminControllerTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/controller/TransferRouteAdminControllerTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/controller/TransferRailAdminControllerTest.java`

**Interfaces:**
- Consumes: Task 3 services and current admin authorization.
- Produces: `/api/admin/providers`, `/api/admin/routes`, and `/api/admin/rail-types`.

- [ ] **Step 1: Write failing MockMvc contracts**

```java
@Test
void adminCreatesProvider() throws Exception {
  when(authorizer.isAdmin(any())).thenReturn(true);
  when(service.create(any())).thenReturn(provider);
  mvc.perform(post("/api/admin/providers")
          .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
          .contentType(MediaType.APPLICATION_JSON)
          .content("""
              {"providerCode":"HDFC_BANK","providerName":"HDFC Bank",
               "railType":"BANK_NETWORK","active":true}
              """))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.data.providerCode").value("HDFC_BANK"));
}
```

Cover list/create/update/delete, deletion disposition, stale version, validation, missing token, and customer forbidden with no service invocation.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=TransferProviderAdminControllerTest,TransferRouteAdminControllerTest,TransferRailAdminControllerTest' test
```

- [ ] **Step 3: Implement records/controllers**

Validate code regex `[A-Za-z][A-Za-z0-9_]{2,49}`, ISO country/currency shape, nonnegative fee/spread, positive ETA, 0-100 reliability, and version. Use both `@PreAuthorize("hasRole('ADMIN')")` and the persistent `authorizer.isAdmin(currentUser)` check. Return 201 for create and 200 otherwise, always in `ApiResponse`.

- [ ] **Step 4: Register advice and verify**

Add all three controllers to `PayoutApiExceptionHandler.assignableTypes`.

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=TransferProviderAdminControllerTest,TransferRouteAdminControllerTest,TransferRailAdminControllerTest,AuthorizationContractTest' test
git add backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay/controller
git commit -m "feat(routing): expose admin provider and route CRUD"
```

### Task 5: Eligibility, Learned Reliability, Outcomes, and Route-Only Pricing

**Files:**
- Create: `backend/src/main/java/com/fluxpay/dto/TransferRoutingContext.java`
- Create: `backend/src/main/java/com/fluxpay/service/RouteEligibilityService.java`
- Create: `backend/src/main/java/com/fluxpay/service/RouteReliabilityService.java`
- Create: `backend/src/main/java/com/fluxpay/service/RouteOutcomeRecorder.java`
- Modify: `backend/src/main/java/com/fluxpay/repository/TransferRouteOutcomeRepository.java`
- Modify: `backend/src/main/java/com/fluxpay/domain/QuotePricingPolicy.java`
- Modify: `backend/src/main/java/com/fluxpay/service/RoutePricingService.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/RouteQuote.java`
- Replace: `backend/src/main/java/com/fluxpay/service/RouteMetrics.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/TransferRouteApi.java`
- Modify: `backend/src/main/java/com/fluxpay/controller/TransferRouteAdminController.java`
- Test: `backend/src/test/java/com/fluxpay/service/RouteEligibilityServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/RouteReliabilityServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/RouteOutcomeRecorderTest.java`
- Modify Test: `backend/src/test/java/com/fluxpay/service/QuoteServiceTest.java`

**Interfaces:**
- Consumes: active catalogue, rail capabilities, market rate, and terminal outcomes.
- Produces: eligible routes, bulk effective reliability, priced candidates, and idempotent terminal recording.

- [ ] **Step 1: Write failing eligibility/reliability/pricing tests**

```java
@Test
void observationsBlendWithTwentyAttemptPrior() {
  // 80% * 20 virtual attempts + 9 completed + 1 failed.
  assertThat(service.effective(new BigDecimal("80.00"), 9, 1))
      .isEqualByComparingTo("83.333333");
}

@Test
void routeCodeNeverChangesConfiguredFee() {
  RouteQuote quote = pricing.price(
      new BigDecimal("100"), new BigDecimal("80"),
      List.of(route("ANY_DYNAMIC_CODE", "5", "0", "7000", "9000")),
      Map.of(ROUTE_ID, new BigDecimal("99"))).get(0);
  assertThat(quote.feeAmount()).isEqualByComparingTo("5.0000");
}
```

Eligibility tests cover destination type, country/currency, provider/route active/archive state, installed compatible rail, and internal country wildcard. Pricing tests prove invalid/out-of-limit candidates are skipped without failing valid candidates.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=RouteEligibilityServiceTest,RouteReliabilityServiceTest,RouteOutcomeRecorderTest,QuoteServiceTest' test
```

- [ ] **Step 3: Implement formula and bulk query**

Use one grouped repository query for all candidate route ids. Formula:

```java
BigDecimal prior = configuredPercent.movePointLeft(2).multiply(new BigDecimal("20"));
BigDecimal probability = prior.add(BigDecimal.valueOf(completed))
    .divide(new BigDecimal("20").add(BigDecimal.valueOf(completed + failed)), 8, HALF_EVEN);
return probability.movePointRight(2).setScale(6, HALF_EVEN);
```

`RouteOutcomeRecorder.record(routeId,executionReference,outcome)` returns the existing identical row on replay and rejects a conflicting duplicate reference.

- [ ] **Step 4: Remove code-specific pricing**

Delete `QuotePricingPolicy.customerFee(String,BigDecimal)`. Price with `route.baseFee()`. Apply min/max to computed recipient amount. Skip per-route invalid economics; throw `422 NO_ELIGIBLE_ROUTES` only when the complete pipeline has no candidate.

Update the admin route-list mapping to call `RouteReliabilityService.effectiveFor(routes)` once and
include both configured and effective reliability plus completed/failed counts in every
`TransferRouteApi.RouteEntry`. Do not restore per-route count queries.

- [ ] **Step 5: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=RouteEligibilityServiceTest,RouteReliabilityServiceTest,RouteOutcomeRecorderTest,QuoteServiceTest' test
git add backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay/service
git commit -m "feat(routing): add eligibility and learned reliability"
```

### Task 6: Deterministic Top-Three Ranking

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/dto/RouteQuote.java`
- Create: `backend/src/main/java/com/fluxpay/dto/RankedRouteQuote.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/RouteRecommendation.java`
- Modify: `backend/src/main/java/com/fluxpay/service/RouteRecommender.java`
- Replace Test: `backend/src/test/java/com/fluxpay/service/RouteRecommenderTest.java`

**Interfaces:**
- Consumes: priced candidates with effective reliability.
- Produces: `RouteRecommendation` with one to three positioned quotes and a detailed reason.

- [ ] **Step 1: Write failing top-three and comparator tests**

```java
@Test
void sameProviderMayOwnAllThreeWinners() {
  List<RouteQuote> candidates = List.of(
      quote("HDFC_A", HDFC_ID, "100", 30, "99"),
      quote("HDFC_B", HDFC_ID, "99", 20, "98"),
      quote("HDFC_C", HDFC_ID, "98", 10, "97"),
      quote("SBI_A", SBI_ID, "97", 5, "96"));
  RouteRecommendation result = recommender.recommend(RoutePreference.CHEAPEST, candidates);
  assertThat(result.quotes()).extracting(q -> q.quote().route().code())
      .containsExactly("HDFC_A", "HDFC_B", "HDFC_C");
}
```

Add exact tests for CHEAPEST, FASTEST, BALANCED 45/30/25 normalization, one/two candidates, deterministic ties, positions 1-3, and reason fields.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=RouteRecommenderTest' test
```

- [ ] **Step 3: Implement ranked records and cap**

```java
public record RankedRouteQuote(RouteQuote quote, BigDecimal score, int position) {}

public record RouteRecommendation(
    RankedRouteQuote recommended, List<RankedRouteQuote> quotes, String reason) {
  public RouteRecommendation {
    quotes = List.copyOf(quotes);
    if (quotes.isEmpty() || quotes.size() > 3 || !quotes.get(0).equals(recommended)) {
      throw new IllegalArgumentException("recommendation requires one to three ranked quotes");
    }
  }
}
```

Sort the entire eligible set, take three, then assign positions. CHEAPEST ties: reliability desc, ETA asc, code. FASTEST ties: recipient desc, reliability desc, code. BALANCED ties: code.

- [ ] **Step 4: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=RouteRecommenderTest' test
git add backend/src/main/java/com/fluxpay/dto backend/src/main/java/com/fluxpay/service/RouteRecommender.java backend/src/test/java/com/fluxpay/service/RouteRecommenderTest.java
git commit -m "feat(routing): rank and cap smart routes"
```

### Task 7: Smart Routing in Quotes and Recommendations

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/SmartRoutingService.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/PaymentQuote.java`
- Modify: `backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql`
- Modify: `backend/src/main/java/com/fluxpay/service/PaymentSnapshot.java`
- Modify: `backend/src/main/java/com/fluxpay/service/DbPaymentReader.java`
- Modify: `backend/src/main/java/com/fluxpay/service/QuoteService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/RouteCatalogService.java`
- Modify: `backend/src/main/java/com/fluxpay/controller/RouteController.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/RouteApi.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/QuoteResponse.java`
- Test: `backend/src/test/java/com/fluxpay/service/QuoteEntryPointsTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/QuoteServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/controller/RouteControllerContractTest.java`

**Interfaces:**
- Consumes: Tasks 5-6 pipeline and frozen recipient destination.
- Produces: shared `SmartRoutingService.recommend(context,preference)` and frozen top-three quote rows.

- [ ] **Step 1: Write failing shared-entry tests**

```java
@Test
void quoteAndRecommendationUseTheSameTopThree() {
  QuoteResponse created = quoteService.createOrCurrent(USER_ID, PAYMENT_ID, "quote-key");
  RouteRecommendation ranked =
      smart.recommend(externalContext("IN", "INR"), RoutePreference.BALANCED);
  assertThat(created.quotes()).hasSize(3);
  assertThat(created.quotes()).extracting(QuoteResponse.Quote::routeCode)
      .containsExactlyElementsOf(
          ranked.quotes().stream().map(q -> q.quote().route().code()).toList());
}
```

Test corridor exclusion, inactive provider, expired regeneration, frozen ranking after route edits, and no eligible routes without advancing generation.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=QuoteEntryPointsTest,QuoteServiceTest,RouteControllerContractTest' test
```

- [ ] **Step 3: Add final quote snapshot columns**

Replace route-code FK shape with `route_id` and add `route_code` snapshot, `provider_id`, `effective_reliability NUMBER(9,6)`, `ranking_score NUMBER(19,12)`, and `ranking_position NUMBER(2) CHECK 1..3`. Unique key becomes `(payment_id,generation,route_id)`. Map all fields in `PaymentQuote`, replace `PaymentQuoteRepository.existsByRoute(String)` with `existsByRouteId(UUID)`, and update `RoutingUsageService` to use the id-based query.

- [ ] **Step 4: Freeze recipient destination**

Parse `Payment.recipientSnapshot()` in `DbPaymentReader` and add `ExternalAccountDestination destination` to `PaymentSnapshot`. Validate snapshot country/currency against payment target values.

- [ ] **Step 5: Implement shared orchestration**

```java
public RouteRecommendation recommend(
    TransferRoutingContext context, RoutePreference preference) {
  List<TransferRoute> eligible =
      eligibility.filter(routes.findAllByOrderByRouteCodeAsc(), context);
  Map<UUID, BigDecimal> rates = reliability.effectiveFor(eligible);
  List<RouteQuote> priced =
      pricing.price(context.gross(), context.marketRate(), eligible, rates);
  if (priced.isEmpty()) {
    throw new BusinessException(
        HttpStatus.UNPROCESSABLE_ENTITY, "NO_ELIGIBLE_ROUTES",
        "No transfer route is eligible for this destination.");
  }
  return recommender.recommend(preference, priced);
}
```

Both `QuoteService` and `RouteCatalogService` delegate. Persist only ranked quotes. Responses expose provider/route identity, position, effective reliability, score, and reason.

- [ ] **Step 6: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=QuoteEntryPointsTest,QuoteServiceTest,RouteControllerContractTest,PaymentConfirmationQuoteTest' test
git add backend/src/main/resources/db/migration/V003__routing_payments_and_quotes.sql backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay
git commit -m "feat(routing): apply smart routing to transfer quotes"
```

### Task 8: External Execution Through Rail Bindings

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutReservationService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutExecutionService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutReconciler.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PayoutFinalizationService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/SelectedQuoteService.java`
- Delete: `backend/src/main/java/com/fluxpay/common/contracts/PayoutProvider.java`
- Delete: `backend/src/main/java/com/fluxpay/dto/PayoutCmd.java`
- Delete: `backend/src/main/java/com/fluxpay/dto/PayoutResult.java`
- Delete: `backend/src/main/java/com/fluxpay/development/SimulatedStandardBankProvider.java`
- Delete: `backend/src/main/java/com/fluxpay/development/SimulatedInstantPayoutProvider.java`
- Delete: `backend/src/main/java/com/fluxpay/development/SimulatedLocalPartnerProvider.java`
- Modify Test: `backend/src/test/java/com/fluxpay/service/PayoutLifecycleIntegrationTest.java`
- Modify Test: `backend/src/test/java/com/fluxpay/service/PayoutProviderUnavailableTest.java`
- Replace Test: `backend/src/test/java/com/fluxpay/service/PayoutProviderTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/service/PayoutReconcilerTest.java`
- Modify Test: `backend/src/test/java/com/fluxpay/config/ApplicationBoundaryWiringTest.java`

**Interfaces:**
- Consumes: selected route/provider ids, rail registry, external destination, outcome recorder.
- Produces: durable provider/route reservation snapshots and rail-based external execution.

- [ ] **Step 1: Write failing shared-rail execution tests**

```java
@Test
void twoProvidersUseOneBankRailWithDistinctContext() {
  TransferRail bank = mockBankRail();
  service(new RailRegistry(List.of(bank))).perform(
      USER_ID, "hdfc-key", "SUBMIT", HDFC_PAYMENT, "HDFC_INR", null, "cid");
  service(new RailRegistry(List.of(bank))).perform(
      USER_ID, "sbi-key", "SUBMIT", SBI_PAYMENT, "SBI_INR", null, "cid");

  ArgumentCaptor<TransferRailCommand> commands =
      ArgumentCaptor.forClass(TransferRailCommand.class);
  verify(bank, times(2)).execute(commands.capture());
  assertThat(commands.getAllValues()).extracting(c -> c.provider().code())
      .containsExactly("HDFC_BANK", "SBI_BANK");
}
```

Test inactive catalogue before reservation, missing rail, incompatible destination, completed/failed recording, no uncertain outcome, and archived-snapshot reconciliation.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=PayoutLifecycleIntegrationTest,PayoutProviderUnavailableTest,PayoutReconcilerTest' test
```

- [ ] **Step 3: Freeze complete reservation context**

```java
public record Reserved(
    UUID userId, UUID paymentId, UUID attemptId, int attemptNumber,
    AcceptedQuote quote, TransferProviderSnapshot provider,
    TransferRouteSnapshot route, ExternalAccountDestination destination,
    TransferRailCommand command) {}
```

Initial/switch operations require active catalogue. Retry resolves the attempt route. Reconciliation uses the serialized snapshot and does not reapply active flags.

- [ ] **Step 4: Execute through registry and record terminal outcome**

```java
TransferRail rail = registry.requireCompatible(
    reserved.provider().railType(), reserved.route().destinationType());
TransferRailResult result;
try {
  result = rail.execute(reserved.command());
} catch (RuntimeException uncertain) {
  throw pendingReconciliation();
}
```

Finalization records `"payout:" + attemptId` as COMPLETED or FAILED. UNCERTAIN records nothing.

- [ ] **Step 5: Remove legacy provider types and verify no runtime coupling**

```powershell
rg -n "PayoutProvider|PayoutCmd|PayoutResult|STANDARD_BANK|INSTANT_PAYOUT|LOCAL_PARTNER" backend/src/main/java
```

Expected: no matches after deletions and migrated imports.

- [ ] **Step 6: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=PayoutLifecycleIntegrationTest,PayoutProviderUnavailableTest,PayoutReconcilerTest,ApplicationBoundaryWiringTest' test
git add backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay
git commit -m "refactor(payout): execute through transfer rail bindings"
```

### Task 9: Wallet Transfer Through INTERNAL_LEDGER

**Files:**
- Modify after prerequisite merge: `backend/src/main/java/com/fluxpay/controller/WalletController.java`
- Modify after prerequisite merge: `backend/src/main/java/com/fluxpay/service/WalletPostingService.java`
- Create: `backend/src/main/java/com/fluxpay/service/WalletTransferRoutingService.java`
- Create: `backend/src/main/java/com/fluxpay/adapter/transfer/InternalLedgerTransferRail.java`
- Modify after prerequisite merge: `backend/src/main/java/com/fluxpay/dto/WalletTransferResponse.java`
- Test after prerequisite merge: `backend/src/test/java/com/fluxpay/service/WalletTransferTest.java`
- Create Test: `backend/src/test/java/com/fluxpay/service/WalletTransferRoutingServiceTest.java`

**Interfaces:**
- Consumes: merged wallet posting primitive, smart routing, registry, outcome recorder.
- Produces: single-call P2P with BALANCED route selection and routing metadata.

- [ ] **Step 1: Verify prerequisite**

```powershell
rg -n 'PostMapping\("/transfer"\)|class WalletTransfer|record WalletTransfer' backend/src/main/java backend/src/test/java
```

Expected: endpoint, request/response, and `WalletTransferTest` exist. If absent, merge the approved wallet work; do not add ledger journal logic here.

- [ ] **Step 2: Write failing integration tests**

```java
@Test
void p2pUsesBalancedInternalWinnerAndRecordsOutcome() {
  when(routing.recommend(any(), eq(RoutePreference.BALANCED)))
      .thenReturn(recommendation(internalRoute("FLUXPAY_INR_INTERNAL")));
  when(internalRail.execute(any()))
      .thenReturn(TransferRailResult.completed("wallet:p2p:op-1", BigDecimal.ZERO));

  WalletTransferResponse response = service.transfer(USER_ID, request, "wallet-key");

  assertThat(response.routeCode()).isEqualTo("FLUXPAY_INR_INTERNAL");
  assertThat(response.providerCode()).isEqualTo("FLUXPAY");
  assertThat(response.railType()).isEqualTo(RailType.INTERNAL_LEDGER);
  verify(outcomes).record(
      INTERNAL_ROUTE_ID, "wallet:p2p:op-1", RouteOutcome.COMPLETED);
}
```

Test no eligible internal route, external exclusion, idempotent replay, failed posting, and one sender debit.

- [ ] **Step 3: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=WalletTransferRoutingServiceTest,WalletTransferTest' test
```

- [ ] **Step 4: Adapt posting primitive and orchestrate**

`InternalLedgerTransferRail` supports only internal destinations and invokes the already-tested `WalletPostingService.transfer` primitive with the same idempotency key. `WalletTransferRoutingService` builds internal context, requests BALANCED ranking, executes the winner once, and records its terminal outcome. Rewire `WalletController` to the routing service; leave journal construction in `WalletPostingService`.

- [ ] **Step 5: Add response/snapshot routing metadata**

Add `providerCode`, `routeCode`, `RailType railType`, and `effectiveReliability`. Persist them in the wallet operation response snapshot so replay returns the original decision.

- [ ] **Step 6: Verify and commit**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply
.\mvnw.cmd -f backend/pom.xml '-Dtest=WalletTransferRoutingServiceTest,WalletTransferTest,WalletOperationServiceTest' test
git add backend/src/main/java/com/fluxpay backend/src/test/java/com/fluxpay
git commit -m "feat(wallet): route p2p through internal ledger rail"
```

### Task 10: Admin Transfer-Routing Workspace

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/services/flux-api.ts`
- Create: `frontend/fluxpay-ui/src/ts/services/routing-workspace.ts`
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin.html`
- Modify: `frontend/fluxpay-ui/src/ts/services/page.ts`
- Modify: `frontend/fluxpay-ui/src/css/workspace.css`
- Create Test: `frontend/fluxpay-ui/tests/routing.test.cjs`
- Modify Test: `frontend/fluxpay-ui/tests/admin-navigation.test.cjs`

**Interfaces:**
- Consumes: Task 4 APIs and existing admin lifecycle/dialog conventions.
- Produces: provider/route CRUD, filters, activation, archive confirmation, and stale conflict UX.

- [ ] **Step 1: Write failing API/workspace tests**

```javascript
test('routing CRUD calls admin endpoints', async () => {
  const {api,calls}=fixture();
  await api.railTypes();
  await api.providers();
  await api.createProvider({
    providerCode:'HDFC_BANK',providerName:'HDFC Bank',
    railType:'BANK_NETWORK',active:true
  });
  await api.routesAdmin();
  await api.deleteRoute(routeId,3);
  assert.deepEqual(calls.map(([url,o])=>[o.method,url]),[
    ['GET','/api/admin/rail-types'],
    ['GET','/api/admin/providers'],
    ['POST','/api/admin/providers'],
    ['GET','/api/admin/routes'],
    ['DELETE','/api/admin/routes/'+routeId+'?version=3']
  ]);
});
```

Test form validation, filters, protected labels, archive confirmation, stale version retaining input, busy guard, and logout clearing late responses.

- [ ] **Step 2: Verify red**

From `frontend/fluxpay-ui`:

```powershell
node --test tests/routing.test.cjs tests/admin-navigation.test.cjs
```

- [ ] **Step 3: Add typed API methods**

Add `RailDescriptor`, `TransferProvider`, `TransferRoute`, request types, and methods for all nine admin operations. URL-encode ids and pass delete version as query parameter.

- [ ] **Step 4: Implement isolated workspace**

Follow `ComplianceWorkspace`: admin check, busy guard, epoch cancellation, `dispose`, error/notice, and confirmation state. Remove old route-editor fields/methods from `Page`. Validate the same code/corridor/money/limit rules before API calls.

- [ ] **Step 5: Add accessible routing tab**

Add `{id:'routing',label:'Transfer routing'}`. Build Providers and Routes sub-tabs with filters, tables, editors, text-only bindings, system-protected badges, and `role="alertdialog"` delete/archive confirmations. Reuse existing panel/form styles and add only missing routing grid/filter CSS.

- [ ] **Step 6: Verify build and commit**

```powershell
node --test tests/routing.test.cjs tests/admin-navigation.test.cjs tests/compliance.test.cjs
npx ojet build
git add src/ts src/css/workspace.css tests
git commit -m "feat(admin): manage transfer providers and routes"
```

### Task 11: Seed, Documentation, Acceptance, and Full Verification

**Files:**
- Modify: `scripts/seed-local.py`
- Modify when referenced: `backend/src/main/resources/db/migration/V603__m5_seed_data.sql`
- Modify: `backend/src/test/java/com/fluxpay/repository/SeedMigrationContractTest.java`
- Modify: `backend/src/test/java/com/fluxpay/integration/BackendAcceptanceIT.java`
- Modify: `README.md`
- Modify: `docs/api-catalog.md`
- Modify: `docs/openapi-notes.md`
- Modify: `docs/fluxpay-endpoint-chains.html`
- Modify: `docs/fluxpay-architecture.json`
- Regenerate: `docs/fluxpay-architecture.html` using the repository Archify workflow if its JSON source changes.

**Interfaces:**
- Consumes: completed backend, wallet integration, and admin UI.
- Produces: insert-only demo seed, accurate docs, and acceptance evidence.

- [ ] **Step 1: Write failing seed/acceptance assertions**

Seed contract requires provider/route inserts, `WHEN NOT MATCHED THEN INSERT` only, and no legacy route literals. Acceptance creates HDFC and SBI on one bank rail, creates four eligible routes including two HDFC routes, receives exactly three quotes, completes external and internal transfers, and observes learned reliability.

- [ ] **Step 2: Verify red**

```powershell
.\mvnw.cmd -f backend/pom.xml '-Dtest=SeedMigrationContractTest,BackendAcceptanceIT' test
```

Expected: seed contract fails on the old route tuple; guarded acceptance skips unless infrastructure is explicitly enabled.

- [ ] **Step 3: Replace fixed route seed**

```python
PROVIDERS = (
    ("FLUXPAY", "FluxPay", "INTERNAL_LEDGER", 1),
    ("DEMO_BANK_ALPHA", "Demo Bank Alpha", "BANK_NETWORK", 0),
    ("DEMO_REAL_TIME", "Demo Real-Time Network", "REAL_TIME_NETWORK", 0),
    ("DEMO_PARTNER", "Demo Partner Network", "PARTNER_NETWORK", 0),
)
```

Seed one protected internal route and at least four external IN/INR routes with deterministic UUIDs. MERGE uses only NOT MATCHED insert so reruns never reset admin edits.

- [ ] **Step 4: Update docs**

Document reset, provider/route/rail concepts, CRUD examples, eligibility, top-three behavior, prior formula, internal BALANCED selection, simulation flags, `BANK_NETWORK[:count]`, and the absence of runtime-installed integrations/credentials. Update architecture flow to `route -> provider -> rail -> internal ledger/external network`.

- [ ] **Step 5: Run forbidden-coupling guards**

```powershell
rg -n "STANDARD_BANK|INSTANT_PAYOUT|LOCAL_PARTNER" backend/src/main/java scripts/seed-local.py
rg -n "Map<String,\s*PayoutProvider>|PayoutProvider::code|customerFee\(String" backend/src/main/java
```

Expected: no matches.

- [ ] **Step 6: Run focused verification**

```powershell
.\mvnw.cmd -f backend/pom.xml spotless:apply spotless:check
.\mvnw.cmd -f backend/pom.xml '-Dtest=MigrationContractTest,TransferProviderServiceTest,TransferRouteServiceTest,RouteEligibilityServiceTest,RouteReliabilityServiceTest,RouteRecommenderTest,QuoteEntryPointsTest,PayoutLifecycleIntegrationTest,WalletTransferRoutingServiceTest' test
Push-Location frontend/fluxpay-ui
node --test tests/routing.test.cjs tests/admin-navigation.test.cjs tests/compliance.test.cjs
npx ojet build
Pop-Location
```

- [ ] **Step 7: Run repository-wide verification**

```powershell
python -B -m unittest discover -s tests
python -B scripts/test-all.py --suite backend
python -B scripts/test-all.py --suite frontend
```

With the isolated Oracle/Kafka environment configured:

```powershell
python -B scripts/reset-local-db.py --schema FLUXPAY_TEST --execute
.\mvnw.cmd -f backend/pom.xml -Pintegration verify
```

Expected: all local gates pass; integration reports a real pass rather than a skip when `ORACLE_TESTS_ACTIVE=true`.

- [ ] **Step 8: Commit**

```powershell
git add scripts/seed-local.py backend/src/main/resources/db/migration backend/src/test README.md docs
git commit -m "docs(routing): seed and document admin transfer routing"
```

## Final Review Checklist

- [ ] Admin writes require both Spring ADMIN authorization and the persistent role check.
- [ ] No runtime route code selects pricing or execution.
- [ ] Active routes have active providers and compatible installed rails.
- [ ] Used provider/route bindings cannot change.
- [ ] Used and system records archive.
- [ ] External quote generations contain one to three rows.
- [ ] Reliability uses terminal outcomes and the 20-attempt prior.
- [ ] Same-provider routes may fill all three positions.
- [ ] Wallet transfer debits once and replays its original internal route decision.
- [ ] External uncertain delivery records no terminal outcome before reconciliation.
- [ ] Fresh-schema, backend, frontend, and configured integration gates pass.
