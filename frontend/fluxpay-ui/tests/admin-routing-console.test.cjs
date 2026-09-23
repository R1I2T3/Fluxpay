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

test('provider modal bindings work with a null Oracle JET outer root', () => {
  const html = read('ts/views/admin-providers.html');
  assert.match(html, /<!-- ko if:providerForm -->/);
  assert.doesNotMatch(html, /visible:providerForm/);
  assert.doesNotMatch(html, /\$root\.environment/);
  assert.match(html, /adminDialog:\{initialFocus:'#provider-editor-title'\}/);
  assert.match(html, /saveReview\(\)!=='provider'/);
  assert.match(html, /saveReview\(\)==='provider'/);
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

test('direct provider navigation installs the shared dialog focus trap', async () => {
  const previousHandler=ko.bindingHandlers.adminDialog;
  const previousDisposal=ko.utils.domNodeDisposal.addDisposeCallback;
  let activeElement;
  let keydown;
  let dispose;
  const previousFocus={isConnected:true,focus(){activeElement=this;}};
  const first={focus(){activeElement=this;}};
  const last={focus(){activeElement=this;}};
  activeElement=previousFocus;
  const document={get activeElement(){return activeElement;}};
  const window={
    FLUXPAY_ENVIRONMENT:'STAGING',location:{hostname:'app.example'},
    setTimeout(callback){callback();return 1;},clearTimeout(){}
  };
  const element={
    querySelectorAll(){return [first,last];},
    addEventListener(name,handler){if(name==='keydown')keydown=handler;},
    removeEventListener(){},contains(value){return value===first||value===last;}
  };
  const user=ko.observable({role:'ADMIN'});
  const session={user,isAdmin:ko.pureComputed(()=>true),restore:async()=>{}};
  class FakeWorkspace {async loadAll(){} dispose(){}}
  try{
    delete ko.bindingHandlers.adminDialog;
    ko.utils.domNodeDisposal.addDisposeCallback=(_element,callback)=>{dispose=callback;};
    const dependencies={
      knockout:ko,
      '../services/admin-console':adminConsole,
      '../services/routing-workspace':{RoutingWorkspace:FakeWorkspace},
      '../services/session':{session}
    };
    let dialogLoaded=false;
    const requireForPage=name=>{
      if(name==='../services/admin-dialog'){
        dialogLoaded=true;
        const dialogContext={exports:{},require:dependency=>dependencies[dependency],window,document};
        vm.runInNewContext(compile('ts/services/admin-dialog.ts'),dialogContext);
        return {};
      }
      return dependencies[name];
    };
    const context={exports:{},module:{exports:{}},require:requireForPage,window,document};
    vm.runInNewContext(compile('ts/viewModels/admin-providers.ts'),context);
    new context.module.exports();
    assert.equal(dialogLoaded,true);
    assert.equal(typeof ko.bindingHandlers.adminDialog?.init,'function');
    ko.bindingHandlers.adminDialog.init(element);
    assert.equal(activeElement,first);
    activeElement=last;
    let prevented=false;
    keydown({key:'Tab',shiftKey:false,preventDefault(){prevented=true;}});
    assert.equal(prevented,true);
    assert.equal(activeElement,first);
    dispose();
    assert.equal(activeElement,previousFocus);
  }finally{
    ko.utils.domNodeDisposal.addDisposeCallback=previousDisposal;
    if(previousHandler)ko.bindingHandlers.adminDialog=previousHandler;
    else delete ko.bindingHandlers.adminDialog;
  }
});

// Route analysis section (Task 6): eligibility preview, corridor matrix and comparison.
function loadAnalysis() {
  const context = {exports: {}};
  vm.runInNewContext(compile('ts/services/route-analysis.ts'), context);
  return context.exports;
}

const analysisProvider = {id: '33333333-3333-4333-8333-333333333333', providerCode: 'WISE', providerName: 'Wise', railType: 'BANK_NETWORK', active: true, systemProtected: false, archivedAt: null, version: 0};
const analysisRails = [{railType: 'BANK_NETWORK', displayLabel: 'Bank network', supportedDestinations: ['EXTERNAL_ACCOUNT']}];
function analysisRoute(overrides = {}) {
  return {id: '44444444-4444-4444-8444-444444444444', providerId: analysisProvider.id, routeCode: 'ELIGIBLE', name: 'Eligible route', destinationType: 'EXTERNAL_ACCOUNT', destinationCountry: 'IN', payoutCurrency: 'INR', baseFee: '5.0000', fxSpreadPercentage: '0.500000', estimatedMinutes: 120, configuredSuccessRate: '99.00', effectiveSuccessRate: '99.00', completedCount: 10, failedCount: 0, minimumRecipientAmount: null, maximumRecipientAmount: null, active: true, systemProtected: false, archivedAt: null, version: 0, ...overrides};
}

test('eligibility preview reports every route and never chooses a winner', () => {
  const analysis = loadAnalysis();
  const eligibleRoute = analysisRoute({routeCode: 'ELIGIBLE'});
  const inactiveRoute = analysisRoute({id: '55555555-5555-4555-8555-555555555555', routeCode: 'INACTIVE', active: false});
  const overLimitRoute = analysisRoute({id: '66666666-6666-4666-8666-666666666666', routeCode: 'OVER_LIMIT', maximumRecipientAmount: '50'});
  const missingProviderRoute = analysisRoute({id: '77777777-7777-4777-8777-777777777777', routeCode: 'NO_PROVIDER', providerId: '99999999-9999-4999-8999-999999999999'});
  const result = analysis.evaluateRouteEligibility(
    {country: 'IN', currency: 'INR', amount: '100', destinationType: 'EXTERNAL_ACCOUNT'},
    [eligibleRoute, inactiveRoute, overLimitRoute, missingProviderRoute],
    [analysisProvider],
    analysisRails
  );
  assert.deepEqual(JSON.parse(JSON.stringify(result.routes.map(item => [item.route.routeCode, item.eligible, item.reasons]))), [
    ['ELIGIBLE', true, []],
    ['INACTIVE', false, ['Route is inactive.']],
    ['OVER_LIMIT', false, ['Amount exceeds the configured maximum of 50.']],
    ['NO_PROVIDER', false, ['Provider configuration is unavailable.']]
  ]);
  assert.equal(Object.prototype.hasOwnProperty.call(result, 'winner'), false);
  assert.deepEqual(Object.keys(result).sort(), ['inputErrors', 'routes']);
});

test('invalid amount and unknown rail produce explicit non-authoritative errors', () => {
  const analysis = loadAnalysis();
  const eligibleRoute = analysisRoute({routeCode: 'ELIGIBLE'});
  const invalid = analysis.evaluateRouteEligibility({country: 'IN', currency: 'INR', amount: 'abc', destinationType: 'EXTERNAL_ACCOUNT'}, [eligibleRoute], [analysisProvider], analysisRails);
  assert.deepEqual(Array.from(invalid.inputErrors), ['Enter an amount greater than zero.']);
  const rail = analysis.evaluateRouteEligibility({country: 'IN', currency: 'INR', amount: '10', destinationType: 'EXTERNAL_ACCOUNT'}, [eligibleRoute], [analysisProvider], []);
  assert.deepEqual(Array.from(rail.routes[0].reasons), ['Rail compatibility is unavailable.']);
});

test('eligibility boundaries, archived records, mismatches and limits share one table', () => {
  const analysis = loadAnalysis();
  const archivedAt = '2026-01-01T00:00:00.000Z';
  const baseInput = {country: 'IN', currency: 'INR', amount: '100', destinationType: 'EXTERNAL_ACCOUNT'};
  const cases = [
    {name: 'amount equals minimum', route: {minimumRecipientAmount: '100'}, input: baseInput, eligible: true, reasons: []},
    {name: 'amount equals maximum', route: {maximumRecipientAmount: '100'}, input: baseInput, eligible: true, reasons: []},
    {name: 'amount below minimum', route: {minimumRecipientAmount: '100'}, input: {...baseInput, amount: '50'}, eligible: false, reasons: ['Amount is below the configured minimum of 100.']},
    {name: 'null limits stay eligible', route: {minimumRecipientAmount: null, maximumRecipientAmount: null}, input: baseInput, eligible: true, reasons: []},
    {name: 'archived route', route: {archivedAt}, input: baseInput, eligible: false, reasons: ['Route is archived.']},
    {name: 'archived provider', route: {}, provider: {...analysisProvider, archivedAt}, input: baseInput, eligible: false, reasons: ['Provider is archived.']},
    {name: 'inactive provider', route: {}, provider: {...analysisProvider, active: false}, input: baseInput, eligible: false, reasons: ['Provider is inactive.']},
    {name: 'country mismatch', route: {destinationCountry: 'KE'}, input: baseInput, eligible: false, reasons: ['Destination country does not match.']},
    {name: 'currency mismatch', route: {payoutCurrency: 'KES'}, input: baseInput, eligible: false, reasons: ['Payout currency does not match.']},
    {name: 'method mismatch', route: {destinationType: 'INTERNAL_WALLET'}, input: baseInput, eligible: false, reasons: ['Payout method does not match.']},
    {name: 'unsupported destination', route: {destinationType: 'INTERNAL_WALLET'}, input: {...baseInput, destinationType: 'INTERNAL_WALLET'}, eligible: false, reasons: ['Provider rail does not support this payout method.']},
    {name: 'zero amount is an input error', route: {}, input: {...baseInput, amount: '0'}, eligible: false, inputErrors: ['Enter an amount greater than zero.']},
    {name: 'country uses two letters', route: {}, input: {...baseInput, country: 'IND'}, eligible: false, inputErrors: ['Enter a two-letter destination country.'], reasons: ['Destination country does not match.']},
    {name: 'currency uses three letters', route: {}, input: {...baseInput, currency: 'IN'}, eligible: false, inputErrors: ['Enter a three-letter payout currency.'], reasons: ['Payout currency does not match.']}
  ];
  for (const item of cases) {
    const route = analysisRoute({routeCode: item.name, ...item.route});
    const result = analysis.evaluateRouteEligibility(item.input, [route], [item.provider || analysisProvider], analysisRails);
    assert.equal(result.routes[0].eligible, item.eligible, item.name);
    assert.deepEqual(Array.from(result.routes[0].reasons), item.reasons || [], item.name);
    if (item.inputErrors) assert.deepEqual(Array.from(result.inputErrors), item.inputErrors, item.name);
    assert.equal(Object.prototype.hasOwnProperty.call(result, 'winner'), false, item.name);
  }
});

test('corridor matrix counts only active, non-archived route and provider combinations', () => {
  const analysis = loadAnalysis();
  const archivedAt = '2026-01-01T00:00:00.000Z';
  const archivedProvider = {...analysisProvider, id: '88888888-8888-4888-8888-888888888888', archivedAt};
  const inactiveProvider = {...analysisProvider, id: '99999999-9999-4999-8999-999999999999', active: false};
  const routes = [
    analysisRoute({id: 'm1', routeCode: 'M1', destinationCountry: 'IN', payoutCurrency: 'INR'}),
    analysisRoute({id: 'm2', routeCode: 'M2', destinationCountry: 'IN', payoutCurrency: 'INR'}),
    analysisRoute({id: 'm3', routeCode: 'M3', destinationCountry: 'IN', payoutCurrency: 'INR', active: false}),
    analysisRoute({id: 'm4', routeCode: 'M4', destinationCountry: 'KE', payoutCurrency: 'KES', archivedAt}),
    analysisRoute({id: 'm5', routeCode: 'M5', providerId: archivedProvider.id, destinationCountry: 'IN', payoutCurrency: 'INR'}),
    analysisRoute({id: 'm6', routeCode: 'M6', providerId: inactiveProvider.id, destinationCountry: 'IN', payoutCurrency: 'INR'}),
    analysisRoute({id: 'm7', routeCode: 'M7', providerId: 'missing-provider', destinationCountry: 'IN', payoutCurrency: 'INR'}),
    analysisRoute({id: 'm8', routeCode: 'M8', destinationCountry: null, payoutCurrency: 'USD'})
  ];
  assert.deepEqual(JSON.parse(JSON.stringify(analysis.buildCorridorMatrix(routes, [analysisProvider, archivedProvider, inactiveProvider]))), [
    {country: 'GLOBAL', cells: [{currency: 'INR', count: 0}, {currency: 'USD', count: 1}]},
    {country: 'IN', cells: [{currency: 'INR', count: 2}, {currency: 'USD', count: 0}]}
  ]);
});

test('provider comparison groups routes without choosing a winner', () => {
  const analysis = loadAnalysis();
  const other = {...analysisProvider, id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', providerCode: 'OTHER', providerName: 'Other'};
  const comparison = analysis.buildProviderComparison([analysisProvider, other], [
    analysisRoute({id: 'c1', routeCode: 'C1'}),
    analysisRoute({id: 'c2', routeCode: 'C2', providerId: other.id})
  ]);
  assert.deepEqual(comparison.map(entry => [entry.provider.providerCode, entry.routes.map(route => route.routeCode)]), [['WISE', ['C1']], ['OTHER', ['C2']]]);
  for (const entry of comparison) assert.equal(Object.prototype.hasOwnProperty.call(entry, 'winner'), false);
});

test('route attention flags unavailable records and reliability drops', () => {
  const analysis = loadAnalysis();
  const archivedAt = '2026-01-01T00:00:00.000Z';
  assert.equal(analysis.routeNeedsAttention(analysisRoute({}), analysisProvider), false);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({active: false}), analysisProvider), true);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({archivedAt}), analysisProvider), true);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({}), undefined), true);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({}), {...analysisProvider, active: false}), true);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({}), {...analysisProvider, archivedAt}), true);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({completedCount: 90, failedCount: 10, effectiveSuccessRate: '90.00', configuredSuccessRate: '99.00'}), analysisProvider), true);
  assert.equal(analysis.routeNeedsAttention(analysisRoute({completedCount: 0, failedCount: 0, effectiveSuccessRate: '0.00', configuredSuccessRate: '99.00'}), analysisProvider), false);
});
