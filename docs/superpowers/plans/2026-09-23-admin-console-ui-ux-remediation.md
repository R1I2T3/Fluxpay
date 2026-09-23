# FluxPay Admin Console UI/UX Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace unstable inline administrator cards with compact queues, responsive tables, and modal-only record workflows while preserving the FluxPay theme, APIs, authentication, and `ADMIN` role.

**Architecture:** Keep the existing Oracle JET routes, Knockout view models, and API services. Add shared admin-scoped list, modal, table, and chat presentation primitives; each domain view model continues to own its current validation and mutations while exposing explicit open, close, and modal-step state.

**Tech Stack:** Oracle JET 16.1, Knockout, TypeScript 5.3, semantic HTML, CSS, Node's built-in test runner.

**Spec:** `docs/superpowers/specs/2026-09-23-admin-console-ui-ux-remediation-design.md`

## Global Constraints

- Work in the current `feat/admin-operations-console` checkout and preserve all existing uncommitted frontend changes; do not reset or check out files.
- Frontend and documentation only. `git diff -- backend` must remain empty.
- Do not modify authentication, JWT/session behavior, route guards, or the existing `ADMIN` role.
- Do not change API routes, request bodies, response bodies, database migrations, or backend behavior.
- Keep the current FluxPay colors. Do not add gradients, a new theme, or an externally hosted font.
- Do not add an npm, icon, grid, or UI-framework dependency.
- Use the existing APIs only; do not imply OCR, SLA, stored Copilot history, governance, audit, dual approval, policy publication, or a route winner.
- Every record-specific action and confirmation belongs inside the record's modal workflow.
- Use existing icon classes; icon-only controls require `aria-label` and `title`.
- Consequential actions such as Approve, Reject, Apply changes, Resolve, and Close ticket retain visible text.
- Preserve entered values on validation, network, and provider/route stale-version failures.
- Allow the running development server 10 to 30 seconds to rebuild before checking served assets.
- Use `npm.cmd` commands on Windows.
- Run every `node` and `npm.cmd` command from `frontend/fluxpay-ui`; run every `git` command from the repository root.

## Review Focus

- Oracle JET may provide a null outer `$root`: provider, route, and policy bindings must evaluate from the correct parent context; `tests/admin-binding-context.test.cjs` pins this behavior.
- Failed or stale mutations must leave the same modal open with operator input intact; routing and policy tests exercise retry state.
- Initial Support loading must not select a ticket, while a valid deep link and a retained refresh selection must still open the requested modal.
- Route and policy action cells must remain readable at narrow widths; source tests assert minimum-width scroll containers, sticky action cells, and non-wrapping icon controls.
- Clipboard unavailability and Copilot provider errors must preserve visible payment/question data and produce a recoverable error rather than clearing the workflow.

## File Structure

### New test file

- `frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs` — shared typography, modal, list, responsive-table, icon, and chat presentation contracts.

### Existing production files

- `src/css/app.css` — global system-font stack.
- `src/css/home.css` — landing-page override aligned with the global stack.
- `src/css/admin-console.css` — admin list, modal, compact overview, wide-table, and Copilot styles.
- `src/css/workspace.css` — retain the corrected administrator content offset and reusable dialog primitives.
- `src/index.html` — retain the administrator sidebar without the removed Sandbox label.
- `src/ts/services/admin-dialog.ts` — focus trap, initial-focus target, and focus restoration.
- `src/ts/services/compliance-workspace.ts` — single active policy modal mode and policy-view reset.
- `src/ts/viewModels/admin.ts` — complete Overview pending/unavailable binding state.
- `src/ts/viewModels/admin-kyc.ts` — document-preview mode and focus restoration.
- `src/ts/viewModels/admin-compliance.ts` — payment copy and decision-completion wrappers.
- `src/ts/viewModels/admin-tickets.ts` — explicit selection and modal close behavior.
- `src/ts/viewModels/admin-copilot.ts` — case selector, manual context, and current-exchange composer behavior.
- Eight administrator templates — compact Overview, modal review/configuration workflows, responsive tables, and chat presentation.

### Existing tests

- `tests/admin-console.test.cjs`
- `tests/admin-kyc.test.cjs`
- `tests/admin-review-workspaces.test.cjs`
- `tests/admin-routing-console.test.cjs`
- `tests/admin-policy-copilot.test.cjs`
- `tests/admin-binding-context.test.cjs`
- `tests/admin-integration.test.cjs`
- `tests/admin-navigation.test.cjs`
- `tests/experience.test.cjs`
- `tests/navigation.test.cjs`

---

### Task 1: Establish typography and shared admin interaction primitives

**Files:**

- Create: `frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs`
- Modify: `frontend/fluxpay-ui/src/css/app.css:1-2`
- Modify: `frontend/fluxpay-ui/src/css/home.css:117-124`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/src/css/workspace.css:1-5`
- Modify: `frontend/fluxpay-ui/src/index.html:43-46`
- Modify: `frontend/fluxpay-ui/src/ts/services/admin-dialog.ts`
- Modify: `frontend/fluxpay-ui/tests/admin-navigation.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/experience.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/navigation.test.cjs`

**Interfaces:**

- Consumes: existing `.admin-shell`, `.admin-confirmation`, `.icon-action`, and Knockout binding registration.
- Produces: `adminDialog: true | {initialFocus: string}`, `.admin-list-surface`, `.review-list`, `.review-list-row`, `.admin-workflow-modal`, `.admin-modal-header`, `.admin-modal-body`, `.admin-modal-footer`, `.admin-wide-table`, and `.sticky-action`.

- [ ] **Step 1: Write failing shared UI contract tests**

```js
// tests/admin-ui-contract.test.cjs
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.join(__dirname,'../src');
const read=file=>fs.readFileSync(path.join(root,file),'utf8');

test('FluxPay uses the deliberate system font stack without a web-font dependency',()=>{
  const app=read('css/app.css');
  const home=read('css/home.css');
  for(const name of ['Segoe UI Variable Text','Segoe UI','Helvetica Neue']){
    assert.ok(app.includes(name),name);
    assert.ok(home.includes(name),`home ${name}`);
  }
  assert.doesNotMatch(app,/src:\s*url\(|@font-face/);
});

test('admin CSS defines stable lists, one workflow modal, and wide-table actions',()=>{
  const css=read('css/admin-console.css');
  for(const token of ['.admin-list-surface','.review-list-row','.admin-workflow-modal','.admin-modal-header','.admin-modal-body','.admin-modal-footer','.admin-wide-table','.sticky-action']) assert.ok(css.includes(token),token);
  assert.match(css,/\.admin-workflow-modal\s*\{[^}]*max-height:\s*90dvh/);
  assert.match(css,/\.sticky-action\s*\{[^}]*position:\s*sticky[^}]*right:\s*0/);
});
```

- [ ] **Step 2: Run the new test and verify it fails**

Run: `node --test tests/admin-ui-contract.test.cjs`

Expected: FAIL because the Segoe-first stack and shared admin classes do not exist.

- [ ] **Step 3: Implement the font and dialog contracts**

Set both the Oracle JET variable and body/landing fonts to:

```css
"Segoe UI Variable Text", "Segoe UI", "Helvetica Neue", Arial, sans-serif
```

Extend `admin-dialog.ts` without breaking existing `adminDialog:true` callers:

```ts
type AdminDialogOptions = true | { initialFocus?: string };

ko.bindingHandlers.adminDialog = {
  init(element: HTMLElement, valueAccessor?: () => AdminDialogOptions) {
    const previous = document.activeElement as HTMLElement | null;
    const options = valueAccessor ? ko.unwrap(valueAccessor()) : true;
    const initialSelector = typeof options === 'object' ? options.initialFocus : undefined;
    const controls = () => Array.from(element.querySelectorAll<HTMLElement>(
      'a[href],button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]',
    ));
    const focus = window.setTimeout(() => {
      const requested = initialSelector
        ? element.querySelector<HTMLElement>(initialSelector)
        : undefined;
      (requested || controls()[0])?.focus();
    }, 0);
    const trap = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const items = controls();
      const first = items[0];
      const last = items[items.length - 1];
      if (!first) {
        event.preventDefault();
        return;
      }
      if (
        event.shiftKey &&
        (document.activeElement === first || !element.contains(document.activeElement))
      ) {
        event.preventDefault();
        last.focus();
      } else if (
        !event.shiftKey &&
        (document.activeElement === last || !element.contains(document.activeElement))
      ) {
        event.preventDefault();
        first.focus();
      }
    };
    element.addEventListener('keydown', trap);
    ko.utils.domNodeDisposal.addDisposeCallback(element, () => {
      window.clearTimeout(focus);
      element.removeEventListener('keydown', trap);
      if (previous?.isConnected) previous.focus();
    });
  },
};
```

Add the shared CSS with current FluxPay tokens:

```css
.admin-list-surface { min-width: 0; padding: 22px; border: 1px solid var(--admin-card-border); border-radius: 16px; background: #fff; box-shadow: var(--admin-card-shadow); }
.review-list { display: grid; gap: 1px; overflow: hidden; border: 1px solid var(--line); border-radius: 12px; }
.review-list-row { display: grid; grid-template-columns: minmax(0,1.5fr) minmax(110px,.65fr) minmax(110px,.55fr) 24px; align-items: center; gap: 14px; width: 100%; min-height: 68px; padding: 12px 16px; background: #fff; color: var(--ink); text-align: left; }
.review-list-row:hover,.review-list-row:focus-visible { background: #f8faff; }
.admin-confirmation .admin-workflow-modal { display: grid; grid-template-rows: auto minmax(0,1fr) auto; width: min(1080px,calc(100vw - 40px)); max-height: 90dvh; overflow: hidden; padding: 0; }
.admin-modal-header { position: sticky; top: 0; z-index: 2; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 24px 28px 18px; border-bottom: 1px solid var(--line); background: #fff; }
.admin-modal-body { min-height: 0; overflow: auto; padding: 24px 28px; }
.admin-modal-footer { position: sticky; bottom: 0; z-index: 2; display: flex; justify-content: flex-end; gap: 10px; padding: 16px 28px; border-top: 1px solid var(--line); background: #fff; }
.admin-wide-table { min-width: 820px; }
.sticky-action { position: sticky; right: 0; z-index: 1; width: 54px; background: #fff; white-space: nowrap; }
```

Keep the already-corrected sidebar offset and removed environment badge. Do not restore `.admin-environment`.

- [ ] **Step 4: Run focused contracts and type checking**

Run: `node --test tests/admin-ui-contract.test.cjs tests/admin-navigation.test.cjs tests/experience.test.cjs tests/navigation.test.cjs`

Expected: PASS.

Run: `npm.cmd run typecheck`

Expected: PASS.

- [ ] **Step 5: Commit the foundation**

```powershell
git add frontend/fluxpay-ui/src/css/app.css frontend/fluxpay-ui/src/css/home.css frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/src/css/workspace.css frontend/fluxpay-ui/src/index.html frontend/fluxpay-ui/src/ts/services/admin-dialog.ts frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs frontend/fluxpay-ui/tests/admin-navigation.test.cjs frontend/fluxpay-ui/tests/experience.test.cjs frontend/fluxpay-ui/tests/navigation.test.cjs
git commit -m "fix: establish admin ui interaction foundation"
```

### Task 2: Make Overview metrics compact and never anonymously blank

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin.ts:43-52`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin.html:27-75`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-console.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs`

**Interfaces:**

- Consumes: `AdminMetric` plus per-source `loading`, `ready`, and `error` state.
- Produces: every rendered metric has boolean `pending` and `unavailable` fields and a labeled compact tile.

- [ ] **Step 1: Add failing compact-metric tests**

```js
test('overview metrics always expose boolean render state',async()=>{
  const {page,api}=overviewPage();
  for(const metric of page.metrics()){
    assert.equal(typeof metric.pending,'boolean');
    assert.equal(typeof metric.unavailable,'boolean');
  }
  api.adminKyc.resolve([]);api.complianceCases.resolve([]);api.listForAdmin.resolve({items:[],total:0});
  api.providers.resolve([]);api.routesAdmin.resolve([]);api.policies.resolve([]);
  await page.loadOverview();
  for(const metric of page.metrics()) assert.deepEqual([metric.pending,metric.unavailable],[false,false]);
  page.disconnected();
});

test('overview metric CSS is a compact single scrolling row',()=>{
  const css=read('css/admin-console.css');
  assert.match(css,/\.attention-metrics\s*\{[^}]*grid-auto-flow:\s*column[^}]*overflow-x:\s*auto/);
  assert.match(css,/\.attention-metric\s*\{[^}]*min-height:\s*80px[^}]*text-align:\s*center/);
  assert.match(css,/\.attention-metric-label\s*\{[^}]*white-space:\s*nowrap/);
});
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run: `node --test tests/admin-console.test.cjs tests/admin-ui-contract.test.cjs`

Expected: FAIL on missing ready-state booleans and compact row CSS.

- [ ] **Step 3: Implement complete metric state and compact markup**

Use this mapping in `admin.ts`:

```ts
const readyMetric = { ...metric, pending: false, unavailable: false };
return states.includes('error')
  ? { ...readyMetric, value: 'Unavailable', unavailable: true }
  : states.includes('loading')
    ? { ...readyMetric, value: 'Loading…', pending: true }
    : readyMetric;
```

Give the tile an explicit accessible label and named elements:

```html
<button type="button" class="attention-metric"
  data-bind="click:$parent.open,disable:unavailable||pending,attr:{'data-tone':tone,'aria-label':label+': '+value}">
  <strong class="attention-metric-value" data-bind="text:value"></strong>
  <span class="attention-metric-label" data-bind="text:label,title:label"></span>
</button>
```

Use a horizontally scrolling column grid, `80px` tile height, centered content, and 11–12px single-line labels. Keep source error rows labeled with Retry and do not render an extra card around `.source-health`.

- [ ] **Step 4: Verify Overview**

Run: `node --test tests/admin-console.test.cjs tests/admin-ui-contract.test.cjs`

Expected: PASS.

- [ ] **Step 5: Commit Overview**

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin.ts frontend/fluxpay-ui/src/ts/views/admin.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-console.test.cjs frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs
git commit -m "fix: compact admin overview metrics"
```

### Task 3: Convert KYC review into a stable queue and one modal workflow

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin-kyc.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-kyc.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-kyc.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-integration.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-binding-context.test.cjs`

**Interfaces:**

- Consumes: inherited `review`, `openReview`, `closeReview`, `documentPreview`, `openDocument`, `closeDocument`, and `decide`.
- Produces: `openReviewDocument(file, event)`, `closeReviewDocument()`, full-row review buttons, modal preview mode, and modal decision-review mode.

- [ ] **Step 1: Write failing KYC modal tests**

```js
test('KYC uses full-row controls and keeps every review action in one dialog',()=>{
  const html=read('ts/views/admin-kyc.html');
  assert.match(html,/class="review-list-row"[^>]*data-bind="[^"]*selectReview/);
  assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
  assert.match(html,/<!-- ko if:review -->[\s\S]*class="admin-confirmation"[\s\S]*adminDialog:/);
  assert.match(html,/<!-- ko if:reviewDecision -->[\s\S]*confirmDecision/);
  assert.match(html,/<!-- ko if:documentPreview -->[\s\S]*closeReviewDocument/);
  assert.ok(html.indexOf('sticky-decision-bar')>html.indexOf('admin-workflow-modal'));
});
```

Add a view-model test that stores a fake document trigger, calls `closeReviewDocument`, and verifies that focus returns through `requestAnimationFrame`.

- [ ] **Step 2: Run the KYC tests and verify failure**

Run: `node --test tests/admin-kyc.test.cjs tests/admin-integration.test.cjs tests/admin-binding-context.test.cjs`

Expected: FAIL because the queue is a table, details/actions are inline, and preview is a second dialog.

- [ ] **Step 3: Add document-mode state helpers**

```ts
private documentTrigger?: HTMLElement;

openReviewDocument = (file: any, event: Event) => {
  this.documentTrigger = event.currentTarget as HTMLElement;
  void this.openDocument(file);
};

closeReviewDocument = () => {
  this.closeDocument();
  window.requestAnimationFrame(() => this.documentTrigger?.focus());
};

closeReviewModal = () => {
  this.documentTrigger = undefined;
  this.closeReview();
};
```

- [ ] **Step 4: Replace the split workspace with a list and conditional modal modes**

The queue uses `<button type="button" class="review-list-row" data-bind="click:$parent.selectReview">` with applicant, age, status, and chevron spans.

Render the dialog only when `review` exists:

```html
<!-- ko if:review -->
<div class="admin-confirmation" role="dialog" aria-modal="true"
  aria-labelledby="kyc-record-heading"
  data-bind="adminDialog:{initialFocus:'#kyc-record-heading'}">
  <article class="panel admin-workflow-modal kyc-review-modal">
    <!-- ko ifnot:documentPreview -->
    <header class="admin-modal-header">
      <div><span class="eyebrow">KYC REVIEW</span><h2 id="kyc-record-heading" tabindex="-1" data-bind="text:review().fullName"></h2></div>
      <button class="close-button" aria-label="Close KYC review" title="Close" data-bind="click:closeReviewModal,disable:busy">×</button>
    </header>
    <div class="admin-modal-body kyc-review-body" data-bind="with:review">
      <p data-bind="text:email"></p>
      <h3>Customer-submitted information</h3>
      <dl class="detail-grid">
        <div><dt>Document type</dt><dd data-bind="text:docType"></dd></div>
        <div><dt>Document number</dt><dd class="mono" data-bind="text:$parent.maskDocument(docNumber)"></dd></div>
        <div><dt>Submitted</dt><dd data-bind="text:$parent.date(submittedAt)"></dd></div>
        <!-- ko if:decidedAt --><div><dt>Decided</dt><dd data-bind="text:$parent.date(decidedAt)"></dd></div><!-- /ko -->
      </dl>
      <!-- ko if:rejectReason --><div class="kyc-review-note"><strong>Reason shared with customer</strong><p data-bind="text:rejectReason"></p></div><!-- /ko -->
      <h3>Manual document checklist</h3>
      <ul><li>Confirm the name and identifier manually.</li><li>Confirm the document is readable and current.</li><li>Record the reason code for the decision.</li></ul>
      <h3>Evidence</h3>
      <div class="kyc-document-grid" data-bind="foreach:documents">
        <button type="button" class="kyc-document-card" data-bind="click:$parents[1].openReviewDocument">
          <strong data-bind="text:fileName"></strong><small data-bind="text:available?'Open original':'Original unavailable'"></small>
        </button>
      </div>
    </div>
    <!-- ko ifnot:reviewDecision -->
    <form class="admin-modal-footer sticky-decision-bar" data-bind="visible:review().status==='PENDING',submit:function(){prepareDecision();return false}">
      <label>Decision<select data-bind="value:decision"><option value="approve">Approve</option><option value="reject">Reject</option></select></label>
      <label>Reason code<select data-bind="options:reasonCodes[decision()],optionsText:'label',optionsValue:'value',value:reasonCode"></select></label>
      <label>Notes<textarea maxlength="500" rows="2" data-bind="textInput:notes"></textarea></label>
      <label class="checkbox-label"><input type="checkbox" data-bind="checked:reviewConsent,disable:decision()==='reject'" />Manual evidence review completed</label>
      <button class="pill dark" data-bind="disable:busy">Review decision</button>
    </form>
    <!-- /ko -->
    <!-- ko if:reviewDecision -->
    <section class="admin-modal-body" aria-live="polite">
      <h3 data-bind="text:reviewDecision()==='approve'?'Approve this KYC review?':'Reject this KYC review?'"></h3>
      <p class="preserve-text" data-bind="text:reviewReason"></p>
    </section>
    <footer class="admin-modal-footer">
      <button class="pill soft" data-bind="click:function(){reviewDecision('')},disable:busy">Back</button>
      <button class="pill dark" data-bind="click:confirmDecision,disable:busy,text:reviewDecision()==='approve'?'Approve':'Reject'"></button>
    </footer>
    <!-- /ko -->
    <!-- /ko -->
    <!-- ko if:documentPreview -->
    <header class="admin-modal-header"><button class="modal-back" data-bind="click:closeReviewDocument">← Back to review</button><h2 id="kyc-preview-title" data-bind="text:documentPreview().fileName"></h2></header>
    <div class="admin-modal-body">
      <p class="muted" data-bind="visible:previewLoading">Opening your document…</p>
      <p class="alert error" role="alert" data-bind="visible:previewError,text:previewError"></p>
      <!-- ko if:documentPreview().url -->
      <div class="kyc-preview-canvas">
        <!-- ko if:documentPreview().fileType==='application/pdf' --><div class="kyc-pdf-viewer" data-bind="kycPdfPreview:documentPreview().url"></div><!-- /ko -->
        <!-- ko if:documentPreview().fileType!=='application/pdf' --><img alt="Uploaded identity document" data-bind="attr:{src:documentPreview().url}" /><!-- /ko -->
      </div>
      <div class="kyc-preview-footer"><span>Read-only · Keep this document private</span><a class="pill soft" data-bind="attr:{href:documentPreview().url,download:documentPreview().fileName}">Download document</a></div>
      <p class="form-help">Use the page controls to read a PDF. You can also download the original file.</p>
      <!-- /ko -->
    </div>
    <!-- /ko -->
  </article>
</div>
<!-- /ko -->
```

Move the existing field and preview blocks without changing their API bindings. Remove the inline `.record-detail`, `.evidence-panel`, external `.sticky-decision-bar`, and second preview backdrop.

- [ ] **Step 5: Verify and commit KYC**

Run: `node --test tests/admin-kyc.test.cjs tests/admin-integration.test.cjs tests/admin-binding-context.test.cjs`

Expected: PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin-kyc.ts frontend/fluxpay-ui/src/ts/views/admin-kyc.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-kyc.test.cjs frontend/fluxpay-ui/tests/admin-integration.test.cjs frontend/fluxpay-ui/tests/admin-binding-context.test.cjs
git commit -m "fix: move KYC review into a modal workflow"
```

### Task 4: Convert Compliance to a modal and add payment copy plus Copilot handoff

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin-compliance.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-compliance.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-integration.test.cjs`

**Interfaces:**

- Consumes: `ComplianceWorkspace.openCase`, `closeCase`, `prepareDecision`, `confirm`, `selectedCase`, `notice`, and `error`.
- Produces: `copyPaymentId()`, `confirmDecision()`, full-row case buttons, and one modal containing details, confirmation, and Copilot navigation.

- [ ] **Step 1: Add failing copy and modal tests**

```js
test('compliance payment copy reports success and keeps the visible ID',async()=>{
  const writes=[];
  const {vm}=complianceViewModel({clipboard:{writeText:async value=>writes.push(value)}});
  vm.workspace.selectedCase(makeCase());
  await vm.copyPaymentId();
  assert.deepEqual(writes,[paymentId]);
  assert.match(vm.workspace.notice(),/copied/i);
});

test('compliance renders a full-row queue and one modal workflow',()=>{
  const html=read('ts/views/admin-compliance.html');
  assert.match(html,/class="review-list-row"/);
  assert.match(html,/aria-label="Copy payment ID"/);
  assert.match(html,/Ask Copilot about this case/);
  assert.match(html,/class="admin-modal-footer"[\s\S]*Review decision/);
  assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
});
```

Also test a missing `navigator.clipboard`: the selected case remains set, the ID remains visible in markup, and `workspace.error()` instructs manual copying.

- [ ] **Step 2: Run and verify failure**

Run: `node --test tests/admin-review-workspaces.test.cjs tests/admin-integration.test.cjs`

Expected: FAIL on missing copy behavior and inline split panes.

- [ ] **Step 3: Implement view-model wrappers**

```ts
copyPaymentId = async () => {
  const paymentId = this.workspace.selectedCase()?.paymentId;
  if (!paymentId) return;
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
    await navigator.clipboard.writeText(paymentId);
    this.workspace.notice('Payment ID copied.');
  } catch {
    this.workspace.error('Unable to copy the payment ID. Select the visible ID and copy it manually.');
  }
};

confirmDecision = async () => {
  await this.workspace.confirm();
  if (!this.workspace.error() && this.workspace.selectedCase()?.status !== 'OPEN') {
    this.workspace.closeCase();
  }
};
```

- [ ] **Step 4: Build the single Compliance modal**

Use the shared review-list row button. Under `<!-- ko if:selectedCase -->`, render one `.admin-confirmation` and `.admin-workflow-modal`. Keep the visible payment ID beside:

```html
<button type="button" class="icon-action" aria-label="Copy payment ID" title="Copy payment ID" data-bind="click:$parents[1].copyPaymentId">
  <i class="fa-solid fa-copy" aria-hidden="true"></i>
</button>
```

Place `Ask Copilot about this case`, Close, decision controls, and confirmation controls within the modal. Use `confirmation()` to switch the modal body/footer rather than opening a second `.admin-confirmation`. Call `$parents[1].confirmDecision` for the final mutation.

- [ ] **Step 5: Verify and commit Compliance**

Run: `node --test tests/admin-review-workspaces.test.cjs tests/admin-integration.test.cjs`

Expected: PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin-compliance.ts frontend/fluxpay-ui/src/ts/views/admin-compliance.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs frontend/fluxpay-ui/tests/admin-integration.test.cjs
git commit -m "fix: redesign compliance review modal"
```

### Task 5: Convert Support to explicit-selection modal UX

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin-tickets.ts:75-120`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-tickets.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-integration.test.cjs`

**Interfaces:**

- Consumes: `selectedTicket`, `selectedNextAction`, `assignToMe`, `runNextAction`, and existing pagination/filter state.
- Produces: no initial selection, retained explicit/deep-link selection, `closeTicket()`, and one ticket modal.

- [ ] **Step 1: Change tests to require explicit selection**

```js
test('support initial load does not open the first ticket',async()=>{
  const {page,finish}=ticketWorkspaceWithDeferredLoad();
  const pending=page.loadTickets();
  finish({items:[ticket({id:'first'}),ticket({id:'second'})],total:2});
  await pending;
  assert.equal(page.selectedTicket(),undefined);
  page.selectTicket(page.visibleTickets()[1]);
  assert.equal(page.selectedTicket().id,'second');
  page.closeTicket();
  assert.equal(page.selectedTicket(),undefined);
});
```

Update the refresh test so it explicitly selects a ticket before refresh and expects `undefined` when that selected ticket disappears. Keep the deep-link test expecting its requested ticket.

- [ ] **Step 2: Run and verify failure**

Run: `node --test tests/admin-review-workspaces.test.cjs tests/admin-integration.test.cjs`

Expected: FAIL because `fetchTickets` selects `visibleTickets()[0]`.

- [ ] **Step 3: Implement explicit selection state**

Replace the final fallback in `fetchTickets` with:

```ts
const currentId = this.selectedTicket()?.id;
const retained = currentId && items.find((item) => item.id === currentId);
this.selectedTicket(retained || undefined);
```

Keep the preferred deep-link branch before this code. Add:

```ts
closeTicket = () => {
  if (!this.busy()) this.selectedTicket(undefined);
};
```

- [ ] **Step 4: Replace the ticket table/detail panes with list rows and one modal**

Each `.review-list-row` shows subject, assignment, age, status, and chevron. The modal header shows subject/status; the body contains the existing customer statement, user, payment, assignee, created, updated, and age fields; the footer contains Assign to me and the current next action. Remove the external sticky action bar.

- [ ] **Step 5: Verify and commit Support**

Run: `node --test tests/admin-review-workspaces.test.cjs tests/admin-integration.test.cjs`

Expected: PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin-tickets.ts frontend/fluxpay-ui/src/ts/views/admin-tickets.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs frontend/fluxpay-ui/tests/admin-integration.test.cjs
git commit -m "fix: require explicit support ticket selection"
```

### Task 6: Repair Providers and move edit/review into one modal

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/views/admin-providers.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-routing-console.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-binding-context.test.cjs`

**Interfaces:**

- Consumes: existing `providerForm`, `saveReview`, `providerChanges`, `requestProviderSave`, `confirmProviderSave`, `cancelSaveReview`, and `closeProviderEditor` state.
- Produces: conditionally rendered provider modal with edit and review modes; no hidden descendant bindings.

- [ ] **Step 1: Add a failing null-root provider binding regression**

```js
test('provider modal bindings work with a null Oracle JET outer root',()=>{
  const html=read('ts/views/admin-providers.html');
  assert.match(html,/<!-- ko if:providerForm -->/);
  assert.doesNotMatch(html,/visible:providerForm/);
  assert.doesNotMatch(html,/\$root\.environment/);
  assert.match(html,/adminDialog:\{initialFocus:'#provider-editor-title'\}/);
  assert.match(html,/saveReview\(\)!=='provider'/);
  assert.match(html,/saveReview\(\)==='provider'/);
});
```

Retain the Knockout binding parser test and evaluate the environment text from a workspace child context with `$parent.environment.label`.

- [ ] **Step 2: Run and verify the exact current failure**

Run: `node --test tests/admin-routing-console.test.cjs tests/admin-binding-context.test.cjs`

Expected: FAIL because the editor uses `visible:providerForm` and `$root.environment.label`.

- [ ] **Step 3: Implement the provider modal state machine**

Keep the provider list full width. Change Edit to:

```html
<button type="button" class="icon-action" aria-label="Edit provider" title="Edit provider" data-bind="click:$parent.editProvider">
  <i class="fa-solid fa-pen-to-square" aria-hidden="true"></i>
</button>
```

After the list, render `<!-- ko if:providerForm -->` around one dialog. The edit state is guarded by `if:saveReview()!=='provider'`; the review state is guarded by `if:saveReview()==='provider'`. Both live inside the same `.admin-workflow-modal`. Bind environment text to `$parent.environment.label`, Back to `cancelSaveReview`, Apply to `confirmProviderSave`, and Close to `closeProviderEditor`.

- [ ] **Step 4: Verify stale retry remains inside the modal**

Run: `node --test tests/admin-routing-console.test.cjs tests/admin-binding-context.test.cjs`

Expected: PASS, including the existing stale-version test with `providerForm() === true` and preserved entered values.

- [ ] **Step 5: Commit Providers**

```powershell
git add frontend/fluxpay-ui/src/ts/views/admin-providers.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-routing-console.test.cjs frontend/fluxpay-ui/tests/admin-binding-context.test.cjs
git commit -m "fix: repair provider modal workflow"
```

### Task 7: Make Payout Routes horizontally scrollable and modal-edited

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/views/admin-routes.html:51-289`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-routing-console.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-binding-context.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/routing.test.cjs`

**Interfaces:**

- Consumes: existing route filter, edit, review, validation, stale recovery, matrix, compare, and preview behavior.
- Produces: `.route-catalogue-table`, sticky icon action, and one conditional route edit/review modal.

- [ ] **Step 1: Add failing responsive-route tests**

```js
test('route catalogue scrolls horizontally and keeps a labeled edit icon',()=>{
  const html=read('ts/views/admin-routes.html');
  const css=read('css/admin-console.css');
  assert.match(html,/class="dense-table admin-wide-table route-catalogue-table"/);
  assert.match(html,/class="sticky-action"/);
  assert.match(html,/aria-label="Edit payout route"/);
  assert.match(css,/\.route-catalogue-table\s*\{[^}]*min-width:\s*1320px/);
  assert.doesNotMatch(html,/class="text-button"[^>]*editRoute/);
});
```

Extend the binding-context test to keep the already-correct `$parent.workspace.routeProtected`, `providerDisplayName`, and `editRoute` expressions executable with a null outer root.

- [ ] **Step 2: Run and verify failure**

Run: `node --test tests/admin-routing-console.test.cjs tests/admin-binding-context.test.cjs tests/routing.test.cjs`

Expected: FAIL because the table collapses and the editor is inline.

- [ ] **Step 3: Implement wide catalogue and sticky action**

Wrap the catalogue table in its existing `.table-scroll`, add the required classes, add `class="sticky-action"` to its action header/cells, and replace Edit text with the existing pen icon. Add:

```css
.route-catalogue-table { min-width: 1320px; }
.route-catalogue-table :is(.mono,.sticky-action) { overflow-wrap: normal; word-break: normal; }
```

- [ ] **Step 4: Move route editing and review into one conditional modal**

Remove the inline empty editor card. Render the dialog only under `<!-- ko if:workspace.routeForm -->`. Inside `with:workspace`, show the form for `saveReview()!=='route'` and the diff for `saveReview()==='route'`. Use `$parent.environment.label`, `closeRouteEditor`, `cancelSaveReview`, and `confirmRouteSave`. Keep matrix, compare, and preview markup unchanged.

- [ ] **Step 5: Verify and commit Routes**

Run: `node --test tests/admin-routing-console.test.cjs tests/admin-binding-context.test.cjs tests/routing.test.cjs`

Expected: PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/views/admin-routes.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-routing-console.test.cjs frontend/fluxpay-ui/tests/admin-binding-context.test.cjs frontend/fluxpay-ui/tests/routing.test.cjs
git commit -m "fix: make payout routes responsive"
```

### Task 8: Normalize Policy Library into one active modal layer

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts:84-109,212-214,278-315`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-policies.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-binding-context.test.cjs`

**Interfaces:**

- Consumes: existing `policy`, `policyView`, editor, review, chunk, guidance, case-viewer, and confirmation observables.
- Produces: `policyModalMode` and a full-width scrollable policy list with exactly one active modal surface.

- [ ] **Step 1: Add failing policy modal-state tests**

```js
test('policy modal mode selects exactly one active surface',()=>{
  const {page}=complianceWorkspace();
  page.policy({...policy});
  assert.equal(page.policyModalMode(),'detail');
  page.openPolicyEdit(policy);
  assert.equal(page.policyModalMode(),'edit');
  page.policyEdit().title('Updated title');
  page.requestPolicyEditSave();
  assert.equal(page.policyModalMode(),'review');
  page.policyChangeReview(false);
  assert.equal(page.policyModalMode(),'edit');
  page.dispose();
});

test('policy table is wide, scrollable and uses accessible icon actions',()=>{
  const html=read('ts/views/admin-policies.html');
  assert.match(html,/class="dense-table admin-wide-table policy-library-table"/);
  assert.match(html,/aria-label="View policy"/);
  assert.doesNotMatch(html,/\$root\.environment/);
});
```

- [ ] **Step 2: Run and verify failure**

Run: `node --test tests/admin-policy-copilot.test.cjs tests/admin-binding-context.test.cjs`

Expected: FAIL because policy details are inline and edit/review dialogs can coexist.

- [ ] **Step 3: Add deterministic policy modal mode**

```ts
policyModalMode = ko.pureComputed<
  ''|'detail'|'edit'|'review'|'chunk-edit'|'guidance-edit'|'case-view'|'confirmation'
>(() => {
  if (this.policyChangeReview()) return 'review';
  if (this.policyEdit()) return 'edit';
  if (this.chunkEdit()) return 'chunk-edit';
  if (this.guidanceEdit()) return 'guidance-edit';
  if (this.guidanceCaseViewer()) return 'case-view';
  if (this.confirmation() && this.policy()) return 'confirmation';
  return this.policy() ? 'detail' : '';
});
```

Reset `policyView('policy')` when opening or closing a policy so a newly opened document never starts in stale Advanced mode.

- [ ] **Step 4: Rebuild Policy Library presentation**

Make the list full width inside `.table-scroll`; use `.policy-library-table { min-width: 860px; }` and a sticky icon action labeled `View policy`. Replace the inline `.configuration-editor` and `.configuration-advanced` with modal sections selected by `policyModalMode()`.

The detail and Advanced modes share one outer `.admin-workflow-modal`. Edit, review, chunk edit, guidance edit, completed-case view, and confirmation each replace that modal content. Bind environment copy through `$parent.environment.label` from the workspace context. Draft editor/view/publish dialogs remain conditional and cannot be opened while the policy modal covers the page.

- [ ] **Step 5: Verify and commit Policies**

Run: `node --test tests/admin-policy-copilot.test.cjs tests/admin-binding-context.test.cjs tests/compliance.test.cjs`

Expected: PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts frontend/fluxpay-ui/src/ts/views/admin-policies.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs frontend/fluxpay-ui/tests/admin-binding-context.test.cjs frontend/fluxpay-ui/tests/compliance.test.cjs
git commit -m "fix: normalize policy library modal workflow"
```

### Task 9: Redesign Compliance Copilot as a cited current-exchange chat

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin-copilot.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-copilot.html`
- Modify: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs`

**Interfaces:**

- Consumes: `ComplianceWorkspace.loadCases`, `cases`, `question`, `answeredQuestion`, `answer`, `copilotPaymentId`, and cited `ask()`.
- Produces: `selectedCaseId`, `caseOptions`, `manualPaymentContext`, `selectCaseContext()`, and a chat-like current exchange.

- [ ] **Step 1: Add failing context and composer tests**

```js
test('Copilot loads case choices and applies selected payment context',async()=>{
  const {page,calls}=copilotPage({params:{}},{complianceCases:async()=>[openCase]});
  await page.ready;
  assert.equal(page.caseOptions().length,2);
  page.selectedCaseId(openCase.id);
  await page.selectCaseContext();
  assert.equal(page.workspace.copilotPaymentId(),openCase.paymentId);
  assert.match(page.workspace.question(),/Risk level: HIGH/);
  assert.ok(calls.some(call=>call[0]==='complianceCases'));
  page.disconnected();
});

test('successful cited ask clears the composer but preserves the rendered question',async()=>{
  const {page}=copilotPage({params:{}});
  await page.ready;
  page.workspace.question('When is review required?');
  await page.askCited();
  assert.equal(page.workspace.question(),'');
  assert.equal(page.workspace.answeredQuestion(),'When is review required?');
  page.disconnected();
});
```

Keep the existing provider-error test and require the composer question to remain on failure.

- [ ] **Step 2: Run and verify failure**

Run: `node --test tests/admin-policy-copilot.test.cjs`

Expected: FAIL because no case list loads and the question is not cleared after success.

- [ ] **Step 3: Implement context selection and current-exchange submission**

```ts
selectedCaseId = ko.observable('');
manualPaymentContext = ko.observable(false);
caseOptions = ko.pureComputed(() => [
  { id: '', label: 'No case context' },
  ...this.workspace.cases().map(item => ({
    id: item.id,
    label: `${item.risk} risk · ${item.paymentId}`,
  })),
]);

selectCaseContext = async () => {
  const id = this.selectedCaseId();
  if (!id) {
    this.caseContext(undefined);
    this.workspace.copilotPaymentId('');
    return;
  }
  await this.workspace.run(async () => {
    const item = await fluxApi.complianceCase(id);
    this.caseContext(item);
    this.workspace.copilotPaymentId(item.paymentId);
    this.workspace.question(copilotQuestionForCase(item));
  });
};

askCited = async () => {
  this.workspace.liveResponse(false);
  await this.workspace.ask();
  if (this.workspace.answer()) this.workspace.question('');
  return false;
};
```

During activation, call `workspace.loadCases()` before resolving a deep-linked case. A payment-only deep link opens the manual payment field. Preserve the current no-context option.

- [ ] **Step 4: Build the familiar chat layout**

Replace `.copilot-workspace` with one `.copilot-chat` surface containing:

```html
<label class="copilot-context-select">Case / payment context
  <select data-bind="options:$parent.caseOptions,optionsText:'label',optionsValue:'id',value:$parent.selectedCaseId,event:{change:$parent.selectCaseContext}"></select>
</label>
<main class="copilot-transcript" aria-live="polite">
  <!-- ko ifnot:answer --><div class="empty-state"><h2>Ask a policy question</h2><p>Answers include the policy sources returned by FluxPay.</p></div><!-- /ko -->
  <!-- ko if:answeredQuestion --><article class="chat-message user"><span>You</span><p data-bind="text:answeredQuestion"></p></article><!-- /ko -->
  <!-- ko if:answer -->
  <article class="chat-message assistant">
    <span>Compliance Copilot</span><p class="preserve-text" data-bind="policyAnswer:answer().answer"></p>
    <p class="subtle-note">Review the cited policies before taking any administrative action.</p>
    <!-- ko if:answer().sources.length -->
    <section class="copilot-sources" aria-label="Source policies" data-bind="foreach:answer().sources">
      <button type="button" class="source-row" data-bind="click:$parents[1].openSource">
        <strong data-bind="text:title"></strong><small data-bind="text:'Policy '+policyDocumentId+' · chunk '+chunkNumber"></small><blockquote data-bind="text:excerpt"></blockquote>
      </button>
    </section>
    <!-- /ko -->
    <!-- ko ifnot:answer().sources.length --><p class="empty-state">No policy sources returned. Do not rely on this answer for a decision.</p><!-- /ko -->
  </article>
  <!-- /ko -->
</main>
<form class="copilot-chat-composer" data-bind="submit:$parent.askCited">
  <label class="sr-only" for="copilot-question">Policy question</label>
  <textarea id="copilot-question" rows="2" required data-bind="textInput:question" placeholder="Ask about the applicable policy…"></textarea>
  <button class="pill dark" data-bind="disable:busy">Send</button>
</form>
```

Put the optional manual payment ID inside a disclosure controlled by `manualPaymentContext`. Keep Advisory only visible and keep source clicks routed to Policy Library.

- [ ] **Step 5: Verify and commit Copilot**

Run: `node --test tests/admin-policy-copilot.test.cjs tests/admin-integration.test.cjs`

Expected: PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin-copilot.ts frontend/fluxpay-ui/src/ts/views/admin-copilot.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs frontend/fluxpay-ui/tests/admin-integration.test.cjs
git commit -m "fix: redesign compliance copilot as chat"
```

### Task 10: Complete cross-route accessibility and responsive regression coverage

**Files:**

- Modify: `frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-integration.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-binding-context.test.cjs`
- Modify only if a regression is found: affected frontend source from Tasks 1–9

**Interfaces:**

- Consumes: all shared classes and page workflows from Tasks 1–9.
- Produces: one cross-route acceptance gate covering modal-only actions, accessible icons, null-root bindings, and responsive tables.

- [ ] **Step 1: Add the cross-route acceptance test**

```js
test('admin record workflows are modal-only and icon actions are named',()=>{
  const reviewRoutes=['admin-kyc','admin-compliance','admin-tickets'];
  for(const route of reviewRoutes){
    const html=read(`ts/views/${route}.html`);
    assert.match(html,/class="review-list-row"/);
    assert.match(html,/class="admin-confirmation"/);
    assert.match(html,/class="admin-workflow-modal/);
    assert.doesNotMatch(html,/class="record-detail"|class="evidence-panel"/);
    assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
  }
  for(const route of ['admin-providers','admin-routes','admin-policies']){
    const html=read(`ts/views/${route}.html`);
    for(const tag of html.matchAll(/<button[^>]*class="[^"]*icon-action[^"]*"[^>]*>/g)){
      assert.match(tag[0],/aria-label="[^"]+"/);
      assert.match(tag[0],/title="[^"]+"/);
    }
  }
});
```

- [ ] **Step 2: Run every frontend test**

Run: `npm.cmd test`

Expected: all tests PASS; no old three-pane/Open-button assertions remain.

- [ ] **Step 3: Run static and production-build verification**

Run: `npm.cmd run typecheck`

Expected: PASS.

Run: `npm.cmd run build`

Expected: Oracle JET build completes successfully.

- [ ] **Step 4: Verify repository boundaries**

Run: `git diff --check`

Expected: no whitespace errors.

Run: `git diff -- backend`

Expected: no output.

Run: `rg -n "^(<<<<<<<|=======|>>>>>>>)" frontend/fluxpay-ui docs/superpowers`

Expected: no output.

- [ ] **Step 5: Smoke-test the served console after rebuild**

Wait for the existing server to rebuild, then request:

```powershell
Invoke-WebRequest 'http://localhost:8000/?ojr=admin' -UseBasicParsing
```

Expected: HTTP 200 and served assets containing the new shared classes.

Using the authorized local administrator account, inspect all eight admin routes at desktop and narrow widths. Verify Overview metrics, empty queues, populated row selection, modal close/focus, provider/route/policy edit-review steps, horizontal scrolling, payment copy, Compliance-to-Copilot navigation, and cited Copilot response. If browser automation remains unavailable, record that exact limitation and do not claim visual verification.

- [ ] **Step 6: Commit final regression adjustments if Step 2–5 required any**

```powershell
git add frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs frontend/fluxpay-ui/tests/admin-integration.test.cjs frontend/fluxpay-ui/tests/admin-binding-context.test.cjs frontend/fluxpay-ui/src
git commit -m "test: lock admin console ui remediation"
```

Skip this commit when verification required no file changes.
