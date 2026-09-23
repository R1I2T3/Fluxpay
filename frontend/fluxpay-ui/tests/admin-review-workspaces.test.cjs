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
    {knockout: ko, './flux-api': {fluxApi: api}, './session': {session}, './admin-console': helpers, './notifications': require('./notification-fixture.cjs')()}
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

function complianceViewModel({clipboard} = {}) {
  const user = ko.observable({id: 'admin-1', role: 'ADMIN'});
  const session = {
    user,
    isAdmin: () => user()?.role === 'ADMIN',
    restore: async () => {}
  };
  const workspace = {
    savedCaseView: ko.observable('OPEN'),
    selectedCase: ko.observable(undefined),
    notice: ko.observable(''),
    error: ko.observable(''),
    loadCases: async () => {},
    openCase: async () => {},
    confirm: async () => {},
    closeCase: () => {},
    dispose: () => {}
  };
  const ViewModel = load('ts/viewModels/admin-compliance.ts', {
    knockout: ko,
    '../services/admin-console': helpers,
    '../services/admin-dialog': {},
    '../services/compliance-workspace': {ComplianceWorkspace: function FakeWorkspace() { return workspace; }},
    '../services/session': {
      navigate: () => {},
      session
    }
  }, {navigator: clipboard === undefined ? {} : {clipboard}});
  return {vm: new ViewModel({params: {view: 'OPEN'}}), workspace};
}

test('compliance identifier copy reports success while the UUID remains hidden by the template', async () => {
  const writes = [];
  const {vm, workspace} = complianceViewModel({clipboard: {writeText: async value => writes.push(value)}});
  workspace.selectedCase(makeCase());
  assert.equal(typeof vm.copyIdentifier, 'function');
  await vm.copyIdentifier(paymentId, 'Payment ID');
  assert.deepEqual(writes, [paymentId]);
  assert.match(workspace.notice(), /copied/i);
  assert.doesNotMatch(read('ts/views/admin-compliance.html'), /text:paymentId/);
  vm.disconnected();
});

test('compliance identifier copy reports unavailable clipboard without asking for a visible UUID', async () => {
  const {vm, workspace} = complianceViewModel({clipboard: undefined});
  workspace.selectedCase(makeCase());
  assert.equal(typeof vm.copyIdentifier, 'function');
  await vm.copyIdentifier(paymentId, 'Payment ID');
  assert.match(workspace.error(), /clipboard access is unavailable/i);
  assert.doesNotMatch(workspace.error(), /visible ID/i);
  vm.disconnected();
});

test('compliance renders a full-row queue and one modal workflow', () => {
  const html = read('ts/views/admin-compliance.html');
  assert.match(html, /class="review-list-row"/);
  assert.match(html, /aria-label="Copy payment ID"/);
  assert.match(html, /Ask Copilot about this case/);
  assert.match(html, /class="[^"]*admin-modal-action-pane[^"]*"[\s\S]*Review decision/);
  assert.doesNotMatch(html, />\s*Open\s*<\/button>/);
  assert.equal((html.match(/class="admin-confirmation/g) || []).length, 1);
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
    '../services/admin-dialog': {},
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

// ---- Support tickets review workspace (Task 4) ----
function ticket(overrides = {}) {
  return {
    id: 'ticket-1',
    userId: 'user-1',
    paymentId: null,
    subject: 'Payment not received',
    body: 'Customer statement body',
    status: 'OPEN',
    assigneeAdminId: null,
    createdAt: '2026-09-21T00:00:00Z',
    updatedAt: '2026-09-21T00:00:00Z',
    ...overrides
  };
}

function ticketWorkspaceWithDeferredLoad({adminId = 'admin-1', params = {}, clipboard} = {}) {
  const user = ko.observable({id: adminId, role: 'ADMIN'});
  const session = {
    user,
    isAdmin: () => user()?.role === 'ADMIN',
    restore: async () => {}
  };
  const navigations = [];
  const calls = [];
  const resolvers = [];
  const ticketApi = {
    listForAdmin: (...args) => {
      calls.push(['listForAdmin', ...args]);
      return new Promise(resolve => resolvers.push(resolve));
    },
    updateForAdmin: async (...args) => {
      calls.push(['updateForAdmin', ...args]);
      return {};
    }
  };
  class PageStub {
    constructor() {
      this.session = session;
      this.busy = ko.observable(false);
      this.error = ko.observable('');
      this.notice = ko.observable('');
      this.search = ko.observable('');
    }
    label(value) {
      return String(value || '').replace(/_/g, ' ').toLowerCase().replace(/^./, s => s.toUpperCase());
    }
    date(value) {
      return value ? String(value) : '—';
    }
    async run(action, success = '') {
      if (this.busy()) return;
      this.busy(true);
      this.error('');
      this.notice('');
      try {
        await action();
        if (success) this.notice(success);
      } catch (e) {
        this.error(e.message || 'Something went wrong. Please try again.');
      } finally {
        this.busy(false);
      }
    }
    disconnected() {}
  }
  const ViewModel = load('ts/viewModels/admin-tickets.ts', {
    knockout: ko,
    '../services/page': {Page: PageStub},
    '../services/admin-console': helpers,
    '../services/flux-api': {ticketApi},
    '../services/session': {navigate: (path, target) => navigations.push([path, target]), session}
  }, {window: {setTimeout: () => 0}, navigator: clipboard === undefined ? {} : {clipboard}});
  const page = new ViewModel({params});
  const finish = result => {
    resolvers.splice(0).forEach(resolve => resolve(result));
  };
  return {page, session, calls, navigations, finish};
}

test('support queue prioritizes open, unassigned, and oldest records', async () => {
  const {page, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  finish({items: [
    ticket({id: 'assigned', status: 'OPEN', assigneeAdminId: 'admin-1', createdAt: '2026-09-20T00:00:00Z'}),
    ticket({id: 'unassigned', status: 'OPEN', assigneeAdminId: null, createdAt: '2026-09-21T00:00:00Z'}),
    ticket({id: 'in-progress', status: 'IN_PROGRESS', assigneeAdminId: null, createdAt: '2026-09-19T00:00:00Z'}),
    ticket({id: 'closed', status: 'CLOSED', assigneeAdminId: null, createdAt: '2026-09-19T00:00:00Z'})
  ], total: 4});
  await pending;
  // NOTE: the OPEN saved view shows open work only, so the CLOSED record is
  // excluded here (it is reachable via the CLOSED view below). Order proves
  // open-before-in-progress, unassigned-before-assigned, oldest-first.
  assert.deepEqual(Array.from(page.visibleTickets(), item => item.id), ['unassigned', 'assigned', 'in-progress']);
  assert.equal(page.selectedTicket(), undefined);
  page.savedView('CLOSED');
  assert.deepEqual(Array.from(page.visibleTickets(), item => item.id), ['closed']);
  page.disconnected();
});

test('support initial load does not open the first ticket and closeTicket clears selection', async () => {
  const {page, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  finish({items: [ticket({id: 'first'}), ticket({id: 'second'})], total: 2});
  await pending;
  assert.equal(page.selectedTicket(), undefined);
  page.selectTicket(page.visibleTickets()[1]);
  assert.equal(page.selectedTicket().id, 'second');
  page.closeTicket();
  assert.equal(page.selectedTicket(), undefined);
  page.disconnected();
});

test('logout invalidates a late ticket response', async () => {
  const {page, session, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  session.user(null);
  finish({items: [ticket({id: 'late'})], total: 1});
  await pending;
  assert.equal(page.tickets().length, 0);
  assert.equal(page.selectedTicket(), undefined);
  page.disconnected();
});

test('support copy says age and customer statement, never SLA or latest message', () => {
  const html = read('ts/views/admin-tickets.html');
  assert.match(html, /Customer statement/);
  assert.match(html, /Waiting over 24 hours/);
  assert.doesNotMatch(html, /\bSLA\b|Latest message/i);
  for (const token of ['review-queue', 'review-list-row', 'admin-confirmation', 'admin-workflow-modal', 'Assign to me']) assert.ok(html.includes(token), token);
  assert.doesNotMatch(html, />\s*Open\s*<\/button>/);
  assert.equal((html.match(/class="admin-confirmation/g) || []).length, 1);
});

test('support identifiers copy without rendering the UUID in ticket details', async () => {
  const writes = [];
  const {page} = ticketWorkspaceWithDeferredLoad({
    clipboard: {writeText: async value => writes.push(value)}
  });
  assert.equal(typeof page.copyIdentifier, 'function');
  await page.copyIdentifier('00000000-0000-0000-0000-000000005d01', 'Payment ID');
  assert.deepEqual(writes, ['00000000-0000-0000-0000-000000005d01']);
  assert.match(page.notice(), /Payment ID copied/);
  assert.doesNotMatch(read('ts/views/admin-tickets.html'), /text:paymentId/);
  page.disconnected();
});

test('support ASSIGNED_TO_ME view shows only my open tickets', async () => {
  const {page, finish} = ticketWorkspaceWithDeferredLoad({adminId: 'admin-1'});
  const pending = page.loadTickets();
  finish({items: [
    ticket({id: 'mine', status: 'OPEN', assigneeAdminId: 'admin-1'}),
    ticket({id: 'theirs', status: 'OPEN', assigneeAdminId: 'admin-2'}),
    ticket({id: 'unassigned', status: 'OPEN', assigneeAdminId: null}),
    ticket({id: 'mine-closed', status: 'CLOSED', assigneeAdminId: 'admin-1'})
  ], total: 4});
  await pending;
  page.savedView('ASSIGNED_TO_ME');
  assert.deepEqual(Array.from(page.visibleTickets(), item => item.id), ['mine']);
  page.disconnected();
});

test('support UNASSIGNED view hides assigned and closed tickets', async () => {
  const {page, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  finish({items: [
    ticket({id: 'unassigned', status: 'OPEN', assigneeAdminId: null}),
    ticket({id: 'assigned', status: 'OPEN', assigneeAdminId: 'admin-1'}),
    ticket({id: 'closed-unassigned', status: 'CLOSED', assigneeAdminId: null})
  ], total: 3});
  await pending;
  page.savedView('UNASSIGNED');
  assert.deepEqual(Array.from(page.visibleTickets(), item => item.id), ['unassigned']);
  page.disconnected();
});

test('support AGING view shows only open work waiting over 24 hours', async () => {
  const hour = 60 * 60 * 1000;
  const agedAt = new Date(Date.now() - 50 * hour).toISOString();
  const recentAt = new Date(Date.now() - 90 * 60 * 1000).toISOString();
  const {page, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  finish({items: [
    ticket({id: 'old-open', status: 'OPEN', createdAt: agedAt, updatedAt: agedAt}),
    ticket({id: 'recent-open', status: 'OPEN', createdAt: recentAt, updatedAt: recentAt}),
    ticket({id: 'old-resolved', status: 'RESOLVED', createdAt: agedAt, updatedAt: agedAt})
  ], total: 3});
  await pending;
  page.savedView('AGING');
  assert.deepEqual(Array.from(page.visibleTickets(), item => item.id), ['old-open']);
  assert.equal(page.ticketAge(ticket({createdAt: agedAt})), 'Waiting 2 days');
  assert.equal(page.ticketAge(ticket({createdAt: recentAt})), 'Waiting 1 hour');
  assert.equal(page.ticketAge(ticket({createdAt: 'not-a-date'})), 'Age unavailable');
  page.disconnected();
});

test('support selection survives a refresh when the ticket is still present', async () => {
  const {page, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  finish({items: [
    ticket({id: 'a', status: 'OPEN', assigneeAdminId: null, createdAt: '2026-09-21T00:00:00Z'}),
    ticket({id: 'b', status: 'OPEN', assigneeAdminId: 'admin-1', createdAt: '2026-09-20T00:00:00Z'})
  ], total: 2});
  await pending;
  page.selectTicket(page.visibleTickets().find(item => item.id === 'b'));
  assert.equal(page.selectedTicket().id, 'b');
  const refresh = page.loadTickets();
  finish({items: [
    ticket({id: 'b', status: 'OPEN', assigneeAdminId: 'admin-1', createdAt: '2026-09-20T00:00:00Z'}),
    ticket({id: 'c', status: 'OPEN', assigneeAdminId: null, createdAt: '2026-09-22T00:00:00Z'})
  ], total: 2});
  await refresh;
  assert.equal(page.selectedTicket().id, 'b');
  const gone = page.loadTickets();
  finish({items: [ticket({id: 'c', status: 'OPEN', assigneeAdminId: null, createdAt: '2026-09-22T00:00:00Z'})], total: 1});
  await gone;
  assert.equal(page.selectedTicket(), undefined);
  page.disconnected();
});

test('support CLOSED tickets map to Reopen and cannot be assigned', async () => {
  const {page, calls, finish} = ticketWorkspaceWithDeferredLoad();
  const pending = page.loadTickets();
  finish({items: [ticket({id: 'closed-1', status: 'CLOSED'})], total: 1});
  await pending;
  page.savedView('CLOSED');
  assert.deepEqual(Array.from(page.visibleTickets(), item => item.id), ['closed-1']);
  page.selectTicket(page.visibleTickets()[0]);
  // selectedNextAction is built in the helper realm, so compare fields.
  assert.equal(page.selectedNextAction().status, 'OPEN');
  assert.equal(page.selectedNextAction().label, 'Reopen ticket');
  const updatesBefore = calls.filter(call => call[0] === 'updateForAdmin').length;
  page.assignToMe(page.selectedTicket());
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.filter(call => call[0] === 'updateForAdmin').length, updatesBefore);
  page.runNextAction();
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  const reopen = calls.find(call => call[0] === 'updateForAdmin');
  assert.equal(reopen[1], 'closed-1');
  assert.equal(reopen[2].status, 'OPEN');
  const html = read('ts/views/admin-tickets.html');
  assert.match(html, /status!=='CLOSED'/);
  page.disconnected();
});

test('support deep link selects the requested ticket', async () => {
  const {page, finish} = ticketWorkspaceWithDeferredLoad({params: {view: 'CLOSED', ticketId: 'closed-9'}});
  assert.equal(page.savedView(), 'CLOSED');
  assert.equal(page.status(), 'CLOSED');
  const pending = page.loadTickets();
  finish({items: [
    ticket({id: 'closed-1', status: 'CLOSED'}),
    ticket({id: 'closed-9', status: 'CLOSED'})
  ], total: 2});
  await pending;
  assert.equal(page.selectedTicket().id, 'closed-9');
  page.disconnected();
});

test('support saved views map to server status filters and deep links', async () => {
  const {page, navigations, finish} = ticketWorkspaceWithDeferredLoad();
  page.savedView('CLOSED');
  page.changeView();
  assert.equal(page.status(), 'CLOSED');
  assert.equal(navigations.length, 1);
  assert.equal(navigations[0][0], 'admin-tickets');
  assert.equal(navigations[0][1].view, 'CLOSED');
  finish({items: [], total: 0});
  await new Promise(resolve => setImmediate(resolve));
  page.savedView('UNASSIGNED');
  page.changeView();
  assert.equal(page.status(), 'ALL');
  finish({items: [], total: 0});
  await new Promise(resolve => setImmediate(resolve));
  page.disconnected();
});
