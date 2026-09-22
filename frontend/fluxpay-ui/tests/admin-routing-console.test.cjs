// tests/admin-routing-console.test.cjs (providers review flow, Task 5)
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const ko = require('knockout');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');
const compile = file => ts.transpileModule(read(file), {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}}).outputText;
const adminConsoleContext = {exports: {}};
vm.runInNewContext(compile('ts/services/admin-console.ts'), adminConsoleContext);
const adminConsole = adminConsoleContext.exports;

const providerId = '11111111-1111-4111-8111-111111111111';
const routeId = '22222222-2222-4222-8222-222222222222';

function routingWorkspace(overrides = {}, admin = true) {
  const calls = [];
  const provider = {id: providerId, providerCode: 'WISE', providerName: 'Wise', railType: 'BANK_NETWORK', active: true, version: 0};
  const route = {id: routeId, providerId, routeCode: 'WISE_INR_STANDARD', name: 'Wise INR Standard', destinationType: 'EXTERNAL_ACCOUNT', destinationCountry: 'IN', payoutCurrency: 'INR', baseFee: '5.0000', fxSpreadPercentage: '0.500000', estimatedMinutes: 120, configuredSuccessRate: '99.00', effectiveSuccessRate: '99.00', completedCount: 0, failedCount: 0, minimumRecipientAmount: null, maximumRecipientAmount: null, active: true, version: 0};
  const api = new Proxy(overrides, {get: (obj, name) => async (...args) => {calls.push([name, ...args]); if (name in obj) return obj[name](...args); if (name === 'railTypes') return [{railType: 'BANK_NETWORK', displayLabel: 'Bank Network', supportedDestinations: ['EXTERNAL_ACCOUNT']}]; if (name === 'providers') return [provider]; if (name === 'routesAdmin') return [route]; if (name === 'createProvider' || name === 'updateProvider') return {...provider}; if (name === 'createRoute' || name === 'updateRoute') return {...route}; throw new Error(`Unexpected api call: ${String(name)}`);}});
  const user = ko.observable(admin ? {role: 'ADMIN'} : null);
  const session = {user, isAdmin: ko.pureComputed(() => user()?.role === 'ADMIN'), restore: async () => {}};
  const context = {exports: {}, require: name => name === 'knockout' ? ko : name === './session' ? {session} : name === './admin-console' ? adminConsole : {fluxApi: api}};
  vm.runInNewContext(compile('ts/services/routing-workspace.ts'), context);
  return {page: new context.exports.RoutingWorkspace(), calls, session, provider, route};
}

test('provider update shows a before/after diff before making the existing PUT', async () => {
  const {page, calls} = routingWorkspace();
  await page.loadAll();
  page.editProvider(page.providers()[0]);
  page.providerName('Wise Payments Ltd');
  page.providerActive(false);
  page.requestProviderSave();
  assert.equal(calls.filter(call => call[0] === 'updateProvider').length, 0);
  assert.deepEqual(JSON.parse(JSON.stringify(page.providerChanges())), [
    {field: 'providerName', label: 'Provider name', before: 'Wise', after: 'Wise Payments Ltd'},
    {field: 'active', label: 'Active', before: 'Yes', after: 'No'}
  ]);
  await page.confirmProviderSave();
  assert.equal(calls.filter(call => call[0] === 'updateProvider').length, 1);
  page.dispose();
});

test('provider create shows a review before making the existing POST', async () => {
  const {page, calls} = routingWorkspace();
  await page.loadAll();
  page.newProvider();
  page.providerCode('SBI_BANK');
  page.providerName('SBI Bank');
  page.providerRail('BANK_NETWORK');
  page.requestProviderSave();
  assert.equal(calls.filter(call => call[0] === 'createProvider').length, 0);
  assert.equal(page.saveReview(), 'provider');
  assert.ok(page.providerChanges().length > 0);
  await page.confirmProviderSave();
  assert.equal(calls.filter(call => call[0] === 'createProvider').length, 1);
  assert.equal(page.saveReview(), '');
  page.dispose();
});

test('provider stale conflict keeps form values and the pending diff for retry', async () => {
  const initial = {id: providerId, providerCode: 'WISE', providerName: 'Wise', railType: 'BANK_NETWORK', active: true, version: 0};
  const latest = {...initial, providerName: 'Server renamed provider', version: 4};
  let providerReads = 0;
  const updates = [];
  const result = routingWorkspace({
    providers: async () => providerReads++ === 0 ? [initial] : [latest],
    updateProvider: async (id, body) => {
      updates.push([id, body]);
      if (updates.length === 1) throw new Error('STALE_PROVIDER: version 0 is stale');
      return {...latest, ...body, version: 5};
    }
  });
  await result.page.loadAll();
  result.page.editProvider(result.page.providers()[0]);
  result.page.providerName('Operator proposed provider');
  result.page.providerActive(false);
  result.page.requestProviderSave();
  await result.page.confirmProviderSave();
  assert.match(result.page.providerError(), /stale/i);
  assert.equal(result.page.providerForm(), true);
  assert.equal(result.page.providerName(), 'Operator proposed provider');
  assert.equal(result.page.providerActive(), false);
  assert.equal(result.page.providerEditTarget().version, 4);
  assert.ok(result.page.providerChanges().length > 0);
  result.page.requestProviderSave();
  await result.page.confirmProviderSave();
  assert.equal(updates.length, 2);
  assert.equal(updates[1][1].version, 4);
  assert.equal(updates[1][1].providerName, 'Operator proposed provider');
  result.page.dispose();
});

test('route update shows a before/after diff before making the existing PUT', async () => {
  const {page, calls} = routingWorkspace();
  await page.loadAll();
  page.editRoute(page.routes()[0]);
  page.routeName('Operator proposed route');
  page.routeActive(false);
  page.requestRouteSave();
  assert.equal(calls.filter(call => call[0] === 'updateRoute').length, 0);
  assert.equal(page.saveReview(), 'route');
  assert.deepEqual(JSON.parse(JSON.stringify(page.routeChanges())), [
    {field: 'name', label: 'Name', before: 'Wise INR Standard', after: 'Operator proposed route'},
    {field: 'baseFee', label: 'Base fee', before: '5.0000', after: '5'},
    {field: 'fxSpreadPercentage', label: 'FX spread', before: '0.500000', after: '0.5'},
    {field: 'configuredSuccessRate', label: 'Configured reliability', before: '99.00', after: '99'},
    {field: 'active', label: 'Active', before: 'Yes', after: 'No'}
  ]);
  await page.confirmRouteSave();
  assert.equal(calls.filter(call => call[0] === 'updateRoute').length, 1);
  page.dispose();
});

test('provider template has no destructive resource control', () => {
  const html = read('ts/views/admin-providers.html');
  assert.match(html, /Provider configuration/);
  assert.match(html, /requestProviderSave/);
  assert.match(html, /providerChanges/);
  assert.doesNotMatch(html, /requestDeleteProvider|deleteProvider|Delete provider|Remove provider/i);
});

test('providers page keeps the admin gate, protection copy and environment label', async () => {
  const html = read('ts/views/admin-providers.html');
  assert.ok(html.includes('if:session.isAdmin()'));
  assert.ok(html.includes('System protected'));
  assert.ok(html.includes('environment.label'));
  assert.ok(html.includes('role="dialog"'));
  const user = ko.observable({role: 'ADMIN'});
  const session = {user, isAdmin: ko.pureComputed(() => user()?.role === 'ADMIN'), restore: async () => {}};
  const workspaceCalls = [];
  class FakeWorkspace {
    constructor() { workspaceCalls.push('constructed'); }
    async loadAll() { workspaceCalls.push('loadAll'); }
    dispose() {}
  }
  const context = {exports: {}, module: {exports: {}}, window: {FLUXPAY_ENVIRONMENT: 'STAGING', location: {hostname: 'app.example'}}, require: name => name === 'knockout' ? ko : name === '../services/admin-console' ? adminConsole : name === '../services/routing-workspace' ? {RoutingWorkspace: FakeWorkspace} : name === '../services/session' ? {session} : {}};
  vm.runInNewContext(compile('ts/viewModels/admin-providers.ts'), context);
  const viewModel = new context.module.exports();
  assert.ok(viewModel.workspace);
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(workspaceCalls, ['constructed', 'loadAll']);
  viewModel.disconnected();
});
