# Plan 4 — Copilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream admin answers per-token and add scoped user helper without policy leaks.

**Architecture:** Keep unary `/ask` working, add SSE `/ask/stream` with `stream:true` chat adapter; new `/helper/ask` uses help-KB plus own-data only, never policy corpus; frontend drawer as new files only.

**Tech Stack:** Spring MVC SSE (`text/event-stream`), Ollama chat, JUnit + MockMvc, JET drawer component.

**Spec:** `docs/superpowers/specs/2026-09-17-copilot-design.md`

## Global Constraints

- Admin stays `@PreAuthorize("hasRole('ADMIN')")`; helper is auth + owner-scoped, 404 on cross-user payment.
- Helper never queries `policy_documents/chunks`; rate-limit 20/min per user.
- OWNERSHIP: this track alone edits `CopilotController.java`, `CopilotService.java`, `OllamaChatAdapter.java` (additive method only) and creates `HelperController/Service` + `helper-drawer` frontend files. Do NOT edit `appController.ts`, `admin.ts`, `session.ts`, wallet/payout files. Append helper API at `flux-api.ts` helper anchor (separate from Plan 1 anchor).
- Contract: produces `POST /api/copilot/ask/stream`, `POST /api/helper/ask`; Plan 5 mounts drawer host element.

---

### Task 1: Admin SSE streaming

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/controller/CopilotController.java:25-31`
- Modify: `backend/src/main/java/com/fluxpay/service/CopilotService.java:41-61`
- Modify: `backend/src/main/java/com/fluxpay/adapter/ollama/OllamaChatAdapter.java`
- Test: `backend/src/test/java/com/fluxpay/service/CopilotServiceTest.java`

**Interfaces:**
- Consumes: existing RAG search. Produces: `POST /api/copilot/ask/stream -> text/event-stream {delta, done}`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void streamEmitsDone() throws Exception {
  mockMvc.perform(post("/api/copilot/ask/stream").header("Authorization", "Bearer admin")
    .contentType(APPLICATION_JSON).content("{\"question\":\"fees?\"}"))
    .andExpect(status().isOk()).andExpect(header().string("Content-Type", containsString("text/event-stream")));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=CopilotServiceTest#streamEmitsDone -v`
Expected: FAIL with 404 no mapping

- [ ] **Step 3: Write minimal implementation**

```java
@PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@PreAuthorize("hasRole('ADMIN')")
public SseEmitter stream(@Valid @RequestBody CopilotRequest r) {
  SseEmitter e = new SseEmitter(90000L);
  copilot.stream(r, d -> { try { e.send(SseEmitter.event().data(Map.of("delta", d))); } catch (Exception ex) { e.completeWithError(ex); } }, e::complete);
  return e;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=CopilotServiceTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/CopilotController.java backend/src/main/java/com/fluxpay/service/CopilotService.java
git commit -m "feat(copilot): add admin streaming"
```

### Task 2: Scoped user helper

**Files:**
- Create: `backend/src/main/java/com/fluxpay/controller/HelperController.java`
- Create: `backend/src/main/java/com/fluxpay/service/HelperService.java`
- Test: `backend/src/test/java/com/fluxpay/controller/HelperControllerTest.java`

**Interfaces:**
- Consumes: `DbPaymentReader` owner check, own wallets/payments/tickets. Produces: `POST /api/helper/ask -> {answer, links[]}`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void helperRejects чужойPayment() throws Exception {
  mockMvc.perform(post("/api/helper/ask").header("Authorization", "Bearer user")
    .contentType(APPLICATION_JSON).content("{\"question\":\"status?\",\"paymentId\":\"" + otherId + "\"}"))
    .andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=HelperControllerTest -v`
Expected: FAIL with no mapping

- [ ] **Step 3: Write minimal implementation**

```java
@RestController @RequestMapping("/api/helper")
public class HelperController {
  @PostMapping("/ask") public ApiResponse<HelperAnswer> ask(@AuthenticationPrincipal CurrentUser u,
    @Valid @RequestBody HelperRequest r) {
    if (r.paymentId() != null) ownedOr404(u.userId(), r.paymentId());
    return new ApiResponse<>("none", helper.answer(u.userId(), r));
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=HelperControllerTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/HelperController.java backend/src/main/java/com/fluxpay/service/HelperService.java
git commit -m "feat(helper): add scoped user helper"
```

### Task 3: Helper drawer fragment (new files only)

**Files:**
- Create: `frontend/fluxpay-ui/src/ts/viewModels/helper-drawer.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/helper-drawer.html`
- Modify (append-only helper anchor): `frontend/fluxpay-ui/src/ts/services/flux-api.ts`

**Interfaces:**
- Consumes: Task 2 URL. Produces: `<helper-drawer>` element; Plan 5 mounts `<div id="helper-host">`.

- [ ] **Step 1: Append helper API + create drawer shell calling `/api/helper/ask`**
- [ ] **Step 2: Verify `npm run build` passes**
- [ ] **Step 3: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/viewModels/helper-drawer.ts frontend/fluxpay-ui/src/ts/views/helper-drawer.html
git commit -m "feat(helper): add drawer fragment"
```
