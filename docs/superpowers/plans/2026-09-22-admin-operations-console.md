# FluxPay Admin Operations Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current tabbed administrator page with a frontend-only, risk-prioritized operations console that uses the existing FluxPay APIs, authentication flow, and `ADMIN` role unchanged.

**Architecture:** Keep Oracle JET routing and Knockout view models, but split the oversized admin module into one route per operational workspace. Pure TypeScript helpers own environment detection, prioritization, change diffs, overview derivation, and route analysis; the existing `RoutingWorkspace` and `ComplianceWorkspace` continue to own their current mutations. The root shell supplies the persistent administrator sidebar while each page renders one of the approved review, configuration, or Copilot layouts.

**Tech Stack:** Oracle JET 16.1, Knockout, TypeScript 5.3, semantic HTML, CSS custom properties, Node's built-in test runner.

**Spec:** `docs/superpowers/specs/2026-09-22-admin-operations-console-design.md`

## Global Constraints

- Only `frontend/fluxpay-ui`, documentation, and local development metadata may change.
- Do not modify backend Java, backend tests, configuration, database migrations, API routes, or API payload shapes.
- Do not change authentication, JWT handling, session restoration, backend authorization, or the existing `ADMIN` role.
- Keep `admin` as the administrator landing and account route.
- Omit the Governance group and Audit Log completely; do not render a disabled or unavailable message for either.
- `window.FLUXPAY_ENVIRONMENT` accepts `PRODUCTION`, `STAGING`, and `SANDBOX` case-insensitively and affects display context only, never API routing or permissions.
- Preserve `window.FLUXPAY_API_URL`, same-origin `/api`, and the existing local `API_PROXY` behavior.
- Do not claim dual approval, step-up authentication, persisted versions, policy publication state, KYC OCR mismatch results, support SLA, immutable audit data, or authoritative route simulation.
- Do not expose provider, route, policy, or compliance-case deletion controls.
- Do not add an npm dependency.
- Keep the existing FluxPay tokens and theme; add admin-scoped styling with no gradients, decorative charts, excessive rounded cards, or color-only statuses.
- Every administrator template must retain explicit signed-out and `Administrator access required` gates and render its operational content only while `session.isAdmin()` is true.
- Preserve the existing `401` token-clearing/expiry event; show the access gate for a non-admin session, retain input and selection on network/`5xx` errors, and use the existing provider/route `409` stale recovery without automatic retry.
- Every queue or configuration mutation must preserve entered values when a request fails; provider and route stale-version recovery must remain intact.
- Announce loading and success updates with `role="status" aria-live="polite"`, announce errors with `role="alert"`, and keep status text visible alongside every color or icon.
- Final verification must include an empty `git diff -- backend`.

## Review Focus

- Missing or invalid timestamps must sort after valid urgent records and must never be labeled as older than 24 hours; Task 1 adds deterministic tests.
- One failed or late Overview request must not erase successful sections or repopulate data after logout/disposal; Task 9 adds partial-failure and epoch tests.
- Route preview boundaries, missing providers, unknown rails, archived records, and non-numeric amounts must produce explicit reasons without selecting a winner; Task 6 adds table-driven tests.
- Provider and route stale conflicts must preserve form entries while rebasing to the newest server version or blocking a removed record; Task 5 retains and extends the existing conflict tests.
- Customer or signed-out deep links must not gain administrator data, and the environment value must never change the API base URL; Tasks 2, 9, and 10 pin those boundaries.

## File Structure

### New production files

- `frontend/fluxpay-ui/src/ts/services/admin-console.ts` — shared environment, age, queue-priority, reason, masking, and field-diff functions.
- `frontend/fluxpay-ui/src/ts/services/admin-overview.ts` — pure derivation of attention metrics and prioritized Overview rows.
- `frontend/fluxpay-ui/src/ts/services/route-analysis.ts` — corridor matrix, provider comparison, and non-authoritative eligibility evaluation.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-kyc.ts` and `src/ts/views/admin-kyc.html` — KYC review workspace.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-compliance.ts` and `src/ts/views/admin-compliance.html` — compliance review workspace.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-providers.ts` and `src/ts/views/admin-providers.html` — provider configuration workspace.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-routes.ts` and `src/ts/views/admin-routes.html` — route catalogue, matrix, comparison, and preview workspace.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-policies.ts` and `src/ts/views/admin-policies.html` — policy configuration workspace.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-copilot.ts` and `src/ts/views/admin-copilot.html` — cited, advisory Copilot workspace.
- `frontend/fluxpay-ui/src/css/admin-console.css` — styles scoped beneath `.admin-shell`.
- `frontend/fluxpay-ui/tests/helpers/load-typescript.cjs` — shared CommonJS test loader for TypeScript modules.
- `frontend/fluxpay-ui/tests/admin-console.test.cjs` — pure helper, environment, and overview tests.
- `frontend/fluxpay-ui/tests/admin-kyc.test.cjs` — KYC view-model and template tests.
- `frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs` — compliance and support workspace tests.
- `frontend/fluxpay-ui/tests/admin-routing-console.test.cjs` — configuration diff and route-analysis tests.
- `frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs` — policy and cited-Copilot tests.
- `frontend/fluxpay-ui/tests/admin-integration.test.cjs` — final routes, shell, destructive-control, accessibility, and backend-boundary source checks.

### Existing files to modify

- `frontend/fluxpay-ui/src/ts/services/flux-api.ts` — add precise frontend response types and make KYC page size configurable without changing an endpoint.
- `frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts` — add reason-code composition, risk-first ordering, policy change review, and guidance confirmation while retaining current API calls.
- `frontend/fluxpay-ui/src/ts/services/routing-workspace.ts` — add client-side provider/route change review while retaining current validation and stale recovery.
- `frontend/fluxpay-ui/src/ts/viewModels/admin.ts` and `src/ts/views/admin.html` — replace the tab host with the Overview route.
- `frontend/fluxpay-ui/src/ts/viewModels/admin-tickets.ts` and `src/ts/views/admin-tickets.html` — convert the support table to the shared review workspace.
- `frontend/fluxpay-ui/src/ts/appController.ts` — register the eight routes and expose grouped administrator navigation and environment state.
- `frontend/fluxpay-ui/src/index.html` — host the persistent labeled administrator sidebar and load `admin-console.css`.
- `frontend/fluxpay-ui/tests/admin-navigation.test.cjs`, `navigation.test.cjs`, and `experience.test.cjs` — replace obsolete “no sidebar” expectations with admin-only sidebar assertions.
- `frontend/fluxpay-ui/tests/compliance.test.cjs` and `routing.test.cjs` — point template assertions at the focused pages and assert that destructive controls are absent.

---

### Task 1: Add typed admin contracts and pure console helpers

**Files:**

- Create: `frontend/fluxpay-ui/tests/helpers/load-typescript.cjs`
- Create: `frontend/fluxpay-ui/src/ts/services/admin-console.ts`
- Create: `frontend/fluxpay-ui/tests/admin-console.test.cjs`
- Modify: `frontend/fluxpay-ui/src/ts/services/flux-api.ts:67-119`

**Interfaces:**

- Consumes: existing `ComplianceCase`, `TransferProvider`, and `TransferRoute` interfaces from `flux-api.ts`.
- Produces: `resolveAdminEnvironment`, `isOlderThanHours`, `prioritizeKycReviews`, `prioritizeComplianceCases`, `prioritizeSupportTickets`, `composeDecisionReason`, `maskIdentifier`, `diffFields`, `nextTicketAction`, and `focusRecordHeading`.
- Produces: typed `KycAdminRow`, `KycDocument`, `TicketResponse`, and `PageResponse<T>` contracts for later view models.

- [ ] **Step 1: Add the shared TypeScript test loader**

```js
// tests/helpers/load-typescript.cjs
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const sourceRoot = path.join(__dirname, '../../src');

function compile(relativePath) {
  return ts.transpileModule(fs.readFileSync(path.join(sourceRoot, relativePath), 'utf8'), {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}
  }).outputText;
}

function load(relativePath, dependencies = {}, globals = {}) {
  const module={exports:{}};
  const context = {
    module,
    exports: module.exports,
    require(name) {
      if (Object.prototype.hasOwnProperty.call(dependencies, name)) return dependencies[name];
      throw new Error(`Missing test dependency: ${name}`);
    },
    ...globals
  };
  vm.runInNewContext(compile(relativePath), context);
  return context.module.exports;
}

module.exports = {compile, load};
```

- [ ] **Step 2: Write failing tests for environment parsing, priority, age, reasons, masking, diffs, ticket actions, and typed KYC pagination**

```js
// tests/admin-console.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {load} = require('./helpers/load-typescript.cjs');

const helpers = load('ts/services/admin-console.ts');
const now = Date.parse('2026-09-22T12:00:00Z');

test('environment values are case-insensitive and hostname fallback is deterministic', () => {
  assert.equal(helpers.resolveAdminEnvironment('production', 'localhost').name, 'PRODUCTION');
  assert.equal(helpers.resolveAdminEnvironment(undefined, '127.0.0.1').name, 'SANDBOX');
  assert.equal(helpers.resolveAdminEnvironment('unknown', 'payments-uat.fluxpay.test').name, 'STAGING');
  assert.equal(helpers.resolveAdminEnvironment('', 'admin.fluxpay.example').name, 'PRODUCTION');
});

test('invalid timestamps sort last and never count as older than 24 hours', () => {
  const rows = helpers.prioritizeKycReviews([
    {applicationId: 'invalid', submittedAt: 'not-a-date', documents: [{available: true}]},
    {applicationId: 'old', submittedAt: '2026-09-20T00:00:00Z', documents: [{available: true}]},
    {applicationId: 'missing-doc', submittedAt: '2026-09-22T10:00:00Z', documents: [{available: false}]}
  ]);
  assert.deepEqual(Array.from(rows, row => row.applicationId), ['missing-doc', 'old', 'invalid']);
  assert.equal(helpers.isOlderThanHours('not-a-date', 24, now), false);
  assert.equal(helpers.isOlderThanHours('2026-09-20T00:00:00Z', 24, now), true);
});

test('compliance and support queues apply approved priority orders', () => {
  const cases = helpers.prioritizeComplianceCases([
    {id: 'low', risk: 'LOW', reviewExpiresAt: null, createdAt: '2026-09-20T00:00:00Z'},
    {id: 'high-later', risk: 'HIGH', reviewExpiresAt: '2026-09-23T00:00:00Z', createdAt: '2026-09-20T00:00:00Z'},
    {id: 'high-sooner', risk: 'HIGH', reviewExpiresAt: '2026-09-22T13:00:00Z', createdAt: '2026-09-21T00:00:00Z'}
  ]);
  assert.deepEqual(Array.from(cases, item => item.id), ['high-sooner', 'high-later', 'low']);
  const tickets = helpers.prioritizeSupportTickets([
    {id: 'closed', status: 'CLOSED', assigneeAdminId: null, createdAt: '2026-09-19T00:00:00Z'},
    {id: 'assigned', status: 'OPEN', assigneeAdminId: 'admin', createdAt: '2026-09-20T00:00:00Z'},
    {id: 'unassigned', status: 'OPEN', assigneeAdminId: null, createdAt: '2026-09-21T00:00:00Z'}
  ]);
  assert.deepEqual(Array.from(tickets, item => item.id), ['unassigned', 'assigned', 'closed']);
});

test('decision composition, masking, diffs, and next actions stay explicit', () => {
  assert.equal(helpers.composeDecisionReason('DOCUMENT_UNREADABLE', 'Please upload all edges.'), 'DOCUMENT_UNREADABLE — Please upload all edges.');
  assert.throws(() => helpers.composeDecisionReason('', 'note'), /reason code/i);
  assert.throws(() => helpers.composeDecisionReason('OTHER', 'x'.repeat(500)), /500 characters/i);
  assert.equal(helpers.maskIdentifier('ABCDE1234'), '••••1234');
  assert.equal(helpers.maskIdentifier('123'), '••••');
  assert.deepEqual(
    JSON.parse(JSON.stringify(helpers.diffFields(
      {active: true, providerName: 'Wise'},
      {active: false, providerName: 'Wise'},
      {active: 'Active', providerName: 'Provider name'},
      ['providerName', 'active']
    ))),
    [{field: 'active', label: 'Active', before: 'Yes', after: 'No'}]
  );
  assert.deepEqual(helpers.nextTicketAction('IN_PROGRESS'), {status: 'RESOLVED', label: 'Resolve ticket'});
  assert.equal(helpers.nextTicketAction('UNKNOWN'), null);
});

test('keyboard activation moves focus to the selected record heading', () => {
  let focused=false;
  const runtime={requestAnimationFrame:callback=>callback(),document:{getElementById:id=>id==='record-heading'?{focus:()=>{focused=true;}}:null}};
  helpers.focusRecordHeading({detail:0},'record-heading',runtime);
  assert.equal(focused,true);
  focused=false;helpers.focusRecordHeading({detail:1},'record-heading',runtime);assert.equal(focused,false);
});
```

Add a request fixture in the same test file which loads `flux-api.ts`, calls `adminKyc('PENDING', 0, 100)`, and asserts the exact URL `/api/admin/kyc/applications?status=PENDING&page=0&size=100`.

- [ ] **Step 3: Run the focused tests and verify the missing modules and exports fail**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-console.test.cjs`

Expected: FAIL because `admin-console.ts` and the typed three-argument `adminKyc` implementation do not exist.

- [ ] **Step 4: Add precise frontend API contracts without changing request or response shapes**

```ts
// services/flux-api.ts
export interface KycDocument {
  id:string;
  fileName:string;
  fileType:string;
  fileSize:number;
  uploadedAt:string;
  available:boolean;
}
export interface KycAdminRow {
  applicationId:string;
  version:number;
  email:string;
  fullName:string;
  docType:string;
  docNumber:string;
  status:string;
  submittedAt:string;
  decidedAt:string|null;
  rejectReason:string|null;
  documents:KycDocument[];
}
export interface TicketResponse {
  id:string;
  userId:string;
  paymentId?:string|null;
  subject:string;
  body:string;
  status:string;
  assigneeAdminId?:string|null;
  createdAt:string;
  updatedAt:string;
}
export interface PageResponse<T> { items:T[]; total:number; }

// Keep the same endpoint; only make size an optional frontend argument.
adminKyc:(status='PENDING',page=0,size=20)=>
  request<KycAdminRow[]>(`/api/admin/kyc/applications?status=${encodeURIComponent(status)}&page=${page}&size=${size}`),

listForAdmin:(status='ALL',page=0,size=20)=>
  fluxApi.get<PageResponse<TicketResponse>>(`/api/admin/tickets?status=${encodeURIComponent(status)}&page=${page}&size=${size}`),
```

- [ ] **Step 5: Implement the pure helper contracts**

```ts
// services/admin-console.ts
import type {ComplianceCase, KycAdminRow, TicketResponse} from './flux-api';

export type AdminEnvironmentName = 'PRODUCTION'|'STAGING'|'SANDBOX';
export interface AdminEnvironment { name:AdminEnvironmentName; label:string; tone:'critical'|'warning'|'info'; }
export interface FieldChange { field:string; label:string; before:string; after:string; }

const environments:Record<AdminEnvironmentName,AdminEnvironment> = {
  PRODUCTION:{name:'PRODUCTION',label:'Production',tone:'critical'},
  STAGING:{name:'STAGING',label:'Staging',tone:'warning'},
  SANDBOX:{name:'SANDBOX',label:'Sandbox',tone:'info'}
};
const timestamp = (value:unknown, fallback=Number.POSITIVE_INFINITY) => {
  const parsed = Date.parse(String(value ?? ''));
  return Number.isFinite(parsed) ? parsed : fallback;
};
const stable = <T>(items:T[], compare:(left:T,right:T)=>number) =>
  items.map((value,index)=>({value,index}))
    .sort((left,right)=>compare(left.value,right.value)||left.index-right.index)
    .map(entry=>entry.value);

export function resolveAdminEnvironment(configured:unknown, hostname:string):AdminEnvironment {
  const explicit = String(configured ?? '').trim().toUpperCase() as AdminEnvironmentName;
  if (explicit in environments) return environments[explicit];
  const host = hostname.toLowerCase();
  if (host==='localhost'||host==='127.0.0.1') return environments.SANDBOX;
  if (/(^|[.-])(staging|stage|uat)([.-]|$)/.test(host)) return environments.STAGING;
  return environments.PRODUCTION;
}
export function isOlderThanHours(value:unknown,hours:number,now=Date.now()):boolean {
  const parsed=timestamp(value,Number.NaN);
  return Number.isFinite(parsed)&&now-parsed>=hours*60*60*1000;
}
export function prioritizeKycReviews<T extends Pick<KycAdminRow,'submittedAt'|'documents'>>(items:T[]):T[] {
  const hasOriginal=(item:T)=>item.documents.length>0&&item.documents.every(document=>document.available);
  return stable([...items],(left,right)=>
    Number(hasOriginal(left))-Number(hasOriginal(right)) ||
    timestamp(left.submittedAt)-timestamp(right.submittedAt));
}
export function prioritizeComplianceCases<T extends Pick<ComplianceCase,'risk'|'reviewExpiresAt'|'createdAt'>>(items:T[]):T[] {
  const risk:Record<string,number>={HIGH:0,MEDIUM:1,LOW:2};
  return stable([...items],(left,right)=>(risk[left.risk]??3)-(risk[right.risk]??3) ||
    timestamp(left.reviewExpiresAt)-timestamp(right.reviewExpiresAt) ||
    timestamp(left.createdAt)-timestamp(right.createdAt));
}
export function prioritizeSupportTickets<T extends Pick<TicketResponse,'status'|'assigneeAdminId'|'createdAt'>>(items:T[]):T[] {
  const status:Record<string,number>={OPEN:0,IN_PROGRESS:1,RESOLVED:2,CLOSED:3};
  return stable([...items],(left,right)=>(status[left.status]??4)-(status[right.status]??4) ||
    Number(Boolean(left.assigneeAdminId))-Number(Boolean(right.assigneeAdminId)) ||
    timestamp(left.createdAt)-timestamp(right.createdAt));
}
export function composeDecisionReason(code:string,notes:string):string {
  const normalizedCode=code.trim().toUpperCase();
  if(!normalizedCode)throw new Error('Choose a standardized reason code.');
  const normalizedNotes=notes.trim();
  const result=normalizedNotes?`${normalizedCode} — ${normalizedNotes}`:normalizedCode;
  if(result.length>500)throw new Error('Reason code and notes must be 500 characters or fewer.');
  return result;
}
export function maskIdentifier(value:string):string {
  const normalized=value.trim();
  return !normalized?'—':normalized.length<=4?'••••':`••••${normalized.slice(-4)}`;
}
const display=(value:unknown)=>value===true?'Yes':value===false?'No':value==null||value===''?'—':String(value);
export function diffFields(before:Record<string,unknown>,after:Record<string,unknown>,labels:Record<string,string>,order:string[]):FieldChange[] {
  return order.filter(field=>display(before[field])!==display(after[field]))
    .map(field=>({field,label:labels[field]||field,before:display(before[field]),after:display(after[field])}));
}
export function nextTicketAction(status:string):{status:string;label:string}|null {
  return ({OPEN:{status:'IN_PROGRESS',label:'Start work'},IN_PROGRESS:{status:'RESOLVED',label:'Resolve ticket'},RESOLVED:{status:'CLOSED',label:'Close ticket'},CLOSED:{status:'OPEN',label:'Reopen ticket'}} as Record<string,{status:string;label:string}>)[status]||null;
}
export function focusRecordHeading(event:{detail:number},id:string,runtime?:{requestAnimationFrame(callback:()=>void):unknown;document:{getElementById(id:string):{focus():void}|null}}):void {
  if(event.detail!==0)return;
  const host=runtime??{requestAnimationFrame:(callback:()=>void)=>window.requestAnimationFrame(()=>callback()),document};
  host.requestAnimationFrame(()=>host.document.getElementById(id)?.focus());
}
```

- [ ] **Step 6: Run focused tests and type checking**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-console.test.cjs; npm.cmd run typecheck`

Expected: both commands PASS.

- [ ] **Step 7: Commit the foundation**

```powershell
git add frontend/fluxpay-ui/src/ts/services/admin-console.ts frontend/fluxpay-ui/src/ts/services/flux-api.ts frontend/fluxpay-ui/tests/helpers/load-typescript.cjs frontend/fluxpay-ui/tests/admin-console.test.cjs
git commit -m "feat(admin): add operations console foundations"
```

### Task 2: Build the KYC review workspace

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-kyc.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/admin-kyc.html`
- Create: `frontend/fluxpay-ui/tests/admin-kyc.test.cjs`
- Modify: `frontend/fluxpay-ui/src/ts/services/page.ts:74-80,285-298`

**Interfaces:**

- Consumes: `KycAdminRow`, `prioritizeKycReviews`, `isOlderThanHours`, `composeDecisionReason`, `maskIdentifier`, `focusRecordHeading`, and the existing `Page.openDocument` preview flow.
- Produces: route module `admin-kyc`, saved-view values `PENDING`, `AGING`, `DOCUMENTS_UNAVAILABLE`, `VERIFIED`, and `REJECTED`, plus URL parameter `view`.

- [ ] **Step 1: Write failing view-model and template tests**

```js
// tests/admin-kyc.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ko = require('knockout');
const {load} = require('./helpers/load-typescript.cjs');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

class PageStub {
  constructor() {
    this.session={isAdmin:()=>true};
    this.busy=ko.observable(false);this.error=ko.observable('');this.notice=ko.observable('');
    this.cases=ko.observableArray([]);this.adminStatus=ko.observable('PENDING');this.adminPage=ko.observable(0);
    this.review=ko.observable();this.reviewReason=ko.observable('');this.reviewConsent=ko.observable(false);this.reviewDecision=ko.observable('');
    this.reviewDocumentsAvailable=ko.pureComputed(()=>Boolean(this.review()?.documents?.length)&&this.review().documents.every(item=>item.available));
  }
  adminFilter() {}
  openDocument() {}
  closeDocument() {}
  disconnected() {}
}

const helpers=load('ts/services/admin-console.ts', {'./flux-api':{}});
const ViewModel=load('ts/viewModels/admin-kyc.ts', {
  knockout:ko,
  '../services/page':{Page:PageStub},
  '../services/admin-console':helpers,
  '../services/session':{navigate:()=>{}}
});

test('KYC decisions require a reason code and compose the existing reason string', () => {
  const vm=new ViewModel({params:{view:'PENDING'}});
  vm.review({status:'PENDING',documents:[{available:true}]});
  vm.reviewConsent(true);vm.decision('reject');vm.reasonCode('DOCUMENT_UNREADABLE');vm.notes('Upload a clearer image.');
  vm.prepareDecision();
  assert.equal(vm.reviewDecision(),'reject');
  assert.equal(vm.reviewReason(),'DOCUMENT_UNREADABLE — Upload a clearer image.');
});

test('KYC template is a three-pane manual review and makes no OCR claim', () => {
  const html=read('ts/views/admin-kyc.html');
  for(const token of ['review-workspace','review-queue','record-detail','evidence-panel','sticky-decision-bar','Customer-submitted information','Manual document checklist']) assert.ok(html.includes(token), token);
  assert.match(html,/text:maskDocument\(docNumber\)/);
  assert.doesNotMatch(html,/OCR|automated mismatch|AI match/i);
  assert.doesNotMatch(html,/copy document|reveal document/i);
});
```

Add tests in this file for `AGING` using a fixed `now`, `DOCUMENTS_UNAVAILABLE`, approval consent, rejection without a code, preservation of `expectedVersion`, non-admin calls making no API request, and a KYC list response arriving after logout being cleared immediately.

- [ ] **Step 2: Run the focused tests and verify the route module is absent**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-kyc.test.cjs`

Expected: FAIL because `admin-kyc.ts` and `admin-kyc.html` do not exist.

- [ ] **Step 3: Implement the focused KYC view model while retaining the existing request methods**

```ts
// viewModels/admin-kyc.ts
import * as ko from 'knockout';
import {Page} from '../services/page';
import {composeDecisionReason,focusRecordHeading,isOlderThanHours,maskIdentifier,prioritizeKycReviews} from '../services/admin-console';
import {navigate} from '../services/session';

type KycView='PENDING'|'AGING'|'DOCUMENTS_UNAVAILABLE'|'VERIFIED'|'REJECTED';

class AdminKycViewModel extends Page {
  savedView=ko.observable<KycView>('PENDING');
  decision=ko.observable<'approve'|'reject'>('approve');
  reasonCode=ko.observable('VERIFIED_DOCUMENTS');
  notes=ko.observable('');
  readonly reasonCodes={
    approve:[{value:'VERIFIED_DOCUMENTS',label:'Documents verified'}],
    reject:[
      {value:'DOCUMENT_UNREADABLE',label:'Document unreadable'},
      {value:'DETAILS_DO_NOT_MATCH',label:'Submitted details do not match'},
      {value:'DOCUMENT_EXPIRED',label:'Document expired'},
      {value:'OTHER',label:'Other'}
    ]
  };
  visibleReviews=ko.pureComputed(()=>prioritizeKycReviews(this.cases()).filter(row=>{
    const query=this.search().trim().toLowerCase();
    const matchesText=!query||(row.fullName+' '+row.email+' '+row.applicationId).toLowerCase().includes(query);
    const matchesView=this.savedView()==='AGING'?row.status==='PENDING'&&isOlderThanHours(row.submittedAt,24):
      this.savedView()==='DOCUMENTS_UNAVAILABLE'?row.status==='PENDING'&&(!row.documents.length||row.documents.some((item:any)=>!item.available)):true;
    return matchesText&&matchesView;
  }));
  maskDocument=maskIdentifier;

  constructor(params:any){
    super('admin',params);
    const view=String(params?.params?.view||'PENDING').toUpperCase() as KycView;
    if(['PENDING','AGING','DOCUMENTS_UNAVAILABLE','VERIFIED','REJECTED'].includes(view))this.savedView(view);
    this.adminStatus(['VERIFIED','REJECTED'].includes(this.savedView())?this.savedView():'PENDING');
    const applicationId=String(params?.params?.applicationId||'');
    this.caseSubscription=this.cases.subscribe(rows=>{
      if(!this.session.isAdmin()){if(rows.length)this.cases([]);return;}
      const match=applicationId&&rows.find((row:any)=>row.applicationId===applicationId);
      if(match)this.selectReview(match);
    });
  }
  changeView=()=>{
    this.adminStatus(['VERIFIED','REJECTED'].includes(this.savedView())?this.savedView():'PENDING');
    this.adminFilter();
    navigate('admin-kyc',{view:this.savedView()});
  };
  selectReview=(row:any,event?:{detail:number})=>{
    this.openReview(row);this.decision('approve');this.reasonCode('VERIFIED_DOCUMENTS');this.notes('');
    if(event)focusRecordHeading(event,'kyc-record-heading');
  };
  prepareDecision=()=>{
    const approve=this.decision()==='approve';
    if(approve&&(!this.reviewConsent()||!this.reviewDocumentsAvailable())){this.error('Open the documents and confirm the manual review.');return;}
    try{this.reviewReason(composeDecisionReason(this.reasonCode(),this.notes()));}
    catch(error:any){this.error(error.message);return;}
    this.reviewDecision(this.decision());
  };
  confirmDecision=()=>this.decide(this.reviewDecision()==='approve');
  private caseSubscription:{dispose():void};
  disconnected(){this.caseSubscription.dispose();super.disconnected();}
}
export = AdminKycViewModel;
```

Keep the existing `Page.decide` body unchanged so approval and rejection continue to send `{expectedVersion, reason}` to the same endpoints. Adjust its rejection validation copy from “free-form rejection reason” to “standardized reason and notes” without changing the payload.

- [ ] **Step 4: Create the semantic review template**

```html
<section class="admin-page review-page" data-bind="if:session.isAdmin()">
  <header class="admin-page-heading">
    <div><span class="eyebrow">OPERATIONS</span><h1>KYC reviews</h1><p>Review submitted information beside the original evidence.</p></div>
    <button class="pill soft" data-bind="click:refresh,disable:busy">Refresh</button>
  </header>
  <div class="alert error" role="alert" data-bind="visible:error,text:error"></div>
  <div class="alert success" role="status" aria-live="polite" data-bind="visible:notice,text:notice"></div>
  <div class="review-workspace">
    <aside class="review-queue" aria-label="KYC review queue">
      <label>Saved view<select data-bind="value:savedView,event:{change:changeView},disable:busy"><option value="PENDING">Pending</option><option value="AGING">Waiting over 24 hours</option><option value="DOCUMENTS_UNAVAILABLE">Documents unavailable</option><option value="VERIFIED">Verified</option><option value="REJECTED">Rejected</option></select></label>
      <label>Search<input type="search" data-bind="textInput:search" aria-label="Search by name, email, or application ID"></label>
      <table class="dense-table"><thead><tr><th>Applicant</th><th>Age</th><th>Status</th><th></th></tr></thead><tbody data-bind="foreach:visibleReviews"><tr data-bind="attr:{'aria-selected':$parent.review()=== $data}"><td><strong data-bind="text:fullName"></strong><small data-bind="text:email"></small></td><td data-bind="text:$parent.date(submittedAt)"></td><td><span class="status" data-bind="text:$parent.label(status),attr:{'data-status':status}"></span></td><td><button type="button" class="text-button" data-bind="click:$parent.selectReview">Open</button></td></tr></tbody></table>
    </aside>
    <main class="record-detail" data-bind="with:review">
      <h2 id="kyc-record-heading" tabindex="-1" data-bind="text:fullName"></h2><p data-bind="text:email"></p>
      <h3>Customer-submitted information</h3>
      <dl class="detail-grid"><div><dt>Document type</dt><dd data-bind="text:docType"></dd></div><div><dt>Document number</dt><dd class="mono" data-bind="text:$parent.maskDocument(docNumber)"></dd></div><div><dt>Submitted</dt><dd data-bind="text:$parent.date(submittedAt)"></dd></div></dl>
      <h3>Manual document checklist</h3><ul><li>Confirm the name and identifier manually.</li><li>Confirm the document is readable and current.</li><li>Record the reason code for the decision.</li></ul>
    </main>
    <aside class="evidence-panel" data-bind="with:review">
      <h2>Evidence</h2><div class="kyc-document-grid" data-bind="foreach:documents"><button type="button" class="kyc-document-card" data-bind="click:$parents[1].openDocument"><strong data-bind="text:fileName"></strong><small data-bind="text:available?'Open original':'Original unavailable'"></small></button></div>
    </aside>
  </div>
  <form class="sticky-decision-bar" data-bind="visible:review()&&review().status==='PENDING',submit:function(){prepareDecision();return false}">
    <label>Decision<select data-bind="value:decision"><option value="approve">Approve</option><option value="reject">Reject</option></select></label>
    <label>Reason code<select data-bind="options:reasonCodes[decision()],optionsText:'label',optionsValue:'value',value:reasonCode"></select></label>
    <label>Notes<textarea maxlength="500" rows="2" data-bind="textInput:notes"></textarea></label>
    <label class="checkbox-label"><input type="checkbox" data-bind="checked:reviewConsent,disable:decision()==='reject'">Manual evidence review completed</label>
    <button class="pill dark" data-bind="disable:busy">Review decision</button>
  </form>
</section>
```

Include the existing read-only PDF/image preview markup after the decision bar, still using `openDocument`, `closeDocument`, `kycPdfPreview`, and text bindings. Add a compact empty state which names the active saved view and a filter-reset button.
For a verified or rejected selection, replace the decision controls with the status text `No further KYC decision is available for this state.` and show `decidedAt` and `rejectReason` when returned.
The `Open` button remains the sole row activation target. Keyboard activation moves focus to `#kyc-record-heading`; mouse activation leaves focus on the clicked button.

- [ ] **Step 5: Run KYC and existing document-preview tests**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-kyc.test.cjs tests/experience.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS and the KYC tests confirm no OCR or reveal/copy claim.

- [ ] **Step 6: Commit the KYC workspace**

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin-kyc.ts frontend/fluxpay-ui/src/ts/views/admin-kyc.html frontend/fluxpay-ui/src/ts/services/page.ts frontend/fluxpay-ui/tests/admin-kyc.test.cjs
git commit -m "feat(admin): add KYC review workspace"
```

### Task 3: Build the compliance review workspace

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-compliance.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/admin-compliance.html`
- Create: `frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs`
- Modify: `frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts:116-195,328-356`
- Modify: `frontend/fluxpay-ui/tests/compliance.test.cjs`

**Interfaces:**

- Consumes: `ComplianceWorkspace`, `prioritizeComplianceCases`, `composeDecisionReason`, `focusRecordHeading`, and `navigate`.
- Produces: route module `admin-compliance`, URL parameter `view`, reason-code fields, and case-to-Copilot navigation using `{caseId, paymentId}`.

- [ ] **Step 1: Write failing tests for risk-first ordering, reason composition, truthful fields, and Copilot handoff**

```js
// tests/admin-review-workspaces.test.cjs (compliance section)
test('compliance decisions combine a code and notes in the existing decisionReason field', async () => {
  const {page,calls}=complianceWorkspace();
  page.selectedCase(openHighRiskCase);
  page.decisionCode('RULES_SATISFIED');
  page.decisionNotes('Evidence reviewed.');
  page.prepareDecision('approve');
  await page.confirm();
  assert.deepEqual(calls.find(call=>call[0]==='decideComplianceCase'), [
    'decideComplianceCase', openHighRiskCase.id, 'approve', 'RULES_SATISFIED — Evidence reviewed.'
  ]);
});

test('compliance page exposes only response-backed context and no delete action', () => {
  const html=read('ts/views/admin-compliance.html');
  for(const field of ['reviewReference','reviewExpiresAt','requoteRequired','riskReasons','suggestedAction','decisionReason']) assert.ok(html.includes(field), field);
  assert.doesNotMatch(html,/delete-case|Delete manual case/);
  assert.doesNotMatch(html,/customer country|payment amount|payment currency/i);
  assert.match(html,/navigateToCopilot/);
});
```

Add a priority test containing HIGH cases with two expiry times, a MEDIUM case with an earlier creation time, and a null expiry. Add a route test asserting `navigateToCopilot` dispatches `admin-copilot` with the selected case and payment IDs.

- [ ] **Step 2: Run the focused tests and verify the page is absent**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-review-workspaces.test.cjs`

Expected: FAIL because the focused compliance route and reason-code fields do not exist.

- [ ] **Step 3: Add risk-first state and standardized decisions to `ComplianceWorkspace`**

```ts
// services/compliance-workspace.ts
import {composeDecisionReason,prioritizeComplianceCases} from './admin-console';

decisionCode=ko.observable('RULES_SATISFIED');
decisionNotes=ko.observable('');
savedCaseView=ko.observable<'HIGH_RISK'|'REQUOTE_REQUIRED'|'REVIEW_EXPIRING'|'OPEN'|'COMPLETED'>('OPEN');
readonly decisionCodes={
  approve:[{value:'RULES_SATISFIED',label:'Rules satisfied'},{value:'EVIDENCE_VERIFIED',label:'Evidence verified'},{value:'OTHER',label:'Other'}],
  reject:[{value:'POLICY_VIOLATION',label:'Policy violation'},{value:'INSUFFICIENT_EVIDENCE',label:'Insufficient evidence'},{value:'RISK_NOT_ACCEPTABLE',label:'Risk not acceptable'},{value:'OTHER',label:'Other'}]
};
savedCases=ko.pureComputed(()=>this.filteredCases().filter(item=>{
  const view=this.savedCaseView();
  if(view==='HIGH_RISK')return item.status==='OPEN'&&item.risk==='HIGH';
  if(view==='REQUOTE_REQUIRED')return item.status==='OPEN'&&item.requoteRequired;
  if(view==='REVIEW_EXPIRING'){
    const expires=Date.parse(item.reviewExpiresAt||'');
    return item.status==='OPEN'&&Number.isFinite(expires)&&expires>=Date.now()&&expires-Date.now()<=24*60*60*1000;
  }
  return view==='COMPLETED'?item.status!=='OPEN':item.status==='OPEN';
}));
prioritizedCases=ko.pureComputed(()=>prioritizeComplianceCases(this.savedCases()));

prepareDecision=(action:'approve'|'reject')=>{
  try{this.decisionReason(composeDecisionReason(this.decisionCode(),this.decisionNotes()));}
  catch(error:any){this.error(error.message);return;}
  this.askConfirmation(action);
};
```

Update `clear`, `openCase`, and `closeCase` to reset `decisionCode` and `decisionNotes`. Make the default computed order risk, review expiry, and age; retain explicit user-selected alternate sorts only if their dropdown remains in the focused page. Leave `confirm` and `api.decideComplianceCase` unchanged so the request body remains `{decisionReason}`. Keep deletion methods internal for compatibility but remove every focused-template binding that can invoke them.

- [ ] **Step 4: Add the route wrapper and case-to-Copilot handoff**

```ts
// viewModels/admin-compliance.ts
import * as ko from 'knockout';
import {focusRecordHeading} from '../services/admin-console';
import {ComplianceWorkspace} from '../services/compliance-workspace';
import {navigate,session} from '../services/session';

class AdminComplianceViewModel {
  session=session;
  workspace=new ComplianceWorkspace();
  private caseId:string;
  constructor(params:any){
    const view=String(params?.params?.view||'OPEN').toUpperCase();
    if(['HIGH_RISK','REQUOTE_REQUIRED','REVIEW_EXPIRING','OPEN','COMPLETED'].includes(view)) this.workspace.savedCaseView(view as any);
    this.caseId=String(params?.params?.caseId||'');
    void this.activate();
  }
  private async activate(){
    if(!session.user())await session.restore();
    if(!session.isAdmin())return;
    await this.workspace.loadCases();
    if(this.caseId)await this.workspace.openCase({id:this.caseId});
  }
  changeView=()=>navigate('admin-compliance',{view:this.workspace.savedCaseView()});
  openCase=async (item:any,event:{detail:number})=>{
    await this.workspace.openCase(item);
    focusRecordHeading(event,'compliance-record-heading');
  };
  navigateToCopilot=()=>{
    const selected=this.workspace.selectedCase();
    if(selected)navigate('admin-copilot',{caseId:selected.id,paymentId:selected.paymentId});
  };
  disconnected(){this.workspace.dispose();}
}
export = AdminComplianceViewModel;
```

- [ ] **Step 5: Create the compliance review template**

Create `admin-compliance.html` with `data-bind="with:workspace"` around:

```html
<div class="review-workspace compliance-review-workspace">
  <aside class="review-queue"><label>Saved view<select data-bind="value:savedCaseView,event:{change:$parent.changeView}"><option value="HIGH_RISK">High risk</option><option value="REQUOTE_REQUIRED">Requote required</option><option value="REVIEW_EXPIRING">Review expiring</option><option value="OPEN">Open</option><option value="COMPLETED">Completed</option></select></label><table class="dense-table"><tbody data-bind="foreach:prioritizedCases"><tr><td><span class="status" data-bind="text:risk,attr:{'data-status':risk}"></span></td><td><strong data-bind="text:$parent.date(createdAt)"></strong><small data-bind="text:id"></small></td><td><button type="button" class="text-button" data-bind="click:$parents[1].openCase">Open</button></td></tr></tbody></table></aside>
  <main class="record-detail" data-bind="with:selectedCase"><h2 id="compliance-record-heading" tabindex="-1">Compliance case</h2><dl class="detail-grid"><div><dt>Case ID</dt><dd data-bind="text:id"></dd></div><div><dt>Payment ID</dt><dd data-bind="text:paymentId"></dd></div><div><dt>Review reference</dt><dd data-bind="text:reviewReference||'—'"></dd></div><div><dt>Review expires</dt><dd data-bind="text:$parent.date(reviewExpiresAt)"></dd></div><div><dt>Requote required</dt><dd data-bind="text:requoteRequired?'Yes':'No'"></dd></div></dl><h3>Triggered reasons</h3><ul data-bind="foreach:riskReasons"><li data-bind="text:$data"></li></ul><h3>Suggested action</h3><p data-bind="text:suggestedAction"></p></main>
  <aside class="evidence-panel" data-bind="with:selectedCase"><h2>Decision history</h2><dl class="detail-grid"><div><dt>Status</dt><dd data-bind="text:status"></dd></div><div><dt>Decided by</dt><dd data-bind="text:decidedBy||'—'"></dd></div><div><dt>Decided at</dt><dd data-bind="text:$parent.date(decidedAt)"></dd></div><div><dt>Reason</dt><dd data-bind="text:decisionReason||'—'"></dd></div></dl><button class="pill soft" data-bind="click:$parents[1].navigateToCopilot">Ask Copilot about this case</button></aside>
</div>
<form class="sticky-decision-bar" data-bind="visible:selectedCase()&&selectedCase().status==='OPEN',submit:function(){prepareDecision(pendingDecision());return false}">
  <label>Decision<select data-bind="value:pendingDecision"><option value="approve">Approve</option><option value="reject">Reject</option></select></label>
  <label>Reason code<select data-bind="options:decisionCodes[pendingDecision()],optionsText:'label',optionsValue:'value',value:decisionCode"></select></label>
  <label>Notes<textarea maxlength="500" rows="2" data-bind="textInput:decisionNotes"></textarea></label>
  <button class="pill dark" data-bind="disable:busy">Review decision</button>
</form>
```

Add `pendingDecision=ko.observable<'approve'|'reject'>('approve')` to the workspace. Include a confirmation dialog showing the composed reason and the linked-payment/manual-case copy already used by the service. Do not include any payment-detail request or fields absent from `ComplianceCase`.
For a non-open case, replace the decision controls with `Decision complete — no further case action is available.` while retaining the returned decision history.
Keyboard activation of a queue `Open` button moves focus to `#compliance-record-heading`; mouse activation leaves focus on the button.

- [ ] **Step 6: Update legacy compliance tests to point to focused templates**

Change policy assertions to `ts/views/admin-policies.html`, Copilot assertions to `ts/views/admin-copilot.html`, and compliance-case assertions to `ts/views/admin-compliance.html`. Replace the old delete-case expectation with:

```js
assert.doesNotMatch(read('ts/views/admin-compliance.html'), /delete-case|Delete manual case/);
```

- [ ] **Step 7: Run compliance tests and commit**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-review-workspaces.test.cjs tests/compliance.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts frontend/fluxpay-ui/src/ts/viewModels/admin-compliance.ts frontend/fluxpay-ui/src/ts/views/admin-compliance.html frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs frontend/fluxpay-ui/tests/compliance.test.cjs
git commit -m "feat(admin): add compliance review workspace"
```

### Task 4: Convert support tickets to the shared review workspace

**Files:**

- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin-tickets.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin-tickets.html`
- Modify: `frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs`

**Interfaces:**

- Consumes: `TicketResponse`, `prioritizeSupportTickets`, `isOlderThanHours`, `nextTicketAction`, `focusRecordHeading`, and existing `ticketApi` methods.
- Produces: saved-view values `OPEN`, `UNASSIGNED`, `ASSIGNED_TO_ME`, `AGING`, `RESOLVED`, and `CLOSED`; selected ticket state; and one next-action descriptor per selected ticket.

- [ ] **Step 1: Add failing tests for priority, saved views, age wording, selection, and late responses**

```js
// tests/admin-review-workspaces.test.cjs (support section)
test('support queue prioritizes open, unassigned, and oldest records', async () => {
  const {page,finish}=ticketWorkspaceWithDeferredLoad();
  const pending=page.loadTickets();
  finish({items:[
    ticket({id:'assigned',status:'OPEN',assigneeAdminId:'admin-1',createdAt:'2026-09-20T00:00:00Z'}),
    ticket({id:'unassigned',status:'OPEN',assigneeAdminId:null,createdAt:'2026-09-21T00:00:00Z'}),
    ticket({id:'closed',status:'CLOSED',assigneeAdminId:null,createdAt:'2026-09-19T00:00:00Z'})
  ],total:3});
  await pending;
  assert.deepEqual(Array.from(page.visibleTickets(), item=>item.id), ['unassigned','assigned','closed']);
  assert.equal(page.selectedTicket().id, 'unassigned');
});

test('logout invalidates a late ticket response', async () => {
  const {page,session,finish}=ticketWorkspaceWithDeferredLoad();
  const pending=page.loadTickets();
  session.user(null);
  finish({items:[ticket({id:'late'})],total:1});
  await pending;
  assert.equal(page.tickets().length,0);
  assert.equal(page.selectedTicket(),undefined);
});

test('support copy says age and customer statement, never SLA or latest message', () => {
  const html=read('ts/views/admin-tickets.html');
  assert.match(html,/Customer statement/);
  assert.match(html,/Waiting over 24 hours/);
  assert.doesNotMatch(html,/\bSLA\b|Latest message/i);
  for(const token of ['review-workspace','review-queue','record-detail','evidence-panel','sticky-decision-bar']) assert.ok(html.includes(token),token);
});
```

Add cases for `ASSIGNED_TO_ME`, `AGING`, selection retention after refresh, `CLOSED` mapping to “Reopen ticket,” and the current backend rule that assignment is not offered for a closed ticket.

- [ ] **Step 2: Run the review-workspace tests and confirm the current support table fails**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-review-workspaces.test.cjs`

Expected: FAIL because `selectedTicket`, `visibleTickets`, saved views, and the three-pane template do not exist.

- [ ] **Step 3: Refactor the support view model without changing ticket endpoints**

```ts
// viewModels/admin-tickets.ts
import {TicketResponse} from '../services/flux-api';
import {focusRecordHeading,isOlderThanHours,nextTicketAction,prioritizeSupportTickets} from '../services/admin-console';
import {navigate} from '../services/session';

type TicketView='OPEN'|'UNASSIGNED'|'ASSIGNED_TO_ME'|'AGING'|'RESOLVED'|'CLOSED';

selectedTicket=ko.observable<TicketResponse>();
savedView=ko.observable<TicketView>('OPEN');
private ticketEpoch=0;
private preferredTicketId='';
visibleTickets=ko.pureComputed(()=>prioritizeSupportTickets(this.tickets()).filter(ticket=>{
  const query=this.search().trim().toLowerCase();
  const text=(ticket.subject+' '+ticket.body+' '+ticket.userId+' '+(ticket.paymentId||'')).toLowerCase();
  const view=this.savedView();
  const matchesView=view==='UNASSIGNED'?!ticket.assigneeAdminId&&ticket.status!=='CLOSED':
    view==='ASSIGNED_TO_ME'?ticket.assigneeAdminId===this.currentAdminId()&&ticket.status!=='CLOSED':
    view==='AGING'?['OPEN','IN_PROGRESS'].includes(ticket.status)&&isOlderThanHours(ticket.createdAt,24):
    view==='OPEN'?['OPEN','IN_PROGRESS'].includes(ticket.status):ticket.status===view;
  return matchesView&&(!query||text.includes(query));
}));
selectedNextAction=ko.pureComputed(()=>nextTicketAction(this.selectedTicket()?.status||''));
selectTicket=(ticket:TicketResponse,event?:{detail:number})=>{
  this.selectedTicket(ticket);
  if(event)focusRecordHeading(event,'support-record-heading');
};
ticketAge=(ticket:TicketResponse)=>{
  const created=Date.parse(ticket.createdAt);
  if(!Number.isFinite(created))return 'Age unavailable';
  const hours=Math.max(0,Math.floor((Date.now()-created)/(60*60*1000)));
  return hours>=24?`Waiting ${Math.floor(hours/24)} day${Math.floor(hours/24)===1?'':'s'}`:`Waiting ${hours} hour${hours===1?'':'s'}`;
};
changeView=()=>{
  this.status(['RESOLVED','CLOSED'].includes(this.savedView())?this.savedView():'ALL');
  this.ticketPage(0);
  void this.loadTickets();
  navigate('admin-tickets',{view:this.savedView()});
};
runNextAction=()=>{
  const ticket=this.selectedTicket(),action=this.selectedNextAction();
  if(ticket&&action)this.moveTo(ticket,action.status);
};
```

In the existing constructor, initialize `savedView` from `params.params.view`, defaulting to `OPEN`, set `status` to `RESOLVED` or `CLOSED` for those server-backed views and `ALL` for every other view before the first request, and set `preferredTicketId=String(params?.params?.ticketId||'')`. In `fetchTickets`, capture `const epoch=++this.ticketEpoch` before awaiting `ticketApi.listForAdmin`, and assign results only when the epoch still matches and `session.isAdmin()` is true; this makes a newer request supersede an older response. Also increment the epoch, clear `tickets`, `total`, and `selectedTicket` in the session subscription and `disconnected`. After a successful fetch, select `preferredTicketId` when present, otherwise retain the current selected ID when it remains in the result, otherwise select the first prioritized visible ticket; clear `preferredTicketId` after the first successful match.

- [ ] **Step 4: Replace the support table with the shared review layout**

```html
<section class="admin-page review-page">
  <header class="admin-page-heading"><div><span class="eyebrow">OPERATIONS</span><h1>Support tickets</h1><p>Prioritized by state, assignment, and age.</p></div><button class="pill soft" data-bind="click:loadTickets,disable:busy">Refresh</button></header>
  <div class="review-workspace">
    <aside class="review-queue" aria-label="Support ticket queue"><label>Saved view<select data-bind="value:savedView,event:{change:changeView}"><option value="OPEN">Open work</option><option value="UNASSIGNED">Unassigned</option><option value="ASSIGNED_TO_ME">Assigned to me</option><option value="AGING">Waiting over 24 hours</option><option value="RESOLVED">Resolved</option><option value="CLOSED">Closed</option></select></label><label>Search<input type="search" data-bind="textInput:search"></label><table class="dense-table"><tbody data-bind="foreach:visibleTickets"><tr><td><strong data-bind="text:subject"></strong><small data-bind="text:assigneeAdminId?'Assigned':'Unassigned'"></small></td><td><span class="status" data-bind="text:$parent.label(status),attr:{'data-status':status}"></span></td><td><button type="button" class="text-button" data-bind="click:$parent.selectTicket">Open</button></td></tr></tbody></table></aside>
    <main class="record-detail" data-bind="with:selectedTicket"><h2 id="support-record-heading" tabindex="-1" data-bind="text:subject"></h2><h3>Customer statement</h3><p class="preserve-text" data-bind="text:body"></p><dl class="detail-grid"><div><dt>Customer</dt><dd data-bind="text:userId"></dd></div><div><dt>Payment</dt><dd data-bind="text:paymentId||'—'"></dd></div></dl></main>
    <aside class="evidence-panel" data-bind="with:selectedTicket"><h2>Operational context</h2><dl class="detail-grid"><div><dt>Assignee</dt><dd data-bind="text:assigneeAdminId||'Unassigned'"></dd></div><div><dt>Created</dt><dd data-bind="text:$parent.date(createdAt)"></dd></div><div><dt>Updated</dt><dd data-bind="text:$parent.date(updatedAt)"></dd></div><div><dt>Age</dt><dd data-bind="text:$parent.ticketAge($data)"></dd></div></dl></aside>
  </div>
  <div class="sticky-decision-bar" data-bind="visible:selectedTicket"><button class="pill soft" data-bind="visible:selectedTicket()&&selectedTicket().status!=='CLOSED'&&selectedTicket().assigneeAdminId!==currentAdminId(),click:function(){assignToMe(selectedTicket())},disable:busy">Assign to me</button><button class="pill dark" data-bind="visible:selectedNextAction,click:runNextAction,disable:busy,text:selectedNextAction()&&selectedNextAction().label"></button></div>
</section>
```

Keep the signed-out and non-admin access gates. Add a source-specific empty state naming the selected view, plus the existing pagination controls.
Keyboard activation of a ticket `Open` button moves focus to `#support-record-heading`; mouse activation leaves focus on the button.

- [ ] **Step 5: Run support tests and commit**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-review-workspaces.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/viewModels/admin-tickets.ts frontend/fluxpay-ui/src/ts/views/admin-tickets.html frontend/fluxpay-ui/tests/admin-review-workspaces.test.cjs
git commit -m "feat(admin): redesign support review workspace"
```

### Task 5: Add reviewed provider and route mutations, then build the Providers page

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-providers.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/admin-providers.html`
- Create: `frontend/fluxpay-ui/tests/admin-routing-console.test.cjs`
- Modify: `frontend/fluxpay-ui/src/ts/services/routing-workspace.ts:42-390`
- Modify: `frontend/fluxpay-ui/tests/routing.test.cjs`

**Interfaces:**

- Consumes: `RoutingWorkspace`, `diffFields`, `resolveAdminEnvironment`, existing provider validation, POST/PUT methods, and stale-version recovery.
- Produces: `providerChanges`, `routeChanges`, `saveReview`, `requestProviderSave`, `confirmProviderSave`, `requestRouteSave`, and `confirmRouteSave`.
- Produces: route module `admin-providers`.

- [ ] **Step 1: Write failing tests for reviewed mutations and the provider page**

```js
// tests/admin-routing-console.test.cjs
test('provider update shows a before/after diff before making the existing PUT', async () => {
  const {page,calls}=routingWorkspace();
  await page.loadAll();
  page.editProvider(page.providers()[0]);
  page.providerName('Wise Payments Ltd');
  page.providerActive(false);
  page.requestProviderSave();
  assert.equal(calls.filter(call=>call[0]==='updateProvider').length,0);
  assert.deepEqual(JSON.parse(JSON.stringify(page.providerChanges())),[
    {field:'providerName',label:'Provider name',before:'Wise',after:'Wise Payments Ltd'},
    {field:'active',label:'Active',before:'Yes',after:'No'}
  ]);
  await page.confirmProviderSave();
  assert.equal(calls.filter(call=>call[0]==='updateProvider').length,1);
});

test('provider template has no destructive resource control', () => {
  const html=read('ts/views/admin-providers.html');
  assert.match(html,/Provider configuration/);
  assert.match(html,/requestProviderSave/);
  assert.match(html,/providerChanges/);
  assert.doesNotMatch(html,/requestDeleteProvider|deleteProvider|Delete provider|Remove provider/i);
});
```

Retain the existing stale-provider tests and add an assertion that form values and `providerChanges` remain available after the first stale failure. Add create-provider coverage proving the POST also waits for review confirmation.

- [ ] **Step 2: Run routing tests and confirm direct-save behavior fails the new contract**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-routing-console.test.cjs tests/routing.test.cjs`

Expected: FAIL because the reviewed-save state and provider page do not exist.

- [ ] **Step 3: Add reviewed-save state around the existing validated mutations**

```ts
// services/routing-workspace.ts
import {diffFields,FieldChange} from './admin-console';

providerChanges=ko.observableArray<FieldChange>([]);
routeChanges=ko.observableArray<FieldChange>([]);
saveReview=ko.observable<'provider'|'route'|''>('');

requestProviderSave=()=>{
  if(this.busy())return;
  let body:CreateProviderRequest;
  try{body=this.providerPayload();}catch(error:any){this.providerError(error.message);return;}
  const target=this.providerEditTarget();
  const before=target||{};
  this.providerChanges(diffFields(before,body,{
    providerCode:'Provider code',providerName:'Provider name',railType:'Rail type',active:'Active'
  },['providerCode','providerName','railType','active']));
  if(target&&!this.providerChanges().length){this.providerError('No provider changes to review.');return;}
  this.saveReview('provider');
};
confirmProviderSave=async()=>{if(this.saveReview()!=='provider')return;this.saveReview('');await this.saveProvider();};
requestRouteSave=()=>{
  if(this.busy())return;
  let body:ReturnType<RoutingWorkspace['routePayload']>;
  try{body=this.routePayload();}catch(error:any){this.routeError(error.message);return;}
  const target=this.routeEditTarget();
  this.routeChanges(diffFields(target||{},body,{
    providerId:'Provider',routeCode:'Route code',name:'Name',destinationType:'Payout method',destinationCountry:'Country',payoutCurrency:'Currency',baseFee:'Base fee',fxSpreadPercentage:'FX spread',estimatedMinutes:'ETA',configuredSuccessRate:'Configured reliability',minimumRecipientAmount:'Minimum amount',maximumRecipientAmount:'Maximum amount',active:'Active'
  },['providerId','routeCode','name','destinationType','destinationCountry','payoutCurrency','baseFee','fxSpreadPercentage','estimatedMinutes','configuredSuccessRate','minimumRecipientAmount','maximumRecipientAmount','active']));
  if(target&&!this.routeChanges().length){this.routeError('No route changes to review.');return;}
  this.saveReview('route');
};
confirmRouteSave=async()=>{if(this.saveReview()!=='route')return;this.saveReview('');await this.saveRoute();};
cancelSaveReview=()=>{if(!this.busy())this.saveReview('');};
```

Reset the change arrays and `saveReview` in `clear`, `closeProviderEditor`, and `closeRouteEditor`. Do not remove or alter the current `saveProvider`, `saveRoute`, stale conflict, validation, or refresh logic. Existing delete methods may remain unreachable for API compatibility, but no new page may bind to them.

- [ ] **Step 4: Add the Providers route wrapper**

```ts
// viewModels/admin-providers.ts
import {resolveAdminEnvironment} from '../services/admin-console';
import {RoutingWorkspace} from '../services/routing-workspace';
import {session} from '../services/session';

class AdminProvidersViewModel {
  session=session;
  environment=resolveAdminEnvironment((window as any).FLUXPAY_ENVIRONMENT,window.location.hostname);
  workspace=new RoutingWorkspace();
  constructor(){void this.activate();}
  private async activate(){if(!session.user())await session.restore();if(session.isAdmin())await this.workspace.loadAll();}
  disconnected(){this.workspace.dispose();}
}
export = AdminProvidersViewModel;
```

- [ ] **Step 5: Build the dense provider configuration template**

```html
<section class="admin-page configuration-page" data-bind="with:workspace">
  <header class="admin-page-heading"><div><span class="eyebrow">MONEY MOVEMENT</span><h1>Providers</h1><p>Configure provider identity, rail, and activation using current server fields.</p></div><button class="pill dark" data-bind="click:newProvider,disable:busy">New provider</button></header>
  <div class="configuration-workspace"><section class="configuration-list"><label>Search<input type="search" data-bind="textInput:search"></label><div class="table-scroll"><table class="dense-table"><thead><tr><th>Code</th><th>Name</th><th>Rail</th><th>Routes</th><th>Status</th><th>Version</th><th></th></tr></thead><tbody data-bind="foreach:filteredProviders"><tr><td class="mono" data-bind="text:providerCode"></td><td data-bind="text:providerName"></td><td data-bind="text:$parent.railDisplayLabel(railType)"></td><td data-bind="text:$parent.providerCount(id)"></td><td><span class="status" data-bind="text:archivedAt?'Archived':active?'Active':'Inactive',attr:{'data-status':archivedAt?'ARCHIVED':active?'ACTIVE':'INACTIVE'}"></span></td><td data-bind="text:version"></td><td><button type="button" class="text-button" data-bind="click:$parent.editProvider">Edit</button></td></tr></tbody></table></div></section>
  <aside class="configuration-editor" data-bind="visible:providerForm"><form data-bind="submit:function(){requestProviderSave();return false}"><h2>Provider configuration</h2><label>Provider code<input data-bind="textInput:providerCode,disable:providerEditTarget"></label><label>Name<input data-bind="textInput:providerName"></label><label>Rail<select data-bind="options:railTypes,optionsText:'displayLabel',optionsValue:'railType',value:providerRail"></select></label><label class="checkbox-label"><input type="checkbox" data-bind="checked:providerActive">Active</label><button class="pill dark" data-bind="disable:busy">Review changes</button></form></aside></div>
  <!-- ko if:saveReview()==='provider' --><div class="admin-confirmation" role="dialog" aria-modal="true" aria-labelledby="provider-change-title" data-bind="adminDialog:true"><section><h2 id="provider-change-title">Review provider changes</h2><p data-bind="text:'Target environment: '+$root.environment.label"></p><table class="dense-table"><tbody data-bind="foreach:providerChanges"><tr><th data-bind="text:label"></th><td data-bind="text:before"></td><td data-bind="text:after"></td></tr></tbody></table><div class="action-row"><button class="pill soft" data-bind="click:cancelSaveReview">Back</button><button class="pill dark" data-bind="click:confirmProviderSave,disable:busy,text:'Apply in '+$root.environment.label"></button></div></section></div><!-- /ko -->
</section>
```

Show `System protected` as a text badge when `systemProtected` is true and preserve archived rows in the table. Deactivation stays inside the normal edit form; do not add an overflow delete/archive item.

- [ ] **Step 6: Update routing template tests and run the focused suite**

Point provider template assertions in `routing.test.cjs` to `admin-providers.html`. Replace assertions for visible delete controls with assertions that `requestDeleteProvider` and deletion copy are absent. Keep the API contract and stale-version tests unchanged.

Run: `cd frontend/fluxpay-ui; node --test tests/admin-routing-console.test.cjs tests/routing.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

- [ ] **Step 7: Commit reviewed configuration and Providers**

```powershell
git add frontend/fluxpay-ui/src/ts/services/routing-workspace.ts frontend/fluxpay-ui/src/ts/viewModels/admin-providers.ts frontend/fluxpay-ui/src/ts/views/admin-providers.html frontend/fluxpay-ui/tests/admin-routing-console.test.cjs frontend/fluxpay-ui/tests/routing.test.cjs
git commit -m "feat(admin): add reviewed provider configuration"
```

### Task 6: Build route analysis and the Payout Routes workspace

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/services/route-analysis.ts`
- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-routes.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/admin-routes.html`
- Modify: `frontend/fluxpay-ui/tests/admin-routing-console.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/routing.test.cjs`

**Interfaces:**

- Consumes: `TransferProvider`, `TransferRoute`, `RailDescriptor`, `RoutingWorkspace`, `resolveAdminEnvironment`, and reviewed route-save methods from Task 5.
- Produces: `routeNeedsAttention`, `buildCorridorMatrix`, `buildProviderComparison`, `evaluateRouteEligibility`, and route-page modes `CATALOGUE`, `MATRIX`, `COMPARE`, and `PREVIEW`.

- [ ] **Step 1: Write table-driven route-analysis tests**

```js
// tests/admin-routing-console.test.cjs (route-analysis section)
test('eligibility preview reports every route and never chooses a winner', () => {
  const result=analysis.evaluateRouteEligibility(
    {country:'IN',currency:'INR',amount:'100',destinationType:'EXTERNAL_ACCOUNT'},
    [eligibleRoute,inactiveRoute,overLimitRoute,missingProviderRoute],
    [activeProvider],
    [{railType:'BANK_NETWORK',displayLabel:'Bank network',supportedDestinations:['EXTERNAL_ACCOUNT']}]
  );
  assert.deepEqual(Array.from(result.routes,item=>[item.route.routeCode,item.eligible,item.reasons]),[
    ['ELIGIBLE',true,[]],
    ['INACTIVE',false,['Route is inactive.']],
    ['OVER_LIMIT',false,['Amount exceeds the configured maximum of 50.']],
    ['NO_PROVIDER',false,['Provider configuration is unavailable.']]
  ]);
  assert.equal(Object.prototype.hasOwnProperty.call(result,'winner'),false);
});

test('invalid amount and unknown rail produce explicit non-authoritative errors', () => {
  const invalid=analysis.evaluateRouteEligibility({country:'IN',currency:'INR',amount:'abc',destinationType:'EXTERNAL_ACCOUNT'},[eligibleRoute],[activeProvider],[]);
  assert.deepEqual(Array.from(invalid.inputErrors),['Enter an amount greater than zero.']);
  const rail=analysis.evaluateRouteEligibility({country:'IN',currency:'INR',amount:'10',destinationType:'EXTERNAL_ACCOUNT'},[eligibleRoute],[activeProvider],[]);
  assert.deepEqual(Array.from(rail.routes[0].reasons),['Rail compatibility is unavailable.']);
});
```

Add boundary cases where amount equals minimum and maximum, archived provider/route, country/currency/method mismatch, unsupported destination, null limits, and inactive providers. Add matrix tests proving only active, non-archived route/provider combinations contribute to available counts.

- [ ] **Step 2: Run the route-analysis tests and verify the module is missing**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-routing-console.test.cjs`

Expected: FAIL because `route-analysis.ts` is absent.

- [ ] **Step 3: Implement deterministic corridor, comparison, and eligibility functions**

```ts
// services/route-analysis.ts
import type {RailDescriptor,TransferProvider,TransferRoute} from './flux-api';

export interface EligibilityInput {country:string;currency:string;amount:string;destinationType:string;}
export interface EligibilityRow {route:TransferRoute;provider?:TransferProvider;eligible:boolean;reasons:string[];}

const active=(value:{active:boolean;archivedAt:string|null})=>value.active&&!value.archivedAt;
const normalized=(value:string|null|undefined)=>String(value||'').trim().toUpperCase();

export function routeNeedsAttention(route:TransferRoute,provider?:TransferProvider):boolean {
  const unavailable=!active(route)||!provider||!active(provider);
  const hasOutcomes=route.completedCount+route.failedCount>0;
  return unavailable||(hasOutcomes&&Number(route.effectiveSuccessRate)<Number(route.configuredSuccessRate));
}

export function evaluateRouteEligibility(input:EligibilityInput,routes:TransferRoute[],providers:TransferProvider[],rails:RailDescriptor[]){
  const amount=Number(input.amount);
  const inputErrors:string[]=[];
  if(!Number.isFinite(amount)||amount<=0)inputErrors.push('Enter an amount greater than zero.');
  if(!/^[A-Z]{2}$/.test(normalized(input.country)))inputErrors.push('Enter a two-letter destination country.');
  if(!/^[A-Z]{3}$/.test(normalized(input.currency)))inputErrors.push('Enter a three-letter payout currency.');
  const rows:EligibilityRow[]=routes.map(route=>{
    const provider=providers.find(item=>item.id===route.providerId);
    const rail=provider&&rails.find(item=>item.railType===provider.railType);
    const reasons:string[]=[];
    if(!provider)reasons.push('Provider configuration is unavailable.');
    else if(provider.archivedAt)reasons.push('Provider is archived.');
    else if(!provider.active)reasons.push('Provider is inactive.');
    if(route.archivedAt)reasons.push('Route is archived.');else if(!route.active)reasons.push('Route is inactive.');
    if(route.destinationCountry&&normalized(route.destinationCountry)!==normalized(input.country))reasons.push('Destination country does not match.');
    if(normalized(route.payoutCurrency)!==normalized(input.currency))reasons.push('Payout currency does not match.');
    if(normalized(route.destinationType)!==normalized(input.destinationType))reasons.push('Payout method does not match.');
    if(provider&&!rail)reasons.push('Rail compatibility is unavailable.');
    else if(rail&&!rail.supportedDestinations.includes(input.destinationType))reasons.push('Provider rail does not support this payout method.');
    if(Number.isFinite(amount)&&amount>0){
      if(route.minimumRecipientAmount!=null&&amount<Number(route.minimumRecipientAmount))reasons.push(`Amount is below the configured minimum of ${route.minimumRecipientAmount}.`);
      if(route.maximumRecipientAmount!=null&&amount>Number(route.maximumRecipientAmount))reasons.push(`Amount exceeds the configured maximum of ${route.maximumRecipientAmount}.`);
    }
    return {route,provider,eligible:inputErrors.length===0&&reasons.length===0,reasons};
  });
  return {inputErrors,routes:rows};
}

export function buildCorridorMatrix(routes:TransferRoute[],providers:TransferProvider[]){
  const eligible=routes.filter(route=>{
    const provider=providers.find(item=>item.id===route.providerId);
    return active(route)&&Boolean(provider&&active(provider));
  });
  const countries=Array.from(new Set(eligible.map(route=>normalized(route.destinationCountry)||'GLOBAL'))).sort();
  const currencies=Array.from(new Set(eligible.map(route=>normalized(route.payoutCurrency)))).sort();
  return countries.map(country=>({country,cells:currencies.map(currency=>({currency,count:eligible.filter(route=>(normalized(route.destinationCountry)||'GLOBAL')===country&&normalized(route.payoutCurrency)===currency).length}))}));
}

export function buildProviderComparison(providers:TransferProvider[],routes:TransferRoute[]){
  return providers.map(provider=>({provider,routes:routes.filter(route=>route.providerId===provider.id)}));
}
```

- [ ] **Step 4: Build the route view model**

```ts
// viewModels/admin-routes.ts
import * as ko from 'knockout';
import {resolveAdminEnvironment} from '../services/admin-console';
import {RoutingWorkspace} from '../services/routing-workspace';
import {buildCorridorMatrix,buildProviderComparison,evaluateRouteEligibility,routeNeedsAttention} from '../services/route-analysis';
import {navigate,session} from '../services/session';

class AdminRoutesViewModel {
  session=session;workspace=new RoutingWorkspace();
  environment=resolveAdminEnvironment((window as any).FLUXPAY_ENVIRONMENT,window.location.hostname);
  mode=ko.observable<'CATALOGUE'|'MATRIX'|'COMPARE'|'PREVIEW'>('CATALOGUE');
  attentionOnly=ko.observable(false);
  previewCountry=ko.observable('');previewCurrency=ko.observable('');previewAmount=ko.observable('');previewDestination=ko.observable('EXTERNAL_ACCOUNT');previewSubmitted=ko.observable(false);
  catalogueRoutes=ko.pureComputed(()=>this.workspace.filteredRoutes().filter(route=>!this.attentionOnly()||routeNeedsAttention(route,this.workspace.providers().find(provider=>provider.id===route.providerId))));
  matrix=ko.pureComputed(()=>buildCorridorMatrix(this.workspace.routes(),this.workspace.providers()));
  comparison=ko.pureComputed(()=>buildProviderComparison(this.workspace.providers(),this.workspace.routes()));
  preview=ko.pureComputed(()=>evaluateRouteEligibility({country:this.previewCountry(),currency:this.previewCurrency(),amount:this.previewAmount(),destinationType:this.previewDestination()},this.workspace.routes(),this.workspace.providers(),this.workspace.railTypes()));
  private routeId:string;
  constructor(params:any){const mode=String(params?.params?.view||'CATALOGUE').toUpperCase();if(['CATALOGUE','MATRIX','COMPARE','PREVIEW'].includes(mode))this.mode(mode as any);this.attentionOnly(String(params?.params?.status||'').toUpperCase()==='ATTENTION');this.routeId=String(params?.params?.routeId||'');void this.activate();}
  private async activate(){if(!session.user())await session.restore();if(!session.isAdmin())return;await this.workspace.loadAll();const route=this.workspace.routes().find(item=>item.id===this.routeId);if(route)this.workspace.editRoute(route);}
  changeMode=()=>navigate('admin-routes',{view:this.mode(),...(this.attentionOnly()?{status:'ATTENTION'}:{})});
  runPreview=()=>{this.previewSubmitted(true);return false;};
  disconnected(){this.workspace.dispose();}
}
export = AdminRoutesViewModel;
```

- [ ] **Step 5: Create the route configuration template**

The page header contains one `Workspace view` dropdown bound to `mode` with `event:{change:changeMode}` so the selected mode remains in the router URL. `CATALOGUE` renders `catalogueRoutes`, the dense current route fields, an `Attention only` text indicator when `attentionOnly()` is true, and a keyboard-native `Edit` button per row that opens an editor whose form calls `requestRouteSave`; its confirmation dialog renders `routeChanges`, `$root.environment.label`, and the affected `destinationCountry / payoutCurrency / destinationType` corridor before `confirmRouteSave`. Its primary button text is `Apply in <environment>`. `MATRIX` renders the matrix cells with text `Available · N routes` or `Unavailable · 0 routes`. `COMPARE` renders provider, route, country, currency, destination type, base fee, spread, ETA, configured/effective reliability, min/max limits, and active/archive state on one common table; it has no score, rank, winner, or recommendation column.

Use this exact preview structure for `PREVIEW`:

```html
<form class="eligibility-inputs" data-bind="submit:runPreview"><label>Country<input maxlength="2" data-bind="textInput:previewCountry"></label><label>Currency<input maxlength="3" data-bind="textInput:previewCurrency"></label><label>Amount<input inputmode="decimal" data-bind="textInput:previewAmount"></label><label>Payout method<select data-bind="value:previewDestination"><option value="EXTERNAL_ACCOUNT">External account</option><option value="INTERNAL_WALLET">Internal wallet</option></select></label><button class="pill dark">Evaluate configuration</button></form>
<p class="subtle-note">Configuration preview only. This does not create a payment, calculate proceeds, call smart routing, or recommend a winner.</p>
<!-- ko if:previewSubmitted --><div class="alert error" role="alert" data-bind="visible:preview().inputErrors.length,text:preview().inputErrors.join(' ')"></div><table class="dense-table"><thead><tr><th>Route</th><th>Result</th><th>Reasons</th><th>Fee</th><th>Spread</th><th>ETA</th><th>Effective reliability</th></tr></thead><tbody data-bind="foreach:preview().routes"><tr><td data-bind="text:route.routeCode"></td><td><span class="status" data-bind="text:eligible?'Eligible':'Ineligible',attr:{'data-status':eligible?'ACTIVE':'WARNING'}"></span></td><td data-bind="text:reasons.length?reasons.join(' '):'All loaded configuration checks passed.'"></td><td data-bind="text:route.baseFee"></td><td data-bind="text:route.fxSpreadPercentage"></td><td data-bind="text:route.estimatedMinutes+' min'"></td><td data-bind="text:route.effectiveSuccessRate+'%'"></td></tr></tbody></table><!-- /ko -->
```

Do not bind `requestDeleteRoute`, `deleteRoute`, or any delete/archive control.

- [ ] **Step 6: Update routing tests, run them, and commit**

Point route template assertions in `routing.test.cjs` to `admin-routes.html`. Assert the four view values, corridor text, provider comparison, preview disclaimer, and absence of route deletion bindings.

Run: `cd frontend/fluxpay-ui; node --test tests/admin-routing-console.test.cjs tests/routing.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/services/route-analysis.ts frontend/fluxpay-ui/src/ts/viewModels/admin-routes.ts frontend/fluxpay-ui/src/ts/views/admin-routes.html frontend/fluxpay-ui/tests/admin-routing-console.test.cjs frontend/fluxpay-ui/tests/routing.test.cjs
git commit -m "feat(admin): add payout route operations workspace"
```

### Task 7: Build the Policy Library configuration workspace

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-policies.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/admin-policies.html`
- Create: `frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs`
- Modify: `frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts:115-360`
- Modify: `frontend/fluxpay-ui/tests/compliance.test.cjs`

**Interfaces:**

- Consumes: current policy, chunk, guidance, import, and index methods in `ComplianceWorkspace`; `diffFields`; `resolveAdminEnvironment`; optional URL parameter `policyId`.
- Produces: `policyChanges`, `policyChangeReview`, `requestPolicyEditSave`, `confirmPolicyEditSave`, `draftPublishReview`, and confirmed guidance deletion.
- Produces: route module `admin-policies` with `LIBRARY` and `ADVANCED` views.

- [ ] **Step 1: Write failing tests for reviewed edits, draft publication confirmation, exact fields, and hidden deletion**

```js
// tests/admin-policy-copilot.test.cjs
test('policy edit shows current-document changes before calling the existing update API', async () => {
  const {page,calls}=complianceWorkspace();
  page.openPolicyEdit(policy);
  page.policyEdit().title('Updated transfer review');
  page.policyEdit().clearExistingChunks(false);
  page.requestPolicyEditSave();
  assert.equal(calls.filter(call=>call[0]==='updatePolicy').length,0);
  assert.deepEqual(JSON.parse(JSON.stringify(page.policyChanges())),[
    {field:'title',label:'Title',before:'Transfer review',after:'Updated transfer review'},
    {field:'clearExistingChunks',label:'Clear existing chunks',before:'Yes',after:'No'}
  ]);
  await page.confirmPolicyEditSave();
  assert.equal(calls.filter(call=>call[0]==='updatePolicy').length,1);
});

test('policy template exposes only current API metadata and hides document deletion', () => {
  const html=read('ts/views/admin-policies.html');
  for(const token of ['Current document','documentHash','createdAt','Indexed chunks','Advanced']) assert.ok(html.includes(token),token);
  assert.doesNotMatch(html,/Policy version|Effective date|Publication status|Index failure|Policy owner/i);
  assert.doesNotMatch(html,/delete-policy|deletePolicy|Delete policy/i);
});
```

Add tests proving `requestPolicyDraftPublish` makes no POST until confirmation, failed batch entries remain in their original order, `policyId` deep linking opens the current document, and guidance/chunk deletion remains available only inside Advanced with a confirmation dialog.

- [ ] **Step 2: Run policy tests and confirm direct-save behavior fails**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-policy-copilot.test.cjs tests/compliance.test.cjs`

Expected: FAIL because the focused policy page and reviewed policy-save state do not exist.

- [ ] **Step 3: Add policy review state without inventing server versions**

```ts
// services/compliance-workspace.ts
import {diffFields,FieldChange} from './admin-console';

policyChanges=ko.observableArray<FieldChange>([]);
policyChangeReview=ko.observable(false);
draftPublishReview=ko.observable(false);
pendingGuidance=ko.observable<PolicyGuidance>();
policySavedView=ko.observable<'ALL'|'UNINDEXED'>('ALL');
savedPolicies=ko.pureComputed(()=>this.filteredPolicies().filter(item=>this.policySavedView()==='ALL'||!(item.chunks||[]).some(chunk=>!chunk.manual)));
visiblePolicies=ko.pureComputed(()=>{
  const filtered=this.savedPolicies();
  return filtered.slice(this.policyPage()*this.policyPageSize,(this.policyPage()+1)*this.policyPageSize);
});

requestPolicyEditSave=()=>{
  const draft=this.policyEdit();
  if(this.busy()||!draft)return;
  let payload:{title:string;category:string;content:string;clearExistingChunks:boolean};
  try{payload={...this.policyDraftPayload(draft),clearExistingChunks:draft.clearExistingChunks()};}
  catch(error:any){draft.error(error.message);return;}
  const current=this.policies().find(item=>item.id===draft.id)||this.policy();
  const baseline={...(current||{}),clearExistingChunks:true};
  this.policyChanges(diffFields(baseline,payload,{
    title:'Title',category:'Category',content:'Content',clearExistingChunks:'Clear existing chunks'
  },['title','category','content','clearExistingChunks']));
  if(!this.policyChanges().length){draft.error('No policy changes to review.');return;}
  this.policyChangeReview(true);
};
confirmPolicyEditSave=async()=>{if(!this.policyChangeReview())return;this.policyChangeReview(false);await this.savePolicyEdit();};
requestPolicyDraftPublish=()=>{
  if(this.busy()||!this.policyDrafts().length)return;
  try{this.policyDrafts().forEach(draft=>this.policyDraftPayload(draft));}
  catch(error:any){this.policyImportError(error.message);return;}
  this.draftPublishReview(true);
};
confirmPolicyDraftPublish=async()=>{if(!this.draftPublishReview())return;this.draftPublishReview(false);await this.createPolicyDrafts();};
requestDeleteGuidance=(item:PolicyGuidance)=>{if(!this.busy()){this.pendingGuidance(item);this.confirmation('delete-guidance');}};
```

Extend the existing confirmation union with `'delete-guidance'`. In `confirm`, require the selected policy and `pendingGuidance`, call the existing `deletePolicyGuidance`, clear the pending item, reload the current policy, and report `Policy guidance deleted.` Reset all new review state in `clear`, editor close methods, and cancellation. Keep `deletePolicy` and the existing endpoint wrapper untouched but unbound.
Replace the existing `policyPageCount` computed so it uses `savedPolicies().length`, and reset `policyPage(0)` whenever `policySavedView` changes.

- [ ] **Step 4: Add a route wrapper that supports policy-source deep links**

```ts
// viewModels/admin-policies.ts
import {resolveAdminEnvironment} from '../services/admin-console';
import {ComplianceWorkspace} from '../services/compliance-workspace';
import {navigate,session} from '../services/session';

class AdminPoliciesViewModel {
  session=session;workspace=new ComplianceWorkspace();
  environment=resolveAdminEnvironment((window as any).FLUXPAY_ENVIRONMENT,window.location.hostname);
  private policyId:string;
  constructor(params:any){this.policyId=String(params?.params?.policyId||'');this.workspace.policySavedView(String(params?.params?.view||'').toUpperCase()==='UNINDEXED'?'UNINDEXED':'ALL');void this.activate();}
  private async activate(){
    if(!session.user())await session.restore();
    if(!session.isAdmin())return;
    await this.workspace.loadPolicies();
    if(this.policyId&&this.workspace.policies().some(item=>item.id===this.policyId))await this.workspace.openPolicy({id:this.policyId});
  }
  changeSavedView=()=>navigate('admin-policies',{view:this.workspace.policySavedView()});
  disconnected(){this.workspace.dispose();}
}
export = AdminPoliciesViewModel;
```

- [ ] **Step 5: Create the Policy Library template**

```html
<section class="admin-page configuration-page" data-bind="with:workspace">
  <header class="admin-page-heading"><div><span class="eyebrow">POLICY &amp; AI</span><h1>Policy Library</h1><p>Maintain the current policy documents and their indexed source material.</p></div><button class="pill dark" data-bind="click:addManualPolicyDraft,disable:busy">New policy</button></header>
  <div class="configuration-workspace"><section class="configuration-list"><div class="filter-row"><label>Search<input type="search" data-bind="textInput:search"></label><label>Category<select data-bind="value:categoryFilter"><option value="ALL">All categories</option><!-- ko foreach:categories --><option data-bind="text:$data,value:$data"></option><!-- /ko --></select></label><label>Index view<select data-bind="value:policySavedView,event:{change:$parent.changeSavedView}"><option value="ALL">All documents</option><option value="UNINDEXED">Without indexed chunks</option></select></label></div><table class="dense-table"><thead><tr><th>Title</th><th>Category</th><th>Document</th><th>Created</th><th>Indexed chunks</th><th></th></tr></thead><tbody data-bind="foreach:visiblePolicies"><tr><td data-bind="text:title"></td><td data-bind="text:category"></td><td>Current document</td><td data-bind="text:$parent.date(createdAt)"></td><td data-bind="text:(chunks||[]).filter(item=>!item.manual).length"></td><td><button type="button" class="text-button" data-bind="click:$parent.openPolicy">Open</button></td></tr></tbody></table></section>
  <aside class="configuration-editor" data-bind="with:policy"><h2 data-bind="text:title"></h2><dl class="detail-grid"><div><dt>Document hash</dt><dd class="mono" data-bind="text:documentHash"></dd></div><div><dt>Created</dt><dd data-bind="text:$parent.date(createdAt)"></dd></div></dl><p class="preserve-text" data-bind="text:content"></p><div class="action-row"><button class="pill soft" data-bind="click:$parent.openPolicyEdit">Edit current document</button><button class="pill dark" data-bind="click:function(){$parent.askConfirmation('index')}">Rebuild index</button></div><label>Details view<select data-bind="value:$parent.policyView"><option value="policy">Policy</option><option value="advanced">Advanced</option></select></label></aside></div>
</section>
```

Render the existing import and draft table above the library when drafts exist; its primary action calls `requestPolicyDraftPublish`, then a dialog lists every draft title/category and `$root.environment.label` before `confirmPolicyDraftPublish`. The edit dialog calls `requestPolicyEditSave`, and its review dialog displays `policyChanges` plus `Apply in <environment>` before `confirmPolicyEditSave`.

When `policyView()==='advanced'`, render raw chunks and guidance with `manual`, `chunkNumber`, and `createdAt`; retain manual-chunk editing, confirmed chunk deletion, guidance creation/editing, and confirmed guidance deletion. Do not show raw chunks in the main policy view and do not render a policy deletion control.

- [ ] **Step 6: Update policy assertions and run the focused tests**

Move policy template assertions in `compliance.test.cjs` from `admin.html` to `admin-policies.html`. Replace the old policy-delete flow test with a service-only endpoint test plus a template assertion that no policy-level delete action is bound. Keep chunk/guidance endpoint tests.

Run: `cd frontend/fluxpay-ui; node --test tests/admin-policy-copilot.test.cjs tests/compliance.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

- [ ] **Step 7: Commit the Policy Library**

```powershell
git add frontend/fluxpay-ui/src/ts/services/compliance-workspace.ts frontend/fluxpay-ui/src/ts/viewModels/admin-policies.ts frontend/fluxpay-ui/src/ts/views/admin-policies.html frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs frontend/fluxpay-ui/tests/compliance.test.cjs
git commit -m "feat(admin): add policy configuration workspace"
```

### Task 8: Build the cited Compliance Copilot workspace

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/viewModels/admin-copilot.ts`
- Create: `frontend/fluxpay-ui/src/ts/views/admin-copilot.html`
- Modify: `frontend/fluxpay-ui/src/ts/services/admin-console.ts`
- Modify: `frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/compliance.test.cjs`

**Interfaces:**

- Consumes: `ComplianceWorkspace.ask`, `fluxApi.complianceCase`, `CopilotAnswer.sources`, URL parameters `caseId` and `paymentId`, and `navigate`.
- Produces: `copilotQuestionForCase`, an optional response-backed case context panel, a cited non-streaming request action, and policy-source navigation using `{policyId}`.

- [ ] **Step 1: Write failing tests for cited answers, case context, safe rendering, and advisory boundaries**

```js
// tests/admin-policy-copilot.test.cjs (Copilot section)
test('case context pre-fills a cited question without inventing payment details', async () => {
  const {page,calls}=copilotPage({params:{caseId:openCase.id,paymentId:openCase.paymentId}});
  await page.ready;
  assert.equal(calls[0][0],'complianceCase');
  assert.match(page.workspace.question(),/Risk level: HIGH/);
  assert.match(page.workspace.question(),/Triggered reasons:/);
  assert.doesNotMatch(page.workspace.question(),/customer country|amount|currency/i);
});

test('Copilot template keeps citations visible and exposes no decision action', () => {
  const html=read('ts/views/admin-copilot.html');
  assert.match(html,/policyAnswer:answer\(\)\.answer/);
  for(const field of ['policyDocumentId','title','chunkNumber','excerpt']) assert.ok(html.includes(field),field);
  assert.match(html,/Advisory only/);
  assert.doesNotMatch(html,/Approve|Reject|Activate|Publish/);
  assert.doesNotMatch(html,/policy version/i);
  assert.doesNotMatch(html,/data-bind="[^"]*\bhtml\s*:/);
});
```

Add tests that `askCited` calls only `askCopilot`, missing sources render an explicit “No policy sources returned” state, provider errors preserve the question, and `openSource` navigates to `admin-policies` with the source document ID.

- [ ] **Step 2: Run focused Copilot tests and verify the route module is absent**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-policy-copilot.test.cjs`

Expected: FAIL because the focused Copilot module and case-question helper do not exist.

- [ ] **Step 3: Add a pure case-question formatter**

```ts
// services/admin-console.ts
export function copilotQuestionForCase(item:{risk:string;riskReasons:string[];suggestedAction:string}):string {
  const reasons=item.riskReasons.filter(Boolean).join('; ')||'No risk reasons recorded.';
  const action=item.suggestedAction.trim()||'No suggested action recorded.';
  return [
    'Which policies are relevant to this payment review?',
    '',
    'Case context:',
    `- Risk level: ${item.risk}`,
    `- Triggered reasons: ${reasons}`,
    `- Suggested action: ${action}`,
    '',
    'Identify the relevant policy checks. Do not make the decision.'
  ].join('\n');
}
```

- [ ] **Step 4: Implement the Copilot route view model**

```ts
// viewModels/admin-copilot.ts
import * as ko from 'knockout';
import {ComplianceWorkspace} from '../services/compliance-workspace';
import {fluxApi} from '../services/flux-api';
import type {ComplianceCase} from '../services/flux-api';
import {copilotQuestionForCase} from '../services/admin-console';
import {navigate,session} from '../services/session';

class AdminCopilotViewModel {
  session=session;workspace=new ComplianceWorkspace();caseContext=ko.observable<ComplianceCase>();
  private caseId:string;private paymentId:string;ready:Promise<void>;
  constructor(params:any){this.caseId=String(params?.params?.caseId||'');this.paymentId=String(params?.params?.paymentId||'');this.ready=this.activate();}
  private async activate(){
    if(!session.user())await session.restore();
    if(!session.isAdmin())return;
    this.workspace.copilotPaymentId(this.paymentId);
    if(this.caseId)await this.workspace.run(async()=>{const item=await fluxApi.complianceCase(this.caseId);this.caseContext(item);this.workspace.copilotPaymentId(item.paymentId);this.workspace.question(copilotQuestionForCase(item));});
  }
  askCited=()=>{this.workspace.liveResponse(false);void this.workspace.ask();return false;};
  openSource=(source:{policyDocumentId:string})=>navigate('admin-policies',{policyId:source.policyDocumentId});
  disconnected(){this.workspace.dispose();}
}
export = AdminCopilotViewModel;
```

The existing streaming API helper may remain for compatibility, but this page must not expose the live-response toggle because streaming responses have no citations.

- [ ] **Step 5: Create the persistent-source Copilot template**

```html
<section class="admin-page copilot-page" data-bind="with:workspace">
  <header class="admin-page-heading"><div><span class="eyebrow">POLICY &amp; AI</span><h1>Compliance Copilot</h1><p>Ask policy questions and inspect every returned source.</p></div><span class="advisory-badge"><i class="fa-solid fa-circle-info" aria-hidden="true"></i> Advisory only</span></header>
  <div class="copilot-workspace"><aside class="copilot-context"><h2>Case context</h2><!-- ko if:$parent.caseContext --><dl class="detail-grid" data-bind="with:$parent.caseContext"><div><dt>Case</dt><dd data-bind="text:id"></dd></div><div><dt>Payment</dt><dd data-bind="text:paymentId"></dd></div><div><dt>Risk</dt><dd data-bind="text:risk"></dd></div><div><dt>Status</dt><dd data-bind="text:status"></dd></div></dl><!-- /ko --><!-- ko ifnot:$parent.caseContext --><p>No case context supplied. You can optionally enter a payment ID.</p><!-- /ko --><label>Payment ID<input data-bind="textInput:copilotPaymentId"></label></aside>
  <main class="copilot-conversation"><form data-bind="submit:$parent.askCited"><label>Policy question<textarea rows="6" data-bind="textInput:question" required></textarea></label><button class="pill dark" data-bind="disable:busy">Ask with citations</button></form><!-- ko if:answer --><article class="copilot-answer"><h2>Answer</h2><p class="preserve-text" data-bind="policyAnswer:answer().answer"></p><p class="subtle-note">Review the cited policies before taking any administrative action.</p></article><!-- /ko --></main>
  <aside class="copilot-sources" aria-label="Source policies"><h2>Source policies</h2><!-- ko if:answer()&&answer().sources.length --><div data-bind="foreach:answer().sources"><button type="button" class="source-row" data-bind="click:$parents[1].openSource"><strong data-bind="text:title"></strong><small data-bind="text:'Policy '+policyDocumentId+' · chunk '+chunkNumber"></small><blockquote data-bind="text:excerpt"></blockquote></button></div><!-- /ko --><!-- ko if:answer()&&!answer().sources.length --><p class="empty-state">No policy sources returned. Do not rely on this answer for a decision.</p><!-- /ko --></aside></div>
</section>
```

- [ ] **Step 6: Run Copilot and safe-rendering tests, then commit**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-policy-copilot.test.cjs tests/compliance.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/services/admin-console.ts frontend/fluxpay-ui/src/ts/viewModels/admin-copilot.ts frontend/fluxpay-ui/src/ts/views/admin-copilot.html frontend/fluxpay-ui/tests/admin-policy-copilot.test.cjs frontend/fluxpay-ui/tests/compliance.test.cjs
git commit -m "feat(admin): add cited compliance Copilot workspace"
```

### Task 9: Replace the tab host with the risk-prioritized Overview

**Files:**

- Create: `frontend/fluxpay-ui/src/ts/services/admin-overview.ts`
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/admin.ts`
- Modify: `frontend/fluxpay-ui/src/ts/views/admin.html`
- Modify: `frontend/fluxpay-ui/tests/admin-console.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/admin-navigation.test.cjs`

**Interfaces:**

- Consumes: existing KYC, compliance, ticket, provider, route, and policy list methods; `Promise.allSettled`; `navigate`.
- Produces: `deriveAdminOverview`, `OverviewMetric`, `AttentionRow`, independent source states, source-specific retry, and metric navigation params.

- [ ] **Step 1: Write failing derivation and partial-failure tests**

```js
// tests/admin-console.test.cjs (Overview section)
test('overview derives only API-backed attention and marks a full KYC page as 100+', () => {
  const result=overview.deriveAdminOverview({
    kyc:Array.from({length:100},(_,index)=>kyc({applicationId:String(index),submittedAt:'2026-09-20T00:00:00Z'})),
    cases:[compliance({id:'high',risk:'HIGH',status:'OPEN'})],
    tickets:[ticket({id:'old',status:'OPEN',createdAt:'2026-09-20T00:00:00Z'})],
    providers:[provider({id:'provider',active:true,archivedAt:null})],
    routes:[route({id:'degraded',providerId:'provider',completedCount:8,failedCount:2,configuredSuccessRate:99,effectiveSuccessRate:80})],
    policies:[policy({id:'unindexed',chunks:[{manual:true}]})],
    now:Date.parse('2026-09-22T12:00:00Z')
  });
  assert.equal(result.metrics.find(item=>item.id==='kyc-pending').value,'100+');
  assert.equal(result.metrics.find(item=>item.id==='compliance-high').value,'1');
  assert.equal(result.metrics.find(item=>item.id==='routes-attention').value,'1');
  assert.equal(result.metrics.find(item=>item.id==='policies-unindexed').value,'1');
});

test('one rejected overview source leaves fulfilled sources usable', async () => {
  const {page,api}=overviewPage();
  api.adminKyc.reject(new Error('KYC unavailable'));
  api.complianceCases.resolve([compliance({id:'high',risk:'HIGH',status:'OPEN'})]);
  api.listForAdmin.resolve({items:[],total:0});api.providers.resolve([]);api.routesAdmin.resolve([]);api.policies.resolve([]);
  await page.loadOverview();
  assert.match(page.sources.kyc().error,/KYC unavailable/);
  assert.equal(page.sources.compliance().status,'ready');
  assert.equal(page.metrics().find(item=>item.id==='kyc-pending').value,'Unavailable');
  assert.equal(page.metrics().find(item=>item.id==='compliance-high').value,'1');
});
```

Add tests for pending/aging KYC, inactive/archived/degraded routes including inactive providers, open/in-progress/aging tickets, policies with no non-manual chunks, and severity-then-age row ordering. Also prove that loading metrics never display a false zero, a provider-source failure suppresses route-derived rows, simultaneous retries for different sources both complete, and a late result is ignored after session clear or `disconnected`.

- [ ] **Step 2: Run Overview tests and verify current tab-host behavior fails**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-console.test.cjs tests/admin-navigation.test.cjs`

Expected: FAIL because `admin-overview.ts` and independent Overview state do not exist.

- [ ] **Step 3: Implement pure Overview derivation**

```ts
// services/admin-overview.ts
import type {ComplianceCase,KycAdminRow,PolicyDocument,TicketResponse,TransferProvider,TransferRoute} from './flux-api';
import {isOlderThanHours,prioritizeComplianceCases} from './admin-console';
import {routeNeedsAttention} from './route-analysis';

export interface OverviewInput {kyc:KycAdminRow[];cases:ComplianceCase[];tickets:TicketResponse[];providers:TransferProvider[];routes:TransferRoute[];policies:PolicyDocument[];now:number;}
export interface OverviewMetric {id:string;label:string;value:string;tone:'critical'|'warning'|'info';path:string;params:Record<string,string>;sourceKeys:string[];unavailable?:boolean;pending?:boolean;}
export interface AttentionRow {id:string;area:string;summary:string;tone:'critical'|'warning'|'info';createdAt:string;path:string;params:Record<string,string>;sourceKeys:string[];}

export function deriveAdminOverview(input:OverviewInput){
  const pending=input.kyc.filter(item=>item.status==='PENDING');
  const agingKyc=pending.filter(item=>isOlderThanHours(item.submittedAt,24,input.now));
  const highCases=input.cases.filter(item=>item.status==='OPEN'&&item.risk==='HIGH');
  const providerById=new Map(input.providers.map(item=>[item.id,item]));
  const routeAttention=input.routes.filter(item=>routeNeedsAttention(item,providerById.get(item.providerId)));
  const openTickets=input.tickets.filter(item=>['OPEN','IN_PROGRESS'].includes(item.status));
  const agingTickets=openTickets.filter(item=>isOlderThanHours(item.createdAt,24,input.now));
  const unindexed=input.policies.filter(item=>!(item.chunks||[]).some(chunk=>!chunk.manual));
  const metrics:OverviewMetric[]=[
    {id:'compliance-high',label:'High-risk compliance',value:String(highCases.length),tone:'critical',path:'admin-compliance',params:{view:'HIGH_RISK'},sourceKeys:['compliance']},
    {id:'kyc-pending',label:'Pending KYC',value:input.kyc.length===100?'100+':String(pending.length),tone:'warning',path:'admin-kyc',params:{view:'PENDING'},sourceKeys:['kyc']},
    {id:'kyc-aging',label:'KYC over 24 hours',value:String(agingKyc.length),tone:'warning',path:'admin-kyc',params:{view:'AGING'},sourceKeys:['kyc']},
    {id:'routes-attention',label:'Routes needing attention',value:String(routeAttention.length),tone:'critical',path:'admin-routes',params:{view:'CATALOGUE',status:'ATTENTION'},sourceKeys:['routes','providers']},
    {id:'tickets-open',label:'Open support work',value:String(openTickets.length),tone:'info',path:'admin-tickets',params:{view:'OPEN'},sourceKeys:['tickets']},
    {id:'tickets-aging',label:'Tickets over 24 hours',value:String(agingTickets.length),tone:'warning',path:'admin-tickets',params:{view:'AGING'},sourceKeys:['tickets']},
    {id:'policies-unindexed',label:'Policies without indexed chunks',value:String(unindexed.length),tone:'warning',path:'admin-policies',params:{view:'UNINDEXED'},sourceKeys:['policies']}
  ];
  const rows:AttentionRow[]=[
    ...prioritizeComplianceCases(highCases).map(item=>({id:item.id,area:'Compliance',summary:`High-risk case · ${item.id}`,tone:'critical' as const,createdAt:item.createdAt,path:'admin-compliance',params:{caseId:item.id,view:'HIGH_RISK'},sourceKeys:['compliance']})),
    ...routeAttention.map(item=>({id:item.id,area:'Routing',summary:`Route needs attention · ${item.routeCode}`,tone:'critical' as const,createdAt:'',path:'admin-routes',params:{routeId:item.id,view:'CATALOGUE'},sourceKeys:['routes','providers']})),
    ...agingKyc.map(item=>({id:item.applicationId,area:'KYC',summary:`Aging KYC review · ${item.fullName}`,tone:'warning' as const,createdAt:item.submittedAt,path:'admin-kyc',params:{applicationId:item.applicationId,view:'AGING'},sourceKeys:['kyc']})),
    ...agingTickets.map(item=>({id:item.id,area:'Support',summary:`Aging support ticket · ${item.subject}`,tone:'warning' as const,createdAt:item.createdAt,path:'admin-tickets',params:{ticketId:item.id,view:'AGING'},sourceKeys:['tickets']})),
    ...unindexed.map(item=>({id:item.id,area:'Policy',summary:`No indexed chunks · ${item.title}`,tone:'warning' as const,createdAt:item.createdAt,path:'admin-policies',params:{policyId:item.id},sourceKeys:['policies']}))
  ];
  const toneRank={critical:0,warning:1,info:2};
  const rowTime=(row:AttentionRow)=>{const value=Date.parse(row.createdAt);return Number.isFinite(value)?value:Number.POSITIVE_INFINITY;};
  const orderedRows=rows.map((row,index)=>({row,index})).sort((left,right)=>
    toneRank[left.row.tone]-toneRank[right.row.tone] ||
    rowTime(left.row)-rowTime(right.row) ||
    left.index-right.index).map(entry=>entry.row);
  return {metrics,rows:orderedRows};
}
```

- [ ] **Step 4: Replace `admin.ts` with independent all-settled loading**

```ts
// viewModels/admin.ts
import * as ko from 'knockout';
import {deriveAdminOverview} from '../services/admin-overview';
import {fluxApi,ticketApi} from '../services/flux-api';
import {navigate,session} from '../services/session';

type SourceStatus<T>={status:'loading'|'ready'|'error';data:T;error:string};
type SourceKey='kyc'|'compliance'|'tickets'|'providers'|'routes'|'policies';
const source=<T>(empty:T)=>ko.observable<SourceStatus<T>>({status:'loading',data:empty,error:''});

class AdminOverviewViewModel {
  session=session;private epoch=0;private disposed=false;
  private sourceEpoch:Record<SourceKey,number>={kyc:0,compliance:0,tickets:0,providers:0,routes:0,policies:0};
  sources={kyc:source<any[]>([]),compliance:source<any[]>([]),tickets:source<any[]>([]),providers:source<any[]>([]),routes:source<any[]>([]),policies:source<any[]>([])};
  overview=ko.pureComputed(()=>deriveAdminOverview({kyc:this.sources.kyc().data,cases:this.sources.compliance().data,tickets:this.sources.tickets().data,providers:this.sources.providers().data,routes:this.sources.routes().data,policies:this.sources.policies().data,now:Date.now()}));
  metrics=ko.pureComputed(()=>this.overview().metrics.map(metric=>{
    const states=metric.sourceKeys.map(key=>(this.sources as any)[key]().status);
    return states.includes('error')?{...metric,value:'Unavailable',unavailable:true}:
      states.includes('loading')?{...metric,value:'Loading…',pending:true}:metric;
  }));
  rows=ko.pureComputed(()=>this.overview().rows.filter(row=>row.sourceKeys.every(key=>(this.sources as any)[key]().status==='ready')));
  sourceEntries=ko.pureComputed(()=>Object.entries(this.sources));
  loadAnnouncement=ko.pureComputed(()=>{
    const states=this.sourceEntries().map(entry=>entry[1]().status);
    return states.includes('loading')?'Loading administrator attention data.':states.includes('error')?'Overview loaded with unavailable sources.':'Overview data loaded.';
  });
  private sessionChanged=session.user.subscribe(user=>{if(!user){this.epoch++;(Object.values(this.sources) as Array<ko.Observable<SourceStatus<any[]>>>).forEach(target=>target({status:'loading',data:[],error:''}));}});
  constructor(){void this.loadOverview();}
  async loadOverview(){
    if(!session.user())await session.restore();
    if(!session.isAdmin())return;
    const epoch=++this.epoch;
    (Object.values(this.sources) as Array<ko.Observable<SourceStatus<any[]>>>).forEach(target=>target({...target(),status:'loading',error:''}));
    const requests:Array<Promise<any[]>>=[fluxApi.adminKyc('PENDING',0,100),fluxApi.complianceCases('OPEN'),ticketApi.listForAdmin('ALL',0,100).then(result=>result.items||[]),fluxApi.providers(),fluxApi.routesAdmin(),fluxApi.policies()];
    const keys=['kyc','compliance','tickets','providers','routes','policies'] as const;
    const results=await Promise.allSettled(requests);
    if(this.disposed||epoch!==this.epoch||!session.isAdmin())return;
    results.forEach((result,index)=>{const target=this.sources[keys[index]] as ko.Observable<SourceStatus<any[]>>;target(result.status==='fulfilled'?{status:'ready',data:result.value,error:''}:{status:'error',data:[],error:result.reason?.message||'This source is unavailable.'});});
  }
  async retrySource(key:SourceKey){
    if(!session.isAdmin())return;
    const epoch=this.epoch,sourceEpoch=++this.sourceEpoch[key],target=this.sources[key];target({...target(),status:'loading',error:''});
    try{
      const data=key==='kyc'?await fluxApi.adminKyc('PENDING',0,100):key==='compliance'?await fluxApi.complianceCases('OPEN'):key==='tickets'?(await ticketApi.listForAdmin('ALL',0,100)).items||[]:key==='providers'?await fluxApi.providers():key==='routes'?await fluxApi.routesAdmin():await fluxApi.policies();
      if(!this.disposed&&epoch===this.epoch&&sourceEpoch===this.sourceEpoch[key]&&session.isAdmin())target({status:'ready',data,error:''});
    }catch(error:any){if(!this.disposed&&epoch===this.epoch&&sourceEpoch===this.sourceEpoch[key]&&session.isAdmin())target({status:'error',data:[],error:error.message||'This source is unavailable.'});}
  }
  open=(item:{path:string;params:Record<string,string>;unavailable?:boolean;pending?:boolean})=>{if(!item.unavailable&&!item.pending)navigate(item.path,item.params);};
  disconnected(){this.disposed=true;this.epoch++;this.sessionChanged.dispose();}
}
export = AdminOverviewViewModel;
```

Keep `401` behavior in `flux-api.ts` unchanged.

- [ ] **Step 5: Create the attention-first Overview template**

```html
<section class="admin-page overview-page">
  <header class="admin-page-heading"><div><span class="eyebrow">OVERVIEW</span><h1>Operations requiring attention</h1><p>Live operational signals from the current FluxPay APIs.</p></div><button class="pill soft" data-bind="click:loadOverview">Refresh</button></header>
  <p class="sr-only" role="status" aria-live="polite" data-bind="text:loadAnnouncement"></p>
  <div class="attention-metrics" data-bind="foreach:metrics"><button type="button" class="attention-metric" data-bind="click:$parent.open,disable:unavailable||pending,attr:{'data-tone':tone}"><span class="status-icon" aria-hidden="true"></span><strong data-bind="text:value"></strong><span data-bind="text:label"></span></button></div>
  <section class="attention-table"><div class="section-heading"><h2>Prioritized work</h2><p>Critical risk first, then warnings and age.</p></div><table class="dense-table"><thead><tr><th>Area</th><th>Attention</th><th>Age</th><th></th></tr></thead><tbody data-bind="foreach:rows"><tr><td data-bind="text:area"></td><td><span class="status" data-bind="text:tone,attr:{'data-status':tone}"></span><strong data-bind="text:summary"></strong></td><td data-bind="text:createdAt||'Configuration state'"></td><td><button class="text-button" data-bind="click:$parent.open">Open filtered queue</button></td></tr></tbody></table></section>
  <section class="source-health" aria-label="Overview data sources"><!-- ko foreach:sourceEntries --><!-- ko if:$data[1]().status==='error' --><div class="inline-error" role="alert"><span data-bind="text:$data[0]+' unavailable: '+$data[1]().error"></span><button data-bind="click:function(){$parent.retrySource($data[0])}">Retry</button></div><!-- /ko --><!-- /ko --></section>
</section>
```

Do not add charts, total-volume cards, revenue, customer growth, recent configuration changes, or audit data.

- [ ] **Step 6: Remove the old tab-host expectations, run tests, and commit**

Delete `adminTab`, `tabs`, `selectTab`, and the old combined-workspace ownership from `admin.ts`. Update navigation tests so `admin` is described as Overview rather than the tabbed Administration page.

Run: `cd frontend/fluxpay-ui; node --test tests/admin-console.test.cjs tests/admin-navigation.test.cjs; npm.cmd run typecheck`

Expected: all commands PASS.

```powershell
git add frontend/fluxpay-ui/src/ts/services/admin-overview.ts frontend/fluxpay-ui/src/ts/viewModels/admin.ts frontend/fluxpay-ui/src/ts/views/admin.html frontend/fluxpay-ui/tests/admin-console.test.cjs frontend/fluxpay-ui/tests/admin-navigation.test.cjs
git commit -m "feat(admin): add risk-prioritized operations overview"
```

### Task 10: Add the persistent administrator shell, route map, and visual system

**Files:**

- Create: `frontend/fluxpay-ui/src/css/admin-console.css`
- Modify: `frontend/fluxpay-ui/src/ts/appController.ts:19-69`
- Modify: `frontend/fluxpay-ui/src/index.html:12-48`
- Modify: `frontend/fluxpay-ui/tests/admin-navigation.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/navigation.test.cjs`
- Modify: `frontend/fluxpay-ui/tests/experience.test.cjs`

**Interfaces:**

- Consumes: all eight route modules, `resolveAdminEnvironment`, current `session`, current `navigate`, and Oracle JET router adapters.
- Produces: `adminNavGroups`, `adminPaths`, `activeAdminPath`, `environment`, persistent labeled sidebar markup, and admin-scoped responsive styles.

- [ ] **Step 1: Replace obsolete no-sidebar tests with failing admin-only shell tests**

```js
// tests/admin-navigation.test.cjs
test('admin shell exposes the approved grouped routes and no Governance group', async () => {
  const f=fixture('admin','ADMIN');await f.settle();
  assert.deepEqual(Array.from(f.root.adminNavGroups,group=>[group.label,Array.from(group.items,item=>item.path)]),[
    ['Overview',['admin']],
    ['Operations',['admin-kyc','admin-compliance','admin-tickets']],
    ['Money Movement',['admin-providers','admin-routes']],
    ['Policy & AI',['admin-policies','admin-copilot']]
  ]);
  assert.equal(f.root.isAdminWorkspace(),true);
  assert.equal(f.root.activeAdminPath(),'admin');
  assert.ok(!JSON.stringify(f.root.adminNavGroups).includes('Audit'));
  assert.ok(!JSON.stringify(f.root.adminNavGroups).includes('Governance'));
});

test('environment display cannot change the API base URL', () => {
  const controller=read('ts/appController.ts');
  const api=read('ts/services/flux-api.ts');
  assert.match(controller,/FLUXPAY_ENVIRONMENT/);
  assert.match(api,/FLUXPAY_API_URL/);
  assert.doesNotMatch(api,/FLUXPAY_ENVIRONMENT/);
  assert.doesNotMatch(controller,/API_PROXY/);
});

test('sidebar markup is guarded to administrator routes and keeps labels visible', () => {
  const html=read('index.html');
  const css=read('css/admin-console.css');
  assert.match(html,/<!-- ko if:isAdminWorkspace -->[\s\S]*class="admin-sidebar"/);
  assert.match(html,/foreach:adminNavGroups/);
  assert.match(html,/attr:\{'aria-current':\$root\.activeAdminPath\(\)===path\?'page':null\}/);
  assert.match(html,/text:environment\.label/);
  assert.doesNotMatch(html,/Audit Log|Governance/);
  assert.match(css,/\.admin-environment\{[^}]*position:sticky/);
});
```

Update `navigation.test.cjs` and `experience.test.cjs` to assert that customer bottom navigation remains unchanged and that the new `.admin-sidebar` is inside the `isAdminWorkspace` Knockout guard. Remove assertions that the source file can never contain any sidebar.
Update the `admin-navigation.test.cjs` VM fixture to supply `window.location.hostname='localhost'`; add a second fixture value with `window.FLUXPAY_ENVIRONMENT='STAGING'` and assert that only `root.environment.label` changes while navigation and API stubs remain identical.

- [ ] **Step 2: Run navigation tests and verify the grouped shell is absent**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-navigation.test.cjs tests/navigation.test.cjs tests/experience.test.cjs`

Expected: FAIL because grouped routes, environment state, and `.admin-sidebar` do not exist.

- [ ] **Step 3: Refactor route metadata without changing auth or redirect behavior**

```ts
// appController.ts
import {resolveAdminEnvironment} from './services/admin-console';

const customerNav=[
  {path:'dashboard',label:'Home',icon:'◫'},{path:'wallets',label:'Wallets',icon:'◉'},
  {path:'payments-new',label:'Send',icon:'↗'},{path:'payments-list',label:'Activity',icon:'⇄'},
  {path:'recipients',label:'Recipients',icon:'◎'},{path:'kyc',label:'Verification',icon:'◇'},
  {path:'account',label:'My account',icon:'○'}
];
adminNavGroups=[
  {label:'Overview',items:[{path:'admin',label:'Overview',icon:'fa-gauge-high'}]},
  {label:'Operations',items:[{path:'admin-kyc',label:'KYC Reviews',icon:'fa-id-card'},{path:'admin-compliance',label:'Compliance Cases',icon:'fa-shield-halved'},{path:'admin-tickets',label:'Support Tickets',icon:'fa-headset'}]},
  {label:'Money Movement',items:[{path:'admin-providers',label:'Providers',icon:'fa-building-columns'},{path:'admin-routes',label:'Payout Routes',icon:'fa-route'}]},
  {label:'Policy & AI',items:[{path:'admin-policies',label:'Policy Library',icon:'fa-book'},{path:'admin-copilot',label:'Compliance Copilot',icon:'fa-robot'}]}
];
adminPaths=this.adminNavGroups.flatMap(group=>group.items.map(item=>item.path));
environment=resolveAdminEnvironment((window as any).FLUXPAY_ENVIRONMENT,window.location.hostname);

// Preserve the existing account and dashboard redirects.
accountPath=ko.pureComputed(()=>session.isAdmin()?'admin':'dashboard');
visibleNav=ko.pureComputed(()=>session.isAdmin()?[{path:'admin',label:'Overview',icon:'fa-gauge-high'}]:customerNav);
bottomNav=customerNav.filter(item=>['dashboard','wallets','payments-new','payments-list','account'].includes(item.path)).map(item=>({...item,label:item.path==='account'?'More':item.label}));
activeAdminPath=ko.pureComputed(()=>this.selection?.path()||'admin');
isAdminWorkspace=ko.pureComputed(()=>!this.isPublic()&&(session.isAdmin()||this.adminPaths.includes(this.selection.path()||'')));
```

Build the router's simple module routes from `customerNav`, the flattened administrator items, and existing public pages. Preserve the special `activity/{id}`, `history/{id}`, `send/{recipient}`, `add-money`, and customer `tickets` routes exactly. Do not change `session.restore`, the `dashboard -> admin` redirect for restored administrators, `logout`, or the `fluxpay:expired` behavior.

- [ ] **Step 4: Add the administrator sidebar host to `index.html`**

Add `<link rel="stylesheet" href="css/admin-console.css">` after `workspace.css`, then insert this immediately before `<main id="main">`:

```html
<!-- ko if:isAdminWorkspace -->
<aside class="admin-sidebar" aria-label="Administrator navigation">
  <div class="admin-environment" data-bind="attr:{'data-tone':environment.tone}"><i class="fa-solid fa-circle" aria-hidden="true"></i><span data-bind="text:environment.label"></span></div>
  <nav>
    <!-- ko foreach:adminNavGroups -->
    <section class="admin-nav-group"><h2 data-bind="text:label"></h2><div data-bind="foreach:items"><a data-bind="attr:{href:'?ojr='+path,'data-route':path,'aria-current':$root.activeAdminPath()===path?'page':null},css:{active:$root.activeAdminPath()===path}"><i class="fa-solid" aria-hidden="true" data-bind="css:icon"></i><span data-bind="text:label"></span></a></div></section>
    <!-- /ko -->
  </nav>
</aside>
<!-- /ko -->
```

Leave the current header, profile menu, skip link, customer bottom navigation, verification reminder, module host, helper host, and public footer behavior unchanged.

- [ ] **Step 5: Implement the restrained admin-scoped visual system**

```css
/* css/admin-console.css */
.admin-shell{--admin-sidebar:240px;--admin-critical:#b42318;--admin-warning:#9a6700;--admin-success:#067647;--admin-info:#175cd3;background:#f6f7f9;color:var(--ink)}
.admin-shell #main{margin-left:var(--admin-sidebar);min-width:0}
.admin-sidebar{position:fixed;inset:80px auto 0 0;box-sizing:border-box;width:var(--admin-sidebar);z-index:30;overflow-y:auto;border-right:1px solid var(--line);background:#fff;padding:18px 14px 24px}
.admin-environment{position:sticky;top:0;z-index:1;display:flex;align-items:center;gap:8px;margin:0 8px 20px;padding:9px 10px;border:1px solid var(--line);background:#fff;font-size:12px;font-weight:700}
.admin-environment[data-tone="critical"]{color:var(--admin-critical)}.admin-environment[data-tone="warning"]{color:var(--admin-warning)}.admin-environment[data-tone="info"]{color:var(--admin-info)}
.admin-nav-group{margin:0 0 20px}.admin-nav-group h2{margin:0 10px 7px;color:var(--muted);font-size:10px;letter-spacing:.09em;text-transform:uppercase}.admin-nav-group div{display:grid;gap:3px}.admin-nav-group a{display:flex;align-items:center;gap:10px;min-height:38px;padding:8px 10px;border-left:3px solid transparent;color:var(--ink);font-size:13px}.admin-nav-group a:hover{background:#f2f4f7;text-decoration:none}.admin-nav-group a.active{border-left-color:var(--admin-info);background:#eff4ff;color:var(--admin-info);font-weight:700}.admin-nav-group i{width:18px;text-align:center}
.admin-page{max-width:1680px;margin:0 auto;padding:28px 32px 56px}.admin-page-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:22px}.admin-page-heading h1{margin:4px 0 6px;font-size:30px;letter-spacing:-.025em}.admin-page-heading p{margin:0;color:var(--muted)}
.review-workspace{display:grid;grid-template-columns:minmax(280px,30%) minmax(360px,1fr) minmax(260px,28%);min-height:560px;border:1px solid var(--line);background:#fff}.review-queue,.record-detail,.evidence-panel{min-width:0;padding:18px}.review-queue,.record-detail{border-right:1px solid var(--line)}
.configuration-workspace{display:grid;grid-template-columns:minmax(620px,1.7fr) minmax(340px,1fr);border:1px solid var(--line);background:#fff}.configuration-list,.configuration-editor{min-width:0;padding:18px}.configuration-list{border-right:1px solid var(--line)}
.copilot-workspace{display:grid;grid-template-columns:minmax(220px,25%) minmax(420px,1fr) minmax(280px,30%);border:1px solid var(--line);background:#fff}.copilot-context,.copilot-conversation,.copilot-sources{min-width:0;padding:18px}.copilot-context,.copilot-conversation{border-right:1px solid var(--line)}
.dense-table{width:100%;border-collapse:collapse;font-size:12px}.dense-table th,.dense-table td{padding:9px 10px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}.dense-table tbody tr:hover{background:#f8fafc}
.sticky-decision-bar{position:sticky;bottom:0;z-index:12;display:flex;align-items:end;gap:12px;padding:12px 16px;border:1px solid var(--line);background:#fff;box-shadow:0 -6px 18px #10182812}.sticky-decision-bar label{flex:1}.sticky-decision-bar .pill{flex:0 0 auto}
.attention-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(175px,1fr));border:1px solid var(--line);background:#fff}.attention-metric{display:grid;gap:4px;padding:16px;border:0;border-right:1px solid var(--line);background:transparent;text-align:left}.attention-metric strong{font-size:24px}.attention-metric[data-tone="critical"] strong{color:var(--admin-critical)}.attention-metric[data-tone="warning"] strong{color:var(--admin-warning)}.attention-metric[data-tone="info"] strong{color:var(--admin-info)}
.status[data-status="HIGH"],.status[data-status="critical"]{color:var(--admin-critical)}.status[data-status="MEDIUM"],.status[data-status="WARNING"],.status[data-status="warning"]{color:var(--admin-warning)}.status[data-status="ACTIVE"],.status[data-status="VERIFIED"]{color:var(--admin-success)}
@media(max-width:1100px){.review-workspace,.copilot-workspace{grid-template-columns:minmax(260px,36%) 1fr}.evidence-panel,.copilot-sources{grid-column:1/-1;border-top:1px solid var(--line)}.record-detail,.copilot-conversation{border-right:0}.configuration-workspace{grid-template-columns:1fr}.configuration-list{border-right:0;border-bottom:1px solid var(--line)}}
@media(max-width:760px){.admin-shell #main{margin-left:0;padding-top:0}.admin-sidebar{position:relative;inset:auto;width:auto;overflow-x:auto;padding:10px 14px}.admin-sidebar nav{display:flex;gap:18px}.admin-nav-group{min-width:max-content;margin:0}.admin-nav-group div{display:flex}.admin-page{padding:20px 14px 44px}.review-workspace,.copilot-workspace{grid-template-columns:1fr}.review-queue,.record-detail,.copilot-context,.copilot-conversation{border-right:0;border-bottom:1px solid var(--line)}.sticky-decision-bar{position:static;align-items:stretch;flex-direction:column}}
```

Append these focused rules; keep every risk state paired with visible text or an icon in markup:

```css
.admin-shell .filter-row,.admin-shell .eligibility-inputs{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;align-items:end}.admin-shell .source-row{display:block;width:100%;padding:12px;border:0;border-bottom:1px solid var(--line);background:#fff;text-align:left}.admin-shell .source-row:hover{background:#f8fafc}.admin-shell .corridor-cell{min-width:120px;padding:10px;border:1px solid var(--line);background:#fff}.admin-shell .change-diff{width:100%;border-collapse:collapse}.admin-shell .change-diff th,.admin-shell .change-diff td{padding:9px;border-bottom:1px solid var(--line)}.admin-shell .inline-error{display:flex;justify-content:space-between;gap:12px;padding:10px;border-left:3px solid var(--admin-critical);background:#fff}.admin-shell .empty-state{padding:30px 16px;text-align:center;color:var(--muted)}.admin-shell tr[aria-selected="true"]{outline:2px solid var(--admin-info);outline-offset:-2px;background:#eff4ff}.admin-shell :where(a,button,input,select,textarea,[tabindex]):focus-visible{outline:3px solid #84adff;outline-offset:2px}
```

Do not add gradients. Keep border radii at the current small control radius or use square section boundaries.

- [ ] **Step 6: Run shell, customer-regression, type, and build checks**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-navigation.test.cjs tests/navigation.test.cjs tests/experience.test.cjs; npm.cmd run typecheck; npm.cmd run build`

Expected: all commands PASS; customer navigation tests prove the existing five bottom tabs and account flow remain unchanged.

- [ ] **Step 7: Commit the route and shell cutover**

```powershell
git add frontend/fluxpay-ui/src/ts/appController.ts frontend/fluxpay-ui/src/index.html frontend/fluxpay-ui/src/css/admin-console.css frontend/fluxpay-ui/tests/admin-navigation.test.cjs frontend/fluxpay-ui/tests/navigation.test.cjs frontend/fluxpay-ui/tests/experience.test.cjs
git commit -m "feat(admin): add persistent operations console shell"
```

### Task 11: Add cross-module acceptance checks and complete visual verification

**Files:**

- Create: `frontend/fluxpay-ui/tests/admin-integration.test.cjs`
- Modify only if a check exposes a defect: the frontend files owned by Tasks 1-10

**Interfaces:**

- Consumes: all administrator route modules, templates, services, styles, and existing frontend verification commands.
- Produces: a single acceptance test that locks the frontend-only boundary, route inventory, safe rendering, environment semantics, and absence of unsupported controls or copy.

- [ ] **Step 1: Write the cross-module acceptance test**

```js
// tests/admin-integration.test.cjs
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.join(__dirname,'../src');
const read=file=>fs.readFileSync(path.join(root,file),'utf8');

const routes=['admin','admin-kyc','admin-compliance','admin-tickets','admin-providers','admin-routes','admin-policies','admin-copilot'];
const templates=routes.map(route=>read(`ts/views/${route}.html`));

test('every approved administrator route has a view model and template',()=>{
  const controller=read('ts/appController.ts');
  for(const route of routes){
    assert.ok(fs.existsSync(path.join(root,`ts/viewModels/${route}.ts`)),route+' view model');
    assert.ok(fs.existsSync(path.join(root,`ts/views/${route}.html`)),route+' template');
    assert.ok(controller.includes(`path:'${route}'`),route+' router entry');
  }
});

test('unsupported governance and destructive resource controls are absent',()=>{
  const joined=templates.join('\n');
  assert.doesNotMatch(joined,/Audit Log|Governance/);
  assert.doesNotMatch(read('ts/views/admin-compliance.html'),/delete-case|Delete manual case/);
  assert.doesNotMatch(read('ts/views/admin-providers.html'),/requestDeleteProvider|Delete provider|Remove provider/i);
  assert.doesNotMatch(read('ts/views/admin-routes.html'),/requestDeleteRoute|Delete route|Remove route/i);
  assert.doesNotMatch(read('ts/views/admin-policies.html'),/delete-policy|Delete policy/i);
});

test('templates use safe bindings and accessible status text',()=>{
  for(const html of templates){
    assert.doesNotMatch(html,/data-bind="[^"]*\bhtml\s*:/);
    assert.match(html,/Administrator access required/);
    assert.match(html,/session\.isAdmin\(\)/);
    assert.match(html,/role="alert"/);
    assert.match(html,/role="status"[^>]*aria-live="polite"/);
  }
  assert.match(read('ts/views/admin-copilot.html'),/policyAnswer:/);
  assert.match(read('index.html'),/aria-current/);
  assert.match(read('index.html'),/Skip to content/);
  assert.match(read('ts/views/admin.html'),/Open filtered queue/);
});

test('review queues use native open buttons and keyboard focus targets',()=>{
  const reviews=[
    ['admin-kyc','kyc-record-heading'],
    ['admin-compliance','compliance-record-heading'],
    ['admin-tickets','support-record-heading']
  ];
  for(const [route,heading] of reviews){
    const html=read(`ts/views/${route}.html`);
    const viewModel=read(`ts/viewModels/${route}.ts`);
    assert.match(html,/>Open<\/button>/);
    assert.match(html,new RegExp(`id="${heading}"[^>]*tabindex="-1"`));
    assert.match(viewModel,/focusRecordHeading/);
  }
});

test('environment display and API routing remain separate concerns',()=>{
  assert.match(read('ts/appController.ts'),/FLUXPAY_ENVIRONMENT/);
  assert.doesNotMatch(read('ts/services/flux-api.ts'),/FLUXPAY_ENVIRONMENT/);
  assert.match(read('ts/services/flux-api.ts'),/FLUXPAY_API_URL/);
  assert.match(fs.readFileSync(path.join(__dirname,'../scripts/hooks/before_serve.js'),'utf8'),/API_PROXY/);
});

test('the existing authentication expiry boundary remains unchanged',()=>{
  const api=read('ts/services/flux-api.ts');
  assert.match(api,/response\.status===401/);
  assert.match(api,/sessionStorage\.removeItem\('fluxpay\.token'\)/);
  assert.match(api,/fluxpay:expired/);
  assert.match(read('ts/services/session.ts'),/window\.addEventListener\('fluxpay:expired'/);
});
```

- [ ] **Step 2: Run the new acceptance test and repair only concrete failures**

Run: `cd frontend/fluxpay-ui; node --test tests/admin-integration.test.cjs`

Expected: PASS. If it fails, change only the named frontend route, template, or style and rerun this command before continuing.

- [ ] **Step 3: Run the complete frontend verification suite**

Run: `cd frontend/fluxpay-ui; npm.cmd test; npm.cmd run typecheck; npm.cmd run build`

Expected: all three commands exit `0`. Record any Oracle JET warnings separately; do not describe a warning-producing build as clean unless it exits successfully and the warning is understood.

- [ ] **Step 4: Prove the backend and auth boundaries**

Run from the repository root:

```powershell
git diff -- backend
git diff aedc63a..HEAD -- backend
git diff aedc63a..HEAD --name-only
rg -n "FLUXPAY_ENVIRONMENT|FLUXPAY_API_URL|API_PROXY" frontend/fluxpay-ui/src frontend/fluxpay-ui/scripts
rg -n "Audit Log|Governance|requestDeleteProvider|requestDeleteRoute|delete-policy|delete-case" frontend/fluxpay-ui/src/ts/views
```

Expected:

- `git diff -- backend` prints nothing.
- `git diff aedc63a..HEAD -- backend` also prints nothing, proving the committed implementation range contains no backend change.
- `git diff aedc63a..HEAD --name-only` lists only `frontend/fluxpay-ui` and approved documentation/local metadata.
- Environment results show `FLUXPAY_ENVIRONMENT` only in display/controller code, `FLUXPAY_API_URL` only in the API client, and `API_PROXY` only in the development proxy.
- The destructive-control scan has no hits in the provider, route, policy, or compliance templates; subordinate chunk/guidance confirmations may appear only in `admin-policies.html`.

- [ ] **Step 5: Perform desktop-first visual and keyboard verification**

Run: `cd frontend/fluxpay-ui; npx.cmd ojet serve --release`

With an existing administrator session, inspect these routes at 1440×900 and 1280×800:

```text
?ojr=admin
?ojr=admin-kyc
?ojr=admin-compliance
?ojr=admin-tickets
?ojr=admin-providers
?ojr=admin-routes
?ojr=admin-policies
?ojr=admin-copilot
```

Verify the 240-pixel labeled sidebar remains visible, the environment label never scrolls out of the sidebar, dense tables do not clip controls, sticky decision bars do not cover selected content, dialogs trap and restore focus, Tab order follows queue → record → evidence → action, and every red/amber/green/blue state also has text or an icon. At 760 pixels, verify labeled navigation remains available and the three-pane layouts stack without horizontal page clipping. Stop the development server after inspection.

- [ ] **Step 6: Review the final diff and commit acceptance repairs**

Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors, no unexpected paths, and no backend files.

```powershell
git add frontend/fluxpay-ui/tests/admin-integration.test.cjs
git commit -m "test(admin): verify operations console acceptance"
```

If Steps 2-5 expose a production defect, return to the task that owns that file, apply its focused verification and explicit `git add` list, and commit that repair before this acceptance-test commit. The acceptance commit contains only `admin-integration.test.cjs`. Do not squash the task commits until the user explicitly asks.
