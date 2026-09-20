# Policy Library Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give administrators a JSON-importable, editable policy library with a compact searchable list and policy-specific detail, indexing, chunk, edit, and delete dialogs.

**Architecture:** A new secured `PUT /api/policies/{id}` updates one policy and deletes its active vector state in the same transaction. The Oracle JET client parses JSON locally into editable drafts, creates reviewed drafts sequentially, then renders persisted policies as compact selectable rows. The existing policy details state becomes a modal rather than an inline editor, keeping chunks and index actions associated with a selected policy.

**Tech Stack:** Spring Boot 3 / Java 17 / JPA / Oracle vector store / JUnit 5 + Mockito / Oracle JET 16 / Knockout / TypeScript / Node built-in test runner.

**Spec:** `docs/superpowers/specs/2026-09-18-policy-library-management-design.md`

## Global Constraints

- Preserve the existing `ApiResponse<T>` success envelope and existing API error mapping.
- Policy actions must be enforced with `hasRole('ADMIN')` at the server boundary.
- JSON import is browser-local only; do not upload or persist source files.
- Only `.json` files, one policy object, or an array of policy objects are supported.
- A policy edit must remove its active indexed/vector state before the altered document is persisted.
- Search and category filters stay at the top of the persisted-policy list, immediately above the rows.
- Do not disturb the payment review, KYC, routes, or Copilot workflows.

---

### Task 1: Make policy updates invalidate stale index data

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/service/PolicyDocumentService.java`
- Modify: `backend/src/test/java/com/fluxpay/service/PolicyDocumentServiceTest.java` (create if absent)
- Test: `backend/src/test/java/com/fluxpay/service/PolicyDeletionServiceTest.java` (leave its delete contract intact)

**Interfaces:**
- Consumes: `PolicyDocumentRequest(title, category, content)`, `PolicyDocumentRepository`, `PolicyIndexStore`.
- Produces: `PolicyDocumentResponse update(UUID id, PolicyDocumentRequest request)`.
- Invariant: `PolicyIndexStore.delete(id)` occurs before `repository.save(document)` for every successful update.

- [ ] **Step 1: Write the failing service tests**

Create `PolicyDocumentServiceTest` with a real `PolicyDocument` fixture and mocked repository/index store. Cover an update that changes title/content/category, recalculates the hash, and removes index state before save:

```java
@Test
void updateClearsTheActiveIndexBeforeSavingTheChangedPolicy() {
  UUID id = UUID.randomUUID();
  PolicyDocument document = document(id, "Original", "Original text");
  when(repository.findById(id)).thenReturn(Optional.of(document));
  when(repository.findByDocumentHash(anyString())).thenReturn(Optional.empty());
  when(repository.save(document)).thenReturn(document);

  service.update(id, new PolicyDocumentRequest("Changed", PolicyCategory.AML, "Changed text"));

  InOrder order = inOrder(indexStore, repository);
  order.verify(indexStore).delete(id);
  order.verify(repository).save(document);
  assertThat(document.getTitle()).isEqualTo("Changed");
}
```

Add separate tests that an unknown ID throws `NoSuchElementException`, and that a new hash owned by a different policy throws `IllegalStateException` without calling `indexStore.delete` or `repository.save`.

- [ ] **Step 2: Run the service test and verify it fails for the missing update method**

Run:

```powershell
.\mvnw.cmd -f backend\pom.xml -Dtest=PolicyDocumentServiceTest test
```

Expected: compilation failure because `PolicyDocumentService.update` does not exist.

- [ ] **Step 3: Implement the minimal transactional update operation**

Inject `PolicyIndexStore` into `PolicyDocumentService`, factor hash calculation into the existing helper, and implement:

```java
@Transactional
public PolicyDocumentResponse update(UUID id, PolicyDocumentRequest request) {
  PolicyDocument document = find(id);
  String hash = sha256(request.title() + "|" + request.content());
  repository.findByDocumentHash(hash)
      .filter(existing -> !existing.getId().equals(id))
      .ifPresent(existing -> { throw new IllegalStateException("Duplicate policy content, existing id: " + existing.getId()); });
  indexStore.delete(id);
  document.setTitle(request.title());
  document.setCategory(request.category());
  document.setContent(request.content());
  document.setDocumentHash(hash);
  return toResponse(repository.save(document), List.of());
}
```

Keep the actual hash and duplicate message aligned with `create`. Do not change the schema: title, category, content, and hash already exist.

- [ ] **Step 4: Run the focused backend tests and format code**

Run:

```powershell
.\mvnw.cmd -f backend\pom.xml spotless:apply test -Dtest=PolicyDocumentServiceTest,PolicyDeletionServiceTest
```

Expected: all focused policy service tests pass.

- [ ] **Step 5: Commit the focused backend service change**

```powershell
git add backend/src/main/java/com/fluxpay/service/PolicyDocumentService.java backend/src/test/java/com/fluxpay/service/PolicyDocumentServiceTest.java
git commit -m "feat: invalidate policy index on update"
```

### Task 2: Expose and secure policy operations consistently

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/controller/PolicyController.java`
- Modify: `backend/src/main/java/com/fluxpay/controller/PolicyIndexController.java`
- Modify: `backend/src/test/java/com/fluxpay/controller/PolicyIndexControllerTest.java`
- Modify: `backend/src/test/java/com/fluxpay/controller/ComplianceCaseControllerSecurityContractTest.java`
- Create: `backend/src/test/java/com/fluxpay/controller/PolicyControllerTest.java`

**Interfaces:**
- Consumes: `PUT /api/policies/{id}` with `PolicyDocumentRequest` JSON.
- Produces: `200 ApiResponse<PolicyDocumentResponse>`.
- Security: class-level `@PreAuthorize("hasRole('ADMIN')")` protects both policy controllers and therefore every listed operation.

- [ ] **Step 1: Write failing controller/security contract tests**

Add direct controller test coverage for the update response:

```java
@Test
void updatesAPolicyWithTheStandardEnvelope() {
  UUID id = UUID.randomUUID();
  when(documents.update(eq(id), any())).thenReturn(response);

  var result = controller.update(id, request);

  assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
  assertThat(result.getBody().data()).isEqualTo(response);
}
```

Extend `ComplianceCaseControllerSecurityContractTest` (or rename it only if necessary) to inspect `@PreAuthorize` on `PolicyController` and `PolicyIndexController`, asserting that all policy reads and mutations inherit `hasRole('ADMIN')`.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run:

```powershell
.\mvnw.cmd -f backend\pom.xml -Dtest=PolicyControllerTest,PolicyIndexControllerTest,ComplianceCaseControllerSecurityContractTest test
```

Expected: compilation failure for the absent `update` controller method and/or assertion failure for missing policy security annotations.

- [ ] **Step 3: Add update endpoint and class-level authorization**

Add `@PutMapping("/{id}")` to `PolicyController`:

```java
@PutMapping("/{id}")
public ApiResponse<PolicyDocumentResponse> update(
    @PathVariable UUID id, @Valid @RequestBody PolicyDocumentRequest request) {
  return wrap(documentService.update(id, request));
}
```

Place `@PreAuthorize("hasRole('ADMIN')")` on both `PolicyController` and `PolicyIndexController`. Remove the now-redundant method-level policy delete annotation only if the class-level annotation replaces it exactly; do not loosen any existing endpoint.

- [ ] **Step 4: Run focused controller tests and formatting**

Run:

```powershell
.\mvnw.cmd -f backend\pom.xml spotless:apply test -Dtest=PolicyControllerTest,PolicyIndexControllerTest,ComplianceCaseControllerSecurityContractTest
```

Expected: policy update envelope and all ADMIN contracts pass.

- [ ] **Step 5: Commit the API/security change**

```powershell
git add backend/src/main/java/com/fluxpay/controller/PolicyController.java backend/src/main/java/com/fluxpay/controller/PolicyIndexController.java backend/src/test/java/com/fluxpay/controller/PolicyControllerTest.java backend/src/test/java/com/fluxpay/controller/PolicyIndexControllerTest.java backend/src/test/java/com/fluxpay/controller/ComplianceCaseControllerSecurityContractTest.java
git commit -m "feat: secure policy management and updates"
```

### Task 3: Add the frontend update client and import-draft state

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/services/flux-api.ts`
- Modify: `frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts`
- Modify: `frontend/fluxpay-ui/tests/compliance.test.cjs`

**Interfaces:**
- Consumes: `fluxApi.updatePolicy(id, { title, category, content })`.
- Produces: `PolicyDraft` entries with `id`, `title`, `category`, `content`, and `error` observable fields; `parsePolicyImport(raw)` accepts one JSON object or an array.
- Invariant: parsing is pure/local and a failed import leaves current drafts untouched.

- [ ] **Step 1: Write failing API and workspace tests**

Update the endpoint test to expect 15 calls and assert:

```javascript
await api.updatePolicy(id,{title:'Updated',category:'AML',content:'Changed'});
assert.deepEqual(calls.at(-1).slice(0,2), [`/api/policies/${id}`, { method:'PUT', /* existing headers/body */ }]);
```

Add workspace tests for:

```javascript
assert.deepEqual(
  parsePolicyImport('{"title":"One","category":"AML","content":"Text"}'),
  [{title:'One',category:'AML',content:'Text'}]
);
assert.equal(parsePolicyImport('[{"title":"One","category":"AML","content":"Text"}]').length,1);
assert.throws(() => parsePolicyImport('{"title":"","category":"AML","content":"Text"}'), /title/i);
```

Also test that a failed sequential create remains in `policyDrafts` with an error while successful drafts disappear, and that update reloads the selected policy with an empty chunk list.

- [ ] **Step 2: Run the frontend test and verify it fails**

Run:

```powershell
node frontend/fluxpay-ui/tests/compliance.test.cjs
```

Expected: failure because `updatePolicy`, `parsePolicyImport`, and draft state do not exist.

- [ ] **Step 3: Implement client and workspace state**

Add to `flux-api.ts`:

```typescript
updatePolicy:(id:string,body:{title:string;category:string;content:string}) =>
  request<PolicyDocument>(`/api/policies/${encodeURIComponent(id)}`,'PUT',body),
```

In `compliance-workspace.ts`, export a pure parser. It must reject non-object root values, invalid categories, blank fields, title lengths above 200, and duplicate normalized title-plus-content pairs. Add observables for `policyDrafts`, `policyEdit`, and `policyImportError`.

Use a `FileReader` only in `loadPolicyJson(event)`: verify the selected file name/type is JSON, read it as text, parse it through the pure parser, and append drafts only after parsing succeeds. Add `addManualPolicyDraft`, `removePolicyDraft`, `createPolicyDrafts`, `openPolicyEdit`, `savePolicyEdit`, and `closePolicyEdit`. In `createPolicyDrafts`, process drafts in order; remove only each successfully created draft and retain a server error on a failed one.

After `savePolicyEdit`, set the returned policy as selected, clear `chunks`, close the edit dialog, refresh the list, and display: `Policy updated. Rebuild its index before asking Copilot.`

- [ ] **Step 4: Run frontend tests to green**

Run:

```powershell
node frontend/fluxpay-ui/tests/compliance.test.cjs
```

Expected: all workspace/API contract tests pass.

- [ ] **Step 5: Commit the frontend state change**

```powershell
git add frontend/fluxpay-ui/src/ts/services/flux-api.ts frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts frontend/fluxpay-ui/tests/compliance.test.cjs
git commit -m "feat: add editable policy import drafts"
```

### Task 4: Rebuild the Policy Library markup and responsive list styling

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/views/admin.html`
- Modify: `frontend/fluxpay-ui/src/css/workspace.css`
- Modify: `frontend/fluxpay-ui/tests/compliance.test.cjs`

**Interfaces:**
- Consumes: workspace methods and observables from Task 3.
- Produces: top creation/import panel, filter-first persisted-policy list, details dialog, edit dialog, and existing delete confirmation.
- Accessibility: dialogs declare `role="dialog"`, `aria-modal="true"`, labels, close buttons, and use `adminDialog`.

- [ ] **Step 1: Write failing template assertions**

Add assertions that the policies section contains all of the following in this order: policy creation/import controls, then `Search policies`, category filter, then the persisted list rows. Assert that the file input has `accept=".json,application/json"`, rows use `click:openPolicy`, action buttons call `openPolicyEdit` and `askConfirmation('delete-policy')`, and no policy text uses an HTML binding.

- [ ] **Step 2: Run the frontend test and verify it fails**

Run:

```powershell
node frontend/fluxpay-ui/tests/compliance.test.cjs
```

Expected: template assertions fail because the page still uses card-based policy markup and inline editors.

- [ ] **Step 3: Replace the policy-tab markup**

In `admin.html`:

1. Replace the collapsed `Create policy` button/inline `policyForm` editor with a permanent top **Add policies** panel containing manual inputs, an **Add draft** button, JSON file input, draft review rows, and an **Add policies** button below the drafts.
2. Keep the next policy-list panel headed by the existing search/category/refresh toolbar, followed by `foreach:filteredPolicies`. Render one semantic button/row whose visible data is category and title only. Put Edit/Delete controls in a right-side action container that stops propagation before invoking its action.
3. Render `policy` as a `modal-backdrop` details dialog. Move index, refresh, chunk list, and manual chunk form there. Include Edit and Delete among its actions.
4. Render `policyEdit` as a separate modal form. It edits title, category, and content and calls `savePolicyEdit`.
5. Keep the shared confirmation dialog for deletion; change its copy to name `policy()?.title` when deleting a policy.

Use `text` bindings for all user-provided policy and error text. Do not use `html` bindings.

- [ ] **Step 4: Implement only the corresponding CSS**

In `workspace.css`, replace `.policy-grid`/`.policy-card` rules with `.policy-list`, `.policy-row`, `.policy-row-actions`, `.policy-draft-list`, and responsive single-column rules. Rows must have a clear keyboard focus state, title truncation/wrapping, fixed right-side actions, and a stacked mobile layout. Draft content textareas must remain readable without constraining policy content.

- [ ] **Step 5: Run the frontend unit suite and visual smoke test**

Run:

```powershell
node frontend/fluxpay-ui/tests/compliance.test.cjs
python -B scripts/start-frontend.py --verbose
```

Expected: test suite passes; at `http://localhost:8000`, the Policy Library places Add policies above the filter-first list and dialogs fit desktop and mobile widths.

- [ ] **Step 6: Commit the UI restructure**

```powershell
git add frontend/fluxpay-ui/src/ts/views/admin.html frontend/fluxpay-ui/src/css/workspace.css frontend/fluxpay-ui/tests/compliance.test.cjs
git commit -m "feat: redesign policy library administration"
```

### Task 5: Verify authorization, stale-index safety, and live UI flow

**Files:**
- Modify: `frontend/fluxpay-ui/tests/live-compliance-flows.cjs`
- Modify: `docs/M5_LOCAL_TESTING_GUIDE.md`

**Interfaces:**
- Consumes: seeded admin account, frontend proxy, local Oracle, Kafka, and Ollama only when indexing.
- Produces: an opt-in temporary policy lifecycle verification with guaranteed cleanup.

- [ ] **Step 1: Write the failing opt-in lifecycle assertion**

Extend the existing `--fixtures` flow to update its temporary QA policy after indexing, then assert:

```javascript
const changed = await api.updatePolicy(policyId, { title, category:'PAYMENT_REVIEW', content:changedContent });
assert.equal(changed.content, changedContent);
assert.equal((await api.policyChunks(policyId)).length, 0);
```

Keep the `finally` cleanup with `deletePolicy(policyId)` unchanged.

- [ ] **Step 2: Run the static frontend suite and verify the new lifecycle test is reachable**

Run:

```powershell
node frontend/fluxpay-ui/tests/compliance.test.cjs
node frontend/fluxpay-ui/tests/live-compliance-flows.cjs
```

Expected: static tests pass; the live script prints its existing opt-in notice without creating data.

- [ ] **Step 3: Document the manual admin test**

Add a short Policy Library section to `docs/M5_LOCAL_TESTING_GUIDE.md` with the supported JSON example, the top-panel → filter-first list flow, update/index invalidation behaviour, and the required admin role. State that all policy actions return 403 for non-admin users.

- [ ] **Step 4: Run the authorized local integration check**

With backend, frontend, local Oracle, Kafka, Ollama, and seed admin configured, run:

```powershell
node frontend/fluxpay-ui/tests/live-compliance-flows.cjs --fixtures --ai
```

Expected: it creates a disposable QA policy, indexes it, updates it, verifies chunks clear, optionally asks Copilot after reindexing if the script rebuilds the index, and deletes the temporary policy in `finally`.

- [ ] **Step 5: Run focused backend and frontend regression suites**

Run:

```powershell
.\mvnw.cmd -f backend\pom.xml test -Dtest=PolicyDocumentServiceTest,PolicyDeletionServiceTest,PolicyControllerTest,PolicyIndexControllerTest,ComplianceCaseControllerSecurityContractTest
node frontend/fluxpay-ui/tests/compliance.test.cjs
```

Expected: all selected backend and frontend checks pass. Record any unrelated Windows file-lock failures separately rather than treating them as product failures.

- [ ] **Step 6: Commit documentation and live-test coverage**

```powershell
git add frontend/fluxpay-ui/tests/live-compliance-flows.cjs docs/M5_LOCAL_TESTING_GUIDE.md
git commit -m "test: cover policy update lifecycle"
```

## Final verification checklist

- [ ] A normal USER receives a 403 for every policy API operation.
- [ ] An ADMIN can create a manual draft, import one-object JSON, import array JSON, edit drafts, and persist reviewed drafts.
- [ ] The filter/search controls remain at the top of the persisted-policy list.
- [ ] A policy row opens details; Edit/Delete controls do not trigger row selection.
- [ ] Editing clears chunks/vector state and requires explicit reindexing before Copilot uses the altered policy.
- [ ] Deleting requires confirmation and removes the policy and its index state.
- [ ] Existing payment review, KYC, route, and Copilot test cases remain green.
