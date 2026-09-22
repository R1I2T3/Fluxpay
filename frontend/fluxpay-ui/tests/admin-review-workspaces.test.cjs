// tests/admin-review-workspaces.test.cjs (compliance section, Task 3)
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ko = require('knockout');
const {load} = require('./helpers/load-typescript.cjs');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

const helpers = load('ts/services/admin-console.ts', {'./flux-api': {}});

const caseId = '22222222-2222-4222-8222-222222222222';
const paymentId = '33333333-3333-4333-8333-333333333333';
function makeCase(overrides = {}) {
  return {
    id: caseId,
    paymentId,
    reviewReference: 'review-1',
    reviewExpiresAt: null,
    requoteRequired: false,
    risk: 'HIGH',
    status: 'OPEN',
    riskReasons: ['High-value corridor'],
    suggestedAction: 'Review source of funds',
    decidedBy: null,
    decidedAt: null,
    decisionReason: null,
    createdAt: '2026-09-20T10:00:00Z',
    ...overrides
  };
}
const openHighRiskCase = makeCase();

function complianceWorkspace(overrides = {}, admin = true) {
  const calls = [];
  const api = new Proxy(overrides, {
    get: (obj, name) => async (...args) => {
      calls.push([name, ...args]);
      if (name in obj) return obj[name](...args);
      if (name === 'complianceCases') return [openHighRiskCase];
      if (name === 'complianceCase') return {...openHighRiskCase};
      if (name === 'decideComplianceCase')
        return {...openHighRiskCase, status: args[1] === 'approve' ? 'APPROVED' : 'REJECTED'};
      throw new Error(`Unexpected api call: ${String(name)}`);
    }
  });
  const user = ko.observable(admin ? {role: 'ADMIN'} : null);
  const session = {
    user,
    isAdmin: ko.pureComputed(() => user()?.role === 'ADMIN'),
    restore: async () => {}
  };
  const Workspace = load(
    'ts/services/compliance-workspace.ts',
    {knockout: ko, './flux-api': {fluxApi: api}, './session': {session}, './admin-console': helpers}
  );
  return {page: new Workspace.ComplianceWorkspace(), calls, session};
}

test('compliance decisions combine a code and notes in the existing decisionReason field', async () => {
  const {page, calls} = complianceWorkspace();
  page.selectedCase(openHighRiskCase);
  page.decisionCode('RULES_SATISFIED');
  page.decisionNotes('Evidence reviewed.');
  page.prepareDecision('approve');
  await page.confirm();
  assert.deepEqual(calls.find(call => call[0] === 'decideComplianceCase'), [
    'decideComplianceCase', openHighRiskCase.id, 'approve', 'RULES_SATISFIED — Evidence reviewed.'
  ]);
  page.dispose();
});

test('compliance cases order risk first, then review expiry, then age', () => {
  const {page} = complianceWorkspace();
  page.savedCaseView('OPEN');
  const highLate = makeCase({id: 'high-late', reviewExpiresAt: '2026-09-23T12:00:00Z', createdAt: '2026-09-20T10:00:00Z'});
  const highEarly = makeCase({id: 'high-early', reviewExpiresAt: '2026-09-22T13:00:00Z', createdAt: '2026-09-21T10:00:00Z'});
  const mediumEarly = makeCase({id: 'medium-early', risk: 'MEDIUM', reviewExpiresAt: null, createdAt: '2026-09-19T10:00:00Z'});
  const highNull = makeCase({id: 'high-null', reviewExpiresAt: null, createdAt: '2026-09-20T11:00:00Z'});
  page.cases([highLate, mediumEarly, highNull, highEarly]);
  assert.deepEqual(Array.from(page.prioritizedCases(), item => item.id), ['high-early', 'high-late', 'high-null', 'medium-early']);
  page.dispose();
});

test('compliance page exposes only response-backed context and no delete action', () => {
  const html = read('ts/views/admin-compliance.html');
  for (const field of ['reviewReference', 'reviewExpiresAt', 'requoteRequired', 'riskReasons', 'suggestedAction', 'decisionReason']) assert.ok(html.includes(field), field);
  assert.doesNotMatch(html, /delete-case|Delete manual case/);
  assert.doesNotMatch(html, /customer country|payment amount|payment currency/i);
  assert.match(html, /navigateToCopilot/);
});

test('navigateToCopilot dispatches admin-copilot with the selected case and payment IDs', async () => {
  const navigateCalls = [];
  const selected = makeCase();
  const FakeWorkspace = function () {
    this.savedCaseView = ko.observable('OPEN');
    this.selectedCase = ko.observable(selected);
    this.loadCases = async () => {};
    this.openCase = async () => {};
    this.dispose = () => {};
  };
  const ViewModel = load('ts/viewModels/admin-compliance.ts', {
    knockout: ko,
    '../services/admin-console': helpers,
    '../services/compliance-workspace': {ComplianceWorkspace: FakeWorkspace},
    '../services/session': {
      navigate: (routePath, params) => navigateCalls.push([routePath, params]),
      session: {user: ko.observable({role: 'ADMIN'}), isAdmin: () => true, restore: async () => {}}
    }
  });
  const vm = new ViewModel({params: {view: 'OPEN'}});
  await new Promise(resolve => setImmediate(resolve));
  vm.navigateToCopilot();
  assert.equal(JSON.stringify(navigateCalls), JSON.stringify([['admin-copilot', {caseId: selected.id, paymentId: selected.paymentId}]]));
  vm.disconnected();
});
