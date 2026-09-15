# M5 Ollama Compliance Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate M5 Oracle-vector policy search, grounded Copilot, compliance screening, and payment review with the current concrete FluxPay application.

**Architecture:** The current branch owns payment confirmation, wallets, KYC, routing, authentication, and messaging. M5 is introduced below `com.fluxpay.m5`, with an adapter implementing the existing `ComplianceAssessor` port; it does not import the source branch's competing M3 payment workflow. M5 adds only new migrations, and uses a 768-dimensional Ollama `nomic-embed-text` space at runtime.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA/JDBC, Oracle AI Vector Search, Flyway, Ollama, Maven/JUnit.

**Spec:** `docs/superpowers/specs/2026-09-15-m5-ollama-integration-design.md`

## Global Constraints

- Preserve `PaymentConfirmationService`, wallets, KYC, routing, JWT security, and outbox contracts as authoritative.
- Add migrations after `V603`; do not rename or edit applied migration files.
- Production uses Ollama at exactly 768 dimensions. Deterministic embeddings exist only in test configuration.
- Phase 1 Copilot returns cited extracts, not LLM-generated answers.
- M5 endpoints use the current-user and API-error conventions.

---

### Task 1: Create M5 domain types and additive schema

**Files:**
- Create: `backend/src/main/java/com/fluxpay/m5/domain/PolicyDocument.java`
- Create: `backend/src/main/java/com/fluxpay/m5/domain/PolicyChunk.java`
- Create: `backend/src/main/java/com/fluxpay/m5/domain/ScreeningCase.java`
- Create: `backend/src/main/resources/db/migration/V604__m5_vector_and_screening_schema.sql`
- Create: `backend/src/main/resources/db/migration/V605__m5_policy_generations.sql`
- Test: `backend/src/test/java/com/fluxpay/m5/infrastructure/persistence/M5MigrationContractTest.java`

**Interfaces:** Policy chunks are bound to a generation and contain `float[768]` embeddings. Screening cases reference existing `payments.id` and schema migration changes no payment state.

- [ ] **Step 1: Write failing migration tests.**

```java
@Test void m5MigrationsFollowV603() {
  assertThat(migrationNames()).containsSubsequence(
      "V601__m5_compliance_cases.sql", "V602__m5_policy_documents_chunks.sql",
      "V603__m5_seed_data.sql", "V604__m5_vector_and_screening_schema.sql");
}
@Test void vectorsUseOllamaDimensions() {
  assertThat(sql("V604__m5_vector_and_screening_schema.sql"))
      .contains("VECTOR(768, FLOAT32)");
}
```

- [ ] **Step 2: Run** `./mvnw -f backend/pom.xml -Dtest=M5MigrationContractTest test` **and verify failure.**
- [ ] **Step 3: Add `m5_policy_generations`, generation-bound policy chunks, and `m5_screening_cases`; use foreign keys to the current policy and payment tables.**
- [ ] **Step 4: Run** `./mvnw -f backend/pom.xml -Dtest=M5MigrationContractTest,MigrationContractTest test`.
- [ ] **Step 5: Commit** `git add backend/src/main/java/com/fluxpay/m5 backend/src/main/resources/db/migration backend/src/test/java/com/fluxpay/m5 && git commit -m "feat(m5): add vector and screening schema"`.

### Task 2: Implement concrete Ollama embedding and Oracle vector search

**Files:**
- Create: `backend/src/main/java/com/fluxpay/m5/domain/{EmbeddingPort,PolicySearchPort,PolicyMatch}.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/ollama/{OllamaEmbeddingProperties,OllamaEmbeddingAdapter}.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/persistence/OraclePolicySearchRepository.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/config/M5VectorConfiguration.java`
- Test: `backend/src/test/java/com/fluxpay/m5/infrastructure/ollama/OllamaEmbeddingAdapterTest.java`
- Test: `backend/src/test/java/com/fluxpay/m5/infrastructure/persistence/OraclePolicySearchRepositoryTest.java`

**Interfaces:** `EmbeddingPort.embedDocument(String)` and `embedQuery(String)` return validated `float[768]`. `PolicySearchPort.search(float[], String, int, double)` returns only cited chunks in the active named vector space.

- [ ] **Step 1: Write failing adapter tests.**

```java
@Test void queryUsesOllamaAndPrefix() {
  assertThat(adapter.embedQuery("what is KYC")).hasSize(768);
  assertThat(requestBody()).contains("search_query: what is KYC");
}
@Test void rejectsWrongDimensions() {
  assertThatThrownBy(() -> adapter.embedDocument("policy"))
      .isInstanceOf(M5ProviderException.class);
}
```

- [ ] **Step 2: Run** `./mvnw -f backend/pom.xml -Dtest=OllamaEmbeddingAdapterTest test` **and verify failure.**
- [ ] **Step 3: Implement `POST /api/embed`, finite HTTP timeouts, response-size limits, finite/nonzero vector validation, and `search_document:` / `search_query:` prefixes.**
- [ ] **Step 4: Implement Oracle search with bound parameters, the configured generation space id, top-K, and distance cutoff; exclude superseded policy versions.**
- [ ] **Step 5: Provide production Ollama wiring and a test-only deterministic `EmbeddingPort`; no mock component belongs in `src/main`.**
- [ ] **Step 6: Run** `./mvnw -f backend/pom.xml -Dtest=OllamaEmbeddingAdapterTest,OraclePolicySearchRepositoryTest test`.
- [ ] **Step 7: Commit** `git add backend/src/main/java/com/fluxpay/m5 backend/src/test/java/com/fluxpay/m5 && git commit -m "feat(m5): add Ollama policy embeddings"`.

### Task 3: Add policy indexing and the grounded Copilot API

**Files:**
- Create: `backend/src/main/java/com/fluxpay/m5/application/{PolicyChunker,PolicyIndexingService,CopilotService}.java`
- Create: `backend/src/main/java/com/fluxpay/m5/api/{PolicyController,CopilotController,M5CopilotDtos}.java`
- Test: `backend/src/test/java/com/fluxpay/m5/application/{PolicyChunkerTest,CopilotServiceTest}.java`
- Test: `backend/src/test/java/com/fluxpay/m5/api/CopilotControllerTest.java`

**Interfaces:** `PolicyIndexingService.index(UUID)` replaces only one document's managed generation. `CopilotService.ask(Ask, CurrentUser)` returns `Answer(answer, sources, context)`.

- [ ] **Step 1: Write failing grounding tests.**

```java
@Test void returnsExcerptWithCitation() {
  var answer = service.ask(new Ask("What is the KYC requirement?", null), actor);
  assertThat(answer.answer()).contains("verification");
  assertThat(answer.sources()).hasSize(1);
}
@Test void returnsNoGroundedAnswerWithoutEligibleMatch() {
  assertThat(service.ask(new Ask("unrelated", null), actor).sources()).isEmpty();
}
```

- [ ] **Step 2: Run** `./mvnw -f backend/pom.xml -Dtest=PolicyChunkerTest,CopilotServiceTest test` **and verify failure.**
- [ ] **Step 3: Implement whole-word chunking with configured min/max/overlap; persist an active generation only after all document vectors are valid.**
- [ ] **Step 4: Implement extractive answers: the top eligible chunk's bounded excerpt plus every returned document/chunk citation; return exactly `No grounded answer found in current policies.` without matches.**
- [ ] **Step 5: Add authenticated `POST /api/m5/policies/{id}/index` and `POST /api/m5/copilot/questions`; validate questions at 1–1000 code points.**
- [ ] **Step 6: Run** `./mvnw -f backend/pom.xml -Dtest=PolicyChunkerTest,CopilotServiceTest,CopilotControllerTest test`.
- [ ] **Step 7: Commit** `git add backend/src/main/java/com/fluxpay/m5 backend/src/test/java/com/fluxpay/m5 && git commit -m "feat(m5): add grounded policy Copilot"`.

### Task 4: Connect M5 screening to the existing payment confirmation port

**Files:**
- Create: `backend/src/main/java/com/fluxpay/m5/application/{ComplianceAssessmentService,ComplianceRulesEngine,ReviewDecisionService}.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/persistence/{M5PaymentObservationRepository,OracleScreeningCaseRepository}.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/adapter/M5ComplianceAssessor.java`
- Modify: `backend/src/main/java/com/fluxpay/service/UnavailableComplianceAssessor.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PaymentConfirmationService.java`
- Test: `backend/src/test/java/com/fluxpay/m5/application/ComplianceAssessmentServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/PaymentConfirmationQuoteTest.java`

**Interfaces:** `M5ComplianceAssessor implements ComplianceAssessor` and returns the existing `ScreeningVerdict`. The existing confirmation service retains its public API, operation idempotency, posting, and outbox behavior.

- [ ] **Step 1: Write a failing port-level integration test.**

```java
@Test void m5ReviewLeavesExistingPaymentFlowUnderReview() {
  when(compliance.assess(senderId, amount, "USD")).thenReturn(ScreeningVerdict.REVIEW);
  assertThat(service.confirm(senderId, paymentId, request, key).status())
      .isEqualTo("UNDER_REVIEW");
}
```

- [ ] **Step 2: Run** `./mvnw -f backend/pom.xml -Dtest=PaymentConfirmationQuoteTest,ComplianceAssessmentServiceTest test` **and verify failure.**
- [ ] **Step 3: Load authoritative payment, recipient, KYC, and history facts from the current repositories. Evaluate the imported M5 rules only from those facts; missing facts fail closed rather than being invented.**
- [ ] **Step 4: Persist/reuse a screening case idempotently and map its outcome to the current `ALLOW`, `REVIEW`, or `BLOCK` enum.**
- [ ] **Step 5: Make `M5ComplianceAssessor` the enabled production bean; make `UnavailableComplianceAssessor` conditional on `fluxpay.m5.enabled=false`. Preserve the existing development simulated assessor.**
- [ ] **Step 6: Run** `./mvnw -f backend/pom.xml -Dtest=PaymentConfirmationQuoteTest,ComplianceAssessmentServiceTest test`.
- [ ] **Step 7: Commit** `git add backend/src/main/java/com/fluxpay/m5 backend/src/main/java/com/fluxpay/service/UnavailableComplianceAssessor.java backend/src/test/java/com/fluxpay/m5 backend/src/test/java/com/fluxpay/service/PaymentConfirmationQuoteTest.java && git commit -m "feat(m5): integrate compliance screening"`.

### Task 5: Complete secure endpoint wiring and retire duplicate M5 runtime paths

**Files:**
- Create: `backend/src/main/java/com/fluxpay/m5/api/{ComplianceController,M5ApiExceptionHandler}.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/config/M5Configuration.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify/Delete after reference audit: old flat M5/Copilot/mock source files copied from `origin/feat/05-member5-updated`
- Test: `backend/src/test/java/com/fluxpay/m5/M5ProductionWiringTest.java`
- Test: `backend/src/test/java/com/fluxpay/m5/api/ComplianceControllerTest.java`

**Interfaces:** Existing JWT filters and `CurrentUser` remain the sole authentication mechanism. Assessment/review endpoints never post funds; payment transitions remain owned by payment confirmation.

- [ ] **Step 1: Write a failing production-wiring test.**

```java
@Test void productionUsesConcreteM5Components() {
  assertThat(context.getBean(EmbeddingPort.class)).isInstanceOf(OllamaEmbeddingAdapter.class);
  assertThat(context.getBean(ComplianceAssessor.class)).isInstanceOf(M5ComplianceAssessor.class);
}
```

- [ ] **Step 2: Run** `./mvnw -f backend/pom.xml -Dtest=M5ProductionWiringTest test` **and verify failure.**
- [ ] **Step 3: Set defaults for `fluxpay.m5.enabled=true`, `fluxpay.m5.ollama.url=http://localhost:11434/api/embed`, and `fluxpay.m5.ollama.model=nomic-embed-text`.**
- [ ] **Step 4: Add M5 role checks using existing role names and error response conventions.**
- [ ] **Step 5: Run a reference audit:** `rg -n "(M5EmbeddingAdapter|MockEmbeddingProvider|M5BackendApplication|M5RiskApplication|M5SecurityConfig|CopilotService)" backend/src/main backend/src/test`. Move retained M5 responsibilities to `com.fluxpay.m5`; remove a legacy file only after no live reference remains and replacement tests pass.**
- [ ] **Step 6: Run** `./mvnw -f backend/pom.xml -Dtest=M5ProductionWiringTest,ComplianceControllerTest,CopilotControllerTest test`.
- [ ] **Step 7: Commit** `git add backend/src/main/java backend/src/main/resources/application.yml backend/src/test/java && git commit -m "feat(m5): wire secured compliance APIs"`.

### Task 6: Run full regression and document Ollama operation

**Files:**
- Modify: `README.md`
- Modify: `docs/api-catalog.md`
- Create: `docs/m5-ollama-local-setup.md`

- [ ] **Step 1: Document exactly `ollama pull nomic-embed-text`, `ollama serve`, M5 properties, policy indexing, and the current extractive-Copilot limitation.**
- [ ] **Step 2: Run** `./mvnw -f backend/pom.xml spotless:check test`.
- [ ] **Step 3: If affected frontend API paths exist, run** `npm --prefix frontend/fluxpay-ui run build`.
- [ ] **Step 4: Inspect** `git diff main...HEAD --check && git log --oneline main..HEAD && git status --short`.
- [ ] **Step 5: Commit** `git add README.md docs/api-catalog.md docs/m5-ollama-local-setup.md && git commit -m "docs: describe M5 Ollama workflow"`.

