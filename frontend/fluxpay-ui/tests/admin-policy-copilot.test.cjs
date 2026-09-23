// tests/admin-policy-copilot.test.cjs (policy configuration workspace, Task 7)
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const ko = require('knockout');
const {load} = require('./helpers/load-typescript.cjs');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');
const compile = file => ts.transpileModule(read(file), {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}}).outputText;
const id = '11111111-1111-4111-8111-111111111111';
const policy = {id, title: 'Transfer review', category: 'PAYMENT_REVIEW', content: 'Review source of funds.', documentHash: 'test-hash', createdAt: '2026-09-17T10:00:00Z', chunks: []};

const adminConsoleContext = {exports: {}, require: () => ({})};
vm.runInNewContext(compile('ts/services/admin-console.ts'), adminConsoleContext);
const adminConsole = adminConsoleContext.exports;

function complianceWorkspace(overrides = {}, admin = true, runtime = {}) {
  const calls = [];
  const api = new Proxy(overrides, {get: (obj, name) => async (...args) => {
    calls.push([name, ...args]);
    if (name in obj) return obj[name](...args);
    if (name === 'policies') return [policy];
    if (name === 'policy' || name === 'createPolicy') return {...policy};
    if (name === 'updatePolicy') return {...policy};
    if (name === 'policyChunks') return [];
    if (name === 'policyGuidance') return [];
    if (name === 'complianceCases') return [];
    if (name === 'indexPolicy') return {policyDocumentId: id, chunkCount: 2};
    throw new Error(`Unexpected api call: ${String(name)}`);
  }});
  const user = ko.observable(admin ? {role: 'ADMIN'} : null);
  const session = {user, isAdmin: ko.pureComputed(() => user()?.role === 'ADMIN'), restore: async () => {}};
  const context = {exports: {}, require: name => name === 'knockout' ? ko : name === './session' ? {session} : name === './admin-console' ? adminConsole : {fluxApi: api}, ...runtime};
  vm.runInNewContext(compile('ts/services/compliance-workspace.ts'), context);
  return {page: new context.exports.ComplianceWorkspace(), calls, session};
}

test('policy edit shows current-document changes before calling the existing update API', async () => {
  const {page, calls} = complianceWorkspace();
  page.policies([{...policy}]);
  page.openPolicyEdit(policy);
  page.policyEdit().title('Updated transfer review');
  page.policyEdit().clearExistingChunks(false);
  page.requestPolicyEditSave();
  assert.equal(calls.filter(call => call[0] === 'updatePolicy').length, 0);
  assert.deepEqual(JSON.parse(JSON.stringify(page.policyChanges())), [
    {field: 'title', label: 'Title', before: 'Transfer review', after: 'Updated transfer review'},
    {field: 'clearExistingChunks', label: 'Clear existing chunks', before: 'Yes', after: 'No'}
  ]);
  await page.confirmPolicyEditSave();
  assert.equal(calls.filter(call => call[0] === 'updatePolicy').length, 1);
  page.dispose();
});

test('policy modal mode selects exactly one active surface', () => {
  const {page} = complianceWorkspace();
  page.policy({...policy});
  assert.equal(page.policyModalMode(), 'detail');
  page.openPolicyEdit(policy);
  assert.equal(page.policyModalMode(), 'edit');
  page.policyEdit().title('Updated title');
  page.requestPolicyEditSave();
  assert.equal(page.policyModalMode(), 'review');
  page.policyChangeReview(false);
  assert.equal(page.policyModalMode(), 'edit');
  page.dispose();
});

test('policy table is wide, scrollable and uses accessible icon actions', () => {
  const html = read('ts/views/admin-policies.html');
  assert.match(html, /class="dense-table admin-wide-table policy-library-table"/);
  assert.match(html, /aria-label="View policy"/);
  assert.doesNotMatch(html, /\$root\.environment/);
});

test('policy template exposes only current API metadata and hides document deletion', () => {
  const html = read('ts/views/admin-policies.html');
  for (const token of ['Current document', 'createdAt', 'Indexed chunks', 'Advanced']) assert.ok(html.includes(token), token);
  assert.doesNotMatch(html, /Document hash|text:documentHash/);
  assert.doesNotMatch(html, /Policy version|Effective date|Publication status|Index failure|Policy owner/i);
  assert.doesNotMatch(html, /delete-policy|deletePolicy|Delete policy/i);
});

test('policy draft publish waits for confirmation before creating policies', async () => {
  const {page, calls} = complianceWorkspace();
  page.addManualPolicyDraft();
  page.draftEditor().title('Staged policy');
  page.draftEditor().category('AML');
  page.draftEditor().content('Staged policy text.');
  page.savePolicyDraft();
  page.requestPolicyDraftPublish();
  assert.equal(calls.filter(call => call[0] === 'createPolicy').length, 0);
  assert.equal(page.draftPublishReview(), true);
  await page.confirmPolicyDraftPublish();
  assert.equal(calls.filter(call => call[0] === 'createPolicy').length, 1);
  assert.equal(page.draftPublishReview(), false);
  page.dispose();
});

test('failed batch entries remain in their original order', async () => {
  const {page, calls} = complianceWorkspace({createPolicy: async body => {
    if (body.title === 'Rejected one' || body.title === 'Rejected two') throw Error('Duplicate policy title');
    return {...policy, title: body.title};
  }});
  for (const [title, category, content] of [['Accepted', 'AML', 'First policy'], ['Rejected one', 'KYC', 'Second policy'], ['Rejected two', 'SUPPORT', 'Third policy']]) {
    page.addManualPolicyDraft();
    page.draftEditor().title(title);
    page.draftEditor().category(category);
    page.draftEditor().content(content);
    page.savePolicyDraft();
  }
  await page.createPolicyDrafts();
  assert.deepEqual(calls.filter(call => call[0] === 'createPolicy').map(call => call[1].title), ['Accepted', 'Rejected one', 'Rejected two']);
  assert.equal(JSON.stringify(page.policyDrafts().map(draft => draft.title())), JSON.stringify(['Rejected one', 'Rejected two']));
  page.dispose();
});

test('policyId deep link opens the current document', async () => {
  const opened = [];
  const navigations = [];
  class FakeWorkspace {
    policies = ko.observable([{...policy}]);
    policySavedView = ko.observable('ALL');
    loadPolicies = async () => {};
    openPolicy = async item => { opened.push(item); };
    dispose() {}
  }
  const ViewModel = load('ts/viewModels/admin-policies.ts', {
    knockout: ko,
    '../services/admin-console': adminConsole,
    '../services/admin-dialog': {},
    '../services/compliance-workspace': {ComplianceWorkspace: FakeWorkspace},
    '../services/session': {
      navigate: (routePath, params) => navigations.push([routePath, params]),
      session: {user: ko.observable({role: 'ADMIN'}), isAdmin: () => true, restore: async () => {}}
    }
  }, {window: {FLUXPAY_ENVIRONMENT: undefined, location: {hostname: 'localhost'}}});
  const vmInstance = new ViewModel({params: {policyId: id, view: 'UNINDEXED'}});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(vmInstance.workspace.policySavedView(), 'UNINDEXED');
  assert.equal(JSON.stringify(opened), JSON.stringify([{id}]));
  vmInstance.workspace.policySavedView('ALL');
  vmInstance.changeSavedView();
  assert.equal(JSON.stringify(navigations), JSON.stringify([['admin-policies', {view: 'ALL'}]]));
  vmInstance.disconnected();
});

test('chunk and guidance deletion remain available only inside Advanced with confirmation', async () => {
  const html = read('ts/views/admin-policies.html');
  const advancedAt = html.indexOf("policyView()==='advanced'");
  assert.ok(advancedAt > 0, 'missing advanced details view');
  const main = html.slice(0, advancedAt);
  assert.ok(!main.includes('foreach:chunks') && !main.includes('foreach:guidance'), 'raw chunks leak into the main policy view');
  assert.ok(main.includes('Current document'));
  assert.ok(html.includes('requestDeleteChunk'));
  assert.ok(html.includes('requestDeleteGuidance'));
  const {page, calls} = complianceWorkspace({deletePolicyGuidance: async () => ({})});
  page.policy({...policy});
  const item = {id: 'guidance-1', policyDocumentId: id, complianceCaseId: id, content: 'Prior decision', createdAt: policy.createdAt, updatedAt: policy.createdAt};
  page.requestDeleteGuidance(item);
  assert.equal(calls.filter(call => call[0] === 'deletePolicyGuidance').length, 0);
  assert.equal(page.confirmation(), 'delete-guidance');
  await page.confirm();
  assert.equal(calls.filter(call => call[0] === 'deletePolicyGuidance').length, 1);
  assert.match(page.notice(), /Policy guidance deleted/);
  page.dispose();
});

test('policy saved views filter indexed documents and reset paging', () => {
  const {page} = complianceWorkspace();
  const indexed = {...policy, id: 'policy-indexed', chunks: [{id: 'chunk-1', policyDocumentId: 'policy-indexed', chunkNumber: 1, content: 'Indexed', manual: false, createdAt: policy.createdAt}]};
  const manualOnly = {...policy, id: 'policy-manual', chunks: [{id: 'chunk-2', policyDocumentId: 'policy-manual', chunkNumber: 1, content: 'Manual', manual: true, createdAt: policy.createdAt}]};
  const empty = {...policy, id: 'policy-empty', chunks: []};
  page.policies([indexed, manualOnly, empty]);
  page.policySavedView('UNINDEXED');
  assert.deepEqual(page.savedPolicies().map(item => item.id), ['policy-manual', 'policy-empty']);
  assert.deepEqual(page.visiblePolicies().map(item => item.id), ['policy-manual', 'policy-empty']);
  page.policyPage(1);
  page.policySavedView('ALL');
  assert.equal(page.policyPage(), 0);
  assert.equal(page.savedPolicies().length, 3);
  page.dispose();
});

// Copilot section (Task 8: cited Compliance Copilot workspace)
const openCase = {id, paymentId: id, reviewReference: null, risk: 'HIGH', status: 'OPEN', riskReasons: ['QA reason'], suggestedAction: 'Review records', decidedBy: null, decidedAt: null, decisionReason: null, createdAt: '2026-09-17T10:00:00Z'};

function copilotPage(routeParams = {params: {}}, overrides = {}) {
  const calls = [];
  const api = new Proxy(overrides, {get: (obj, name) => async (...args) => {
    calls.push([name, ...args]);
    if (name in obj) return obj[name](...args);
    if (name === 'complianceCases') return [openCase];
    if (name === 'complianceCase') return {...openCase};
    if (name === 'askCopilot') return {answer: 'A sourced answer', sources: []};
    throw new Error(`Unexpected api call: ${String(name)}`);
  }});
  const user = ko.observable({role: 'ADMIN'});
  const session = {user, isAdmin: ko.pureComputed(() => user()?.role === 'ADMIN'), restore: async () => {}};
  const navigations = [];
  const navigate = (routePath, params) => navigations.push([routePath, params]);
  const workspaceContext = {exports: {}, require: name => name === 'knockout' ? ko : name === './session' ? {session} : name === './admin-console' ? adminConsole : {fluxApi: api}};
  vm.runInNewContext(compile('ts/services/compliance-workspace.ts'), workspaceContext);
  const ViewModel = load('ts/viewModels/admin-copilot.ts', {
    knockout: ko,
    '../services/compliance-workspace': workspaceContext.exports,
    '../services/flux-api': {fluxApi: api},
    '../services/admin-console': adminConsole,
    '../services/session': {navigate, session}
  });
  return {page: new ViewModel(routeParams), calls, navigations, session};
}

async function settlePage(page, done) {
  for (let i = 0; i < 50 && !done(); i++) await new Promise(resolve => setImmediate(resolve));
}

test('case context pre-fills a cited question without inventing payment details', async () => {
  const {page, calls} = copilotPage({params: {caseId: openCase.id, paymentId: openCase.paymentId}});
  await page.ready;
  assert.ok(calls.some(call => call[0] === 'complianceCase'));
  assert.match(page.workspace.question(), /Risk level: HIGH/);
  assert.match(page.workspace.question(), /Triggered reasons:/);
  assert.doesNotMatch(page.workspace.question(), /customer country|amount|currency/i);
  page.disconnected();
});

test('Copilot accepts direct payment context without loading the compliance queue', async () => {
  const {page, calls} = copilotPage({params: {paymentId: openCase.paymentId}});
  await page.ready;
  assert.equal(page.workspace.copilotPaymentId(), openCase.paymentId);
  assert.ok(!calls.some(call => call[0] === 'complianceCases'));
  page.disconnected();
});

test('Copilot template keeps citations visible and exposes no decision action', () => {
  const html = read('ts/views/admin-copilot.html');
  assert.match(html, /policyAnswer:answer\(\)\.answer/);
  for (const field of ['policyDocumentId', 'title', 'chunkNumber', 'excerpt']) assert.ok(html.includes(field), field);
  assert.match(html, /Advisory only/);
  assert.doesNotMatch(html, /Approve|Reject|Activate|Publish/);
  assert.doesNotMatch(html, /policy version/i);
  assert.doesNotMatch(html, /data-bind="[^"]*\bhtml\s*:/);
});

test('askCited requests only a cited answer without the live response', async () => {
  const {page, calls} = copilotPage({params: {}});
  await page.ready;
  page.workspace.question('When is review needed?');
  page.workspace.liveResponse(true);
  page.askCited();
  await settlePage(page, () => page.workspace.answer());
  assert.equal(page.workspace.liveResponse(), false);
  assert.deepEqual(calls.map(call => call[0]).filter(name => name === 'askCopilot'), ['askCopilot']);
  assert.equal(page.workspace.answer().answer, 'A sourced answer');
  assert.deepEqual(page.workspace.answer().sources, []);
  page.disconnected();
});

test('successful cited ask clears the composer but preserves the rendered question', async () => {
  const {page} = copilotPage({params: {}});
  await page.ready;
  page.workspace.question('When is review required?');
  await page.askCited();
  assert.equal(page.workspace.question(), '');
  assert.equal(page.workspace.answeredQuestion(), 'When is review required?');
  page.disconnected();
});

test('empty Copilot sources render an explicit no-source state', () => {
  assert.match(read('ts/views/admin-copilot.html'), /No policy sources returned/);
});

test('Copilot provider errors preserve the question for retry', async () => {
  const {page} = copilotPage({params: {}}, {askCopilot: async () => { throw Error('Compliance Copilot is temporarily unavailable.'); }});
  await page.ready;
  page.workspace.question('What requires review?');
  page.askCited();
  await settlePage(page, () => page.workspace.error());
  assert.match(page.workspace.error(), /unavailable/);
  assert.equal(page.workspace.question(), 'What requires review?');
  assert.equal(page.workspace.answer(), undefined);
  page.disconnected();
});

test('opening a Copilot source navigates to the policy library', async () => {
  const {page, navigations} = copilotPage({params: {}});
  await page.ready;
  page.openSource({policyDocumentId: id, title: 'Policy', chunkNumber: 1, excerpt: 'Review clause'});
  assert.equal(JSON.stringify(navigations), JSON.stringify([['admin-policies', {policyId: id}]]));
  page.disconnected();
});
