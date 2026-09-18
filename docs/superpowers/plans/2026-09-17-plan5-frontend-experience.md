# Plan 5 — Frontend Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship mobile-first shell plus all QA page fixes and session rule without touching backend.

**Architecture:** Replace sidebar with bottom-nav 5-tab shell (max-width 480px centered), merge list+tracking into Activity, fix each QA page in its own viewModel/view, own all route wiring including Plan 1 tickets route and Plan 4 drawer host.

**Tech Stack:** Oracle JET 16.1 MVVM / Knockout TS, Redwood tokens, `fetch` API client.

**Spec:** `docs/superpowers/specs/2026-09-17-mobile-refactor-design.md` and `docs/superpowers/specs/2026-09-17-qa-bugbatch-design.md`

## Global Constraints

- No backend edits, no migrations, no API URL changes; consume Plan 1/3/4 contracts as documented.
- OWNERSHIP: this track alone edits `appController.ts`, `index.html`, `src/css/*`, existing `viewModels/{dashboard,payments-new,payments-list,tracking,recipients,wallets,kyc,account}.ts`, `services/session.ts`, `services/page.ts` filters, and 401 handler hunk in `flux-api.ts`. Do NOT edit Plan 1 ticket anchor block or Plan 4 helper anchor block at EOF.
- Mount points: add `tickets` route for Plan 1 fragment and `<div id="helper-host">` for Plan 4 drawer; never modify their fragment internals.

---

### Task 1: Shell + session rule + Activity merge

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/appController.ts:14-43`
- Modify: `frontend/fluxpay-ui/src/index.html:18-58`
- Modify: `frontend/fluxpay-ui/src/ts/services/session.ts:11`
- Modify: `frontend/fluxpay-ui/src/ts/services/flux-api.ts:19-22` (401 hunk only)
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/payments-list.ts`, `tracking.ts`

**Interfaces:**
- Consumes: existing routes. Produces: bottom-nav `home/wallets/send/activity/more`, `activity/:id` detail, expire-to-home.

- [ ] **Step 1: Add session-expire redirect test hook**

```ts
// session.ts:11 — replace user(null) with clear + navigate home
window.addEventListener('fluxpay:expired', () => {
  sessionStorage.removeItem('fluxpay.token');
  session.user(null);
  navigate('home');
});
```

```ts
// flux-api.ts 401 hunk: keep 401 clear, leave 403 session intact
if (res.status === 401) { sessionStorage.removeItem('fluxpay.token'); window.dispatchEvent(new Event('fluxpay:expired')); }
```

- [ ] **Step 2: Replace sidebar with bottom-nav, merge tracking into activity/:id, shorten ID display**

```ts
// payments-list.ts display helper — resolves "b0496293" question: truncated UUID
export const shortId = (id: string) => (id ? id.slice(0, 8) + '…' : '');
```

```html
<!-- bottom-nav: 5 buttons home/wallets/send/activity/more; content max-width 480px -->
<nav class="bottom-nav"><button data-route="dashboard">Home</button><button data-route="wallets">Wallets</button><button data-route="payments-new">Send</button><button data-route="payments-list">Activity</button><button data-route="account">More</button></nav>
<div id="helper-host"></div>
```

- [ ] **Step 3: Verify `npm run build` + manual 360px/1280px + expire-to-home**
- [ ] **Step 4: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/appController.ts frontend/fluxpay-ui/src/index.html frontend/fluxpay-ui/src/ts/services/session.ts
git commit -m "feat(ui): add mobile shell and session redirect"
```

### Task 2: Send + Recipients + Wallets QA

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/payments-new.ts`, `views/payments-new.html`
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/recipients.ts`, `views/recipients.html`
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/wallets.ts`, `views/wallets.html`

**Interfaces:**
- Consumes: Plan 3 `transfer/withdraw/topup` + `entry_type`. Produces: Others purpose, back button, 5-min countdown, Pay/Save-draft, searchable bank/country dropdowns, Add-money entry.

- [ ] **Step 1: Send flow fixes**

```ts
// payments-new.ts: countdown from existing now() observable
self.quoteCountdown = ko.pureComputed(() => Math.max(0, 300 - Math.floor((Date.now() - self.quoteFetchedAt()) / 1000)));
self.confirmQuote = async () => { const r = await fluxApi.confirm(...); self.showSuccess({ trackId: r.id }); /* no auto-navigate */ };
```

- [ ] **Step 2: Recipients + wallets fixes (remove user Active/Blocked toggle, add Delete, Add-money button, PROCESSING in on-hold filter)**

```ts
// recipients.ts: delete only; block is admin — no status toggle in user UI
self.deleteRecipient = async (id: string) => { await fluxApi.delete(`/api/recipients/${id}`); self.load(); };
// wallets.ts on-hold: include PROCESSING
self.onHold = ko.pureComputed(() => self.payments().filter(p => p.status === 'PROCESSING' || p.status === 'UNDER_REVIEW'));
```

- [ ] **Step 3: Verify build + manual draft-save + topup-in-transactions**
- [ ] **Step 4: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/viewModels/payments-new.ts frontend/fluxpay-ui/src/ts/viewModels/recipients.ts frontend/fluxpay-ui/src/ts/viewModels/wallets.ts
git commit -m "fix(ui): send recipient wallet qa batch"
```

### Task 3: Verification + filters + admin wiring

**Files:**
- Modify: `frontend/fluxpay-ui/src/ts/viewModels/kyc.ts`, `views/kyc.html`
- Modify: `frontend/fluxpay-ui/src/ts/services/page.ts:filteredPayments`
- Modify: `frontend/fluxpay-ui/src/ts/appController.ts` (add `tickets` route mount only)

**Interfaces:**
- Consumes: Plan 1 tickets fragment, Plan 4 drawer host. Produces: status header, REJECTED re-upload, type/date filters.

- [ ] **Step 1: KYC status + re-upload gate**

```ts
// kyc.ts: allow resubmit only when REJECTED
self.canResubmit = ko.pureComputed(() => self.kyc()?.status === 'REJECTED');
```

```ts
// page.ts filteredPayments: add type + date filters incl. Plan 3 entry types
self.filteredPayments = ko.pureComputed(() => self.payments().filter(p =>
  (!self.typeFilter() || p.type === self.typeFilter()) &&
  (!self.fromDate() || p.createdAt >= self.fromDate())));
```

- [ ] **Step 2: Mount Plan 1 route (no fragment edits)**

```ts
{ path: 'tickets', detail: {} }, // mounted viewModels/tickets (Plan 1 owner)
```

- [ ] **Step 3: Verify build + manual rejected-reupload + filter matrix**
- [ ] **Step 4: Commit**

```bash
git add frontend/fluxpay-ui/src/ts/viewModels/kyc.ts frontend/fluxpay-ui/src/ts/services/page.ts frontend/fluxpay-ui/src/ts/appController.ts
git commit -m "fix(ui): verification filters and tickets mount"
```
