# Grounded Copilot and Payment Risk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute this plan inline task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a grounded Ollama RAG Compliance Copilot and wire M5 risk reasons through administrator payment-review decisions.

**Architecture:** Retrieve active Oracle policy chunks, refuse low-relevance questions before generation, and use a separate Ollama chat model to answer only from retrieved excerpts. A detailed M5 assessment opens a linked review case; an administrator decision updates both the case and payment atomically.

**Tech Stack:** Java 17, Spring Boot, Spring Security, Spring JDBC/JPA, Oracle AI Vector Search, Ollama, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-16-grounded-compliance-copilot-design.md`

## Global Constraints

- Keep `POST /api/copilot/ask` response fields `answer` and `sources` compatible.
- Use `qwen3-embedding:4b` for 1536-dimensional retrieval and configurable `qwen3:4b` for generation.
- Empty or low-relevance retrieval returns a fixed scope refusal, no sources, and never calls chat generation.
- Derive decision reviewer identity from an authenticated `ADMIN` JWT, never from client input.
- Implement only risk facts supported by current inputs: configured threshold, unsupported currency, and invalid assessment input.

---

### Task 1: Add a grounded Ollama chat adapter

**Files:**
- Create: `backend/src/main/java/com/fluxpay/m5/domain/M5ChatPort.java`
- Create: `backend/src/main/java/com/fluxpay/m5/domain/M5ChatException.java`
- Create: `backend/src/main/java/com/fluxpay/m5/infrastructure/ollama/OllamaChatAdapter.java`
- Modify: `backend/src/main/java/com/fluxpay/m5/infrastructure/config/M5VectorProperties.java`
- Modify: `backend/src/main/java/com/fluxpay/m5/infrastructure/config/M5VectorConfiguration.java`
- Modify: `.env.example`
- Test: `backend/src/test/java/com/fluxpay/m5/infrastructure/ollama/OllamaChatAdapterTest.java`

**Interfaces:** Produces `M5ChatPort.answer(String question, List<CopilotSource> sources): String`; properties add `chatModel`, `chatTemperature`, and `maxDistance`.

- [ ] Write a failing adapter test that stubs `POST /api/chat` and asserts `stream=false`, configured model, source excerpts, and parsed `message.content`.
- [ ] Write a failing adapter test for blank `message.content`, expecting `M5ChatException`.
- [ ] Run `mvn -f backend/pom.xml -Dtest=OllamaChatAdapterTest test`; expect failure because the port and adapter do not exist.
- [ ] Implement the port and adapter. Use a policy-only system prompt, temperature `0.2`, and fail closed for non-2xx, timeout, malformed, or blank response.
- [ ] Register the adapter in M5 configuration and document `M5_OLLAMA_CHAT_MODEL=qwen3:4b`, `M5_OLLAMA_CHAT_TEMPERATURE=0.2`, and `M5_COPILOT_MAX_DISTANCE=0.65`.
- [ ] Re-run the adapter test; expect PASS. Commit as `feat(m5): add grounded Ollama chat adapter`.

### Task 2: Gate irrelevant questions and generate cited answers

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/m5/application/M5CopilotService.java`
- Modify: `backend/src/main/java/com/fluxpay/common/web/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/fluxpay/m5/application/M5CopilotServiceTest.java`

**Interfaces:** Consumes `M5ChatPort`, `PolicyMatch.distance()`, and `M5VectorProperties.maxDistance()`. Returns generated cited answers, fixed refusal responses, and maps `M5ChatException` to 503 `COPILOT_UNAVAILABLE`.

- [ ] Write failing tests: relevant retrieved evidence calls chat and retains citations; a high-distance birthday-style question returns the refusal without calling chat; an empty corpus returns the same refusal.
- [ ] Run `mvn -f backend/pom.xml -Dtest=M5CopilotServiceTest test`; expect failure because existing service returns the nearest excerpt.
- [ ] Implement `matches.isEmpty() || matches.get(0).distance() > maxDistance` as the deterministic refusal gate.
- [ ] Convert retained matches to `CopilotSource`, call `chat.answer(question, sources)`, and keep those exact sources in the response.
- [ ] Add a global M5 chat exception handler returning 503 so internal generation errors cannot become misleading authentication errors.
- [ ] Re-run the service tests; expect PASS. Commit as `feat(m5): generate grounded cited copilot answers`.

### Task 3: Return detailed M5 risk assessments and create linked review cases

**Files:**
- Create: `backend/src/main/java/com/fluxpay/common/contracts/ComplianceAssessment.java`
- Modify: `backend/src/main/java/com/fluxpay/common/contracts/ComplianceAssessor.java`
- Modify: `backend/src/main/java/com/fluxpay/m5/application/M5AmountComplianceAssessor.java`
- Modify: `backend/src/main/java/com/fluxpay/service/PaymentConfirmationService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/ComplianceCaseService.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/ComplianceCase.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/Payment.java`
- Create: `backend/src/main/resources/db/migration/V605__m5_review_case_binding.sql`
- Test: `backend/src/test/java/com/fluxpay/m5/application/M5AmountComplianceAssessorTest.java`
- Test: `backend/src/test/java/com/fluxpay/service/PaymentConfirmationServiceTest.java`

**Interfaces:** `ComplianceAssessor.assessDetailed(...)` returns verdict, risk, reasons, and action. Review confirmation retains selected quote id and review reference and opens exactly one linked case.

- [ ] Write a failing assessor test: amount above configured threshold returns REVIEW, MEDIUM, `AMOUNT_EXCEEDS_REVIEW_THRESHOLD`, and a manual-review action.
- [ ] Write a failing confirmation test: detailed REVIEW opens a case containing payment id, review reference, risk, reasons, and suggested action.
- [ ] Run `mvn -f backend/pom.xml -Dtest=M5AmountComplianceAssessorTest,PaymentConfirmationServiceTest test`; expect failure because detailed risk and case binding do not exist.
- [ ] Add immutable `ComplianceAssessment(ScreeningVerdict, ComplianceRisk, List<String>, String)`. Keep the existing verdict method and add a default detailed method for existing implementations; override it in M5 with supported threshold, unsupported-currency, and invalid-input facts.
- [ ] Add V605 with nullable `review_reference` for historical cases, index it, retain selected quote before payment review, and persist the linked case in the confirmation transaction.
- [ ] Re-run focused tests; expect PASS. Commit as `feat(m5): record risk evidence for payment reviews`.

### Task 4: Apply administrator decisions to linked payments

**Files:**
- Create: `backend/src/main/java/com/fluxpay/service/CompliancePaymentDecisionService.java`
- Modify: `backend/src/main/java/com/fluxpay/service/ComplianceCaseService.java`
- Modify: `backend/src/main/java/com/fluxpay/controller/ComplianceCaseController.java`
- Modify: `backend/src/main/java/com/fluxpay/dto/ComplianceDecisionRequest.java`
- Modify: `backend/src/main/java/com/fluxpay/beans/Payment.java`
- Test: `backend/src/test/java/com/fluxpay/service/CompliancePaymentDecisionServiceTest.java`
- Test: `backend/src/test/java/com/fluxpay/controller/ComplianceCaseControllerTest.java`

**Interfaces:** Consumes an open linked case, locked under-review payment, selected quote, `PostingPort`, `PayoutOutboxService`, and JWT `CurrentUser`; produces atomic case/payment approval or rejection.

- [ ] Write failing tests: admin approval posts a valid under-review payment and starts processing; admin rejection marks payment rejected; an expired stored quote leaves payment/case unchanged; non-admin decision returns forbidden.
- [ ] Run `mvn -f backend/pom.xml -Dtest=CompliancePaymentDecisionServiceTest,ComplianceCaseControllerTest test`; expect failure because current decisions only update case state and trust `decidedBy` from request JSON.
- [ ] Add `@PreAuthorize("hasRole('ADMIN')")`; lock case and payment; require OPEN case, UNDER_REVIEW payment, and matching review reference; derive decision identity from `CurrentUser`.
- [ ] For approval validate unexpired quote, post payment, record posting snapshot, transition to PROCESSING, and enqueue `PAYMENT_INITIATED`. For rejection transition to REJECTED. Save case and payment in the same transaction.
- [ ] Re-run focused tests; expect PASS. Commit as `feat(m5): apply admin compliance decisions to payments`.

### Task 5: Verify and document local workflows

**Files:**
- Modify: `.env.example`
- Modify: `README.md`
- Create: `backend/src/test/java/com/fluxpay/m5/application/M5ConfigurationContractTest.java`

- [ ] Write a failing configuration-contract test asserting the three new RAG variables appear in `.env.example`.
- [ ] Run `mvn -f backend/pom.xml -Dtest=M5ConfigurationContractTest test`; expect failure until docs are added.
- [ ] Document `ollama pull qwen3:4b`, index-before-ask, low-relevance refusal, and the admin review-decision test flow without secrets.
- [ ] Run `mvn -f backend/pom.xml -Dtest=M5CopilotServiceTest,OraclePolicySearchRepositoryTest,M5AmountComplianceAssessorTest,CompliancePaymentDecisionServiceTest,M5ConfigurationContractTest test`; expect PASS.
- [ ] Commit as `docs: explain grounded copilot and payment review`.
