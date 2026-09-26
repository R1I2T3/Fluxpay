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
  assert.equal(helpers.resolveAdminEnvironment('', 'staging1.fluxpay.test').name, 'STAGING');
  assert.equal(helpers.resolveAdminEnvironment('', 'prestage.fluxpay.test').name, 'STAGING');
  assert.equal(helpers.resolveAdminEnvironment('', 'uat2.fluxpay.test').name, 'STAGING');
  assert.equal(helpers.resolveAdminEnvironment('', 'admin.fluxpay.example').name, 'PRODUCTION');
});

test('admin identifiers copy through one clipboard boundary without exposing their value', async () => {
  assert.equal(typeof helpers.copyAdminIdentifier, 'function');
  const writes = [];
  const notice = await helpers.copyAdminIdentifier(
    '00000000-0000-0000-0000-000000005d01',
    'Payment ID',
    {writeText: async value => writes.push(value)}
  );
  assert.deepEqual(writes, ['00000000-0000-0000-0000-000000005d01']);
  assert.equal(notice, 'Payment ID copied.');
  await assert.rejects(
    () => helpers.copyAdminIdentifier('hidden-id', 'Case ID', undefined),
    /Clipboard access is unavailable/
  );
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
  assert.deepEqual(JSON.parse(JSON.stringify(helpers.nextTicketAction('IN_PROGRESS'))), {status: 'RESOLVED', label: 'Resolve ticket'});
  assert.equal(helpers.nextTicketAction('UNKNOWN'), null);
});

test('keyboard activation moves focus to the selected record heading', () => {
  let focused=false;
  const runtime={requestAnimationFrame:callback=>callback(),document:{getElementById:id=>id==='record-heading'?{focus:()=>{focused=true;}}:null}};
  helpers.focusRecordHeading({detail:0},'record-heading',runtime);
  assert.equal(focused,true);
  focused=false;helpers.focusRecordHeading({detail:1},'record-heading',runtime);assert.equal(focused,false);
});

test('adminKyc requests the exact typed pagination URL', async () => {
  let capturedUrl = '';
  const api = load('ts/services/flux-api.ts', {'./admin-statistics': {statisticsSearch: () => ''}}, {
    window: {},
    sessionStorage: {getItem: () => null},
    fetch: async (url) => {
      capturedUrl = String(url);
      return {ok: true, status: 200, json: async () => ({data: []})};
    }
  });
  await api.fluxApi.adminKyc('PENDING', 0, 100);
  assert.equal(capturedUrl, '/api/admin/kyc/applications?status=PENDING&page=0&size=100');
});

// ---- Operations overview derivation and partial failure (Task 9) ----
const fsOverview = require('node:fs');
const pathOverview = require('node:path');
const koOverview = require('knockout');
const routeAnalysisOverview = load('ts/services/route-analysis.ts', {'./flux-api': {}});
const overview = load('ts/services/admin-overview.ts', {
  './flux-api': {},
  './admin-console': helpers,
  './route-analysis': routeAnalysisOverview
});
const OVERVIEW_NOW = Date.parse('2026-09-22T12:00:00Z');
const kyc = (overrides = {}) => ({
  applicationId: 'kyc-1', version: 1, email: 'applicant@test.example', fullName: 'Applicant One',
  docType: 'PAN', docNumber: 'ABCDE1234', status: 'PENDING', submittedAt: '2026-09-22T10:00:00Z',
  decidedAt: null, rejectReason: null,
  documents: [{id: 'doc-1', fileName: 'id.pdf', fileType: 'application/pdf', fileSize: 1024, uploadedAt: '2026-09-22T10:00:00Z', available: true}],
  ...overrides
});
const compliance = (overrides = {}) => ({
  id: 'case-1', paymentId: 'pay-1', reviewReference: 'review-1', reviewExpiresAt: null,
  requoteRequired: false, risk: 'HIGH', status: 'OPEN', riskReasons: ['High-value corridor'],
  suggestedAction: 'Review source of funds', decidedBy: null, decidedAt: null, decisionReason: null,
  createdAt: '2026-09-21T00:00:00Z', ...overrides
});
const ticket = (overrides = {}) => ({
  id: 'ticket-1', userId: 'user-1', paymentId: null, subject: 'Payment not received',
  body: 'Customer statement body', status: 'OPEN', assigneeAdminId: null,
  createdAt: '2026-09-22T10:00:00Z', updatedAt: '2026-09-22T10:00:00Z', ...overrides
});
const provider = (overrides = {}) => ({
  id: 'provider-1', providerCode: 'WISE', providerName: 'Wise', railType: 'BANK_NETWORK',
  active: true, systemProtected: false, archivedAt: null, version: 1, ...overrides
});
const route = (overrides = {}) => ({
  id: 'route-1', providerId: 'provider-1', routeCode: 'WISE_INR_STANDARD', name: 'Wise INR Standard',
  destinationType: 'EXTERNAL_ACCOUNT', destinationCountry: 'IN', payoutCurrency: 'INR',
  baseFee: '5.0000', fxSpreadPercentage: '0.500000', estimatedMinutes: 120,
  configuredSuccessRate: '99.00', effectiveSuccessRate: '99.00',
  completedCount: 10, failedCount: 0, minimumRecipientAmount: null, maximumRecipientAmount: null,
  active: true, systemProtected: false, archivedAt: null, version: 1, ...overrides
});
const policy = (overrides = {}) => ({
  id: 'policy-1', title: 'KYC policy', category: 'KYC', content: 'Policy text.',
  documentHash: 'hash-1', createdAt: '2026-09-20T00:00:00Z',
  chunks: [{id: 'chunk-1', policyDocumentId: 'policy-1', chunkNumber: 1, content: 'Indexed passage.', manual: false, createdAt: '2026-09-20T00:00:00Z'}],
  ...overrides
});
const metricById = (metrics, id) => metrics.find(item => item.id === id);

function deferred() {
  let resolve, reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return {promise, resolve, reject};
}

function overviewPage() {
  const calls = [];
  const sticky = {};
  const queues = {adminKyc: [], complianceCases: [], listForAdmin: [], providers: [], routesAdmin: [], policies: []};
  const stub = key => {
    const fn = (...args) => {
      calls.push([key, ...args]);
      if (queues[key].length) return queues[key].shift().promise;
      if (sticky[key]) return sticky[key].status === 'ok' ? Promise.resolve(sticky[key].value) : Promise.reject(sticky[key].value);
      return new Promise(() => {});
    };
    fn.resolve = value => { sticky[key] = {status: 'ok', value}; };
    fn.reject = error => { sticky[key] = {status: 'err', value: error}; };
    fn.next = () => { const gate = deferred(); queues[key].push(gate); return gate; };
    return fn;
  };
  const api = {
    adminKyc: stub('adminKyc'), complianceCases: stub('complianceCases'), listForAdmin: stub('listForAdmin'),
    providers: stub('providers'), routesAdmin: stub('routesAdmin'), policies: stub('policies')
  };
  const user = koOverview.observable({role: 'ADMIN'});
  const session = {
    user,
    isAdmin: () => user()?.role === 'ADMIN',
    restore: async () => { if (!user()) user({role: 'ADMIN'}); },
    clear: () => user(null)
  };
  const navigations = [];
  const ViewModel = load('ts/viewModels/admin.ts', {
    knockout: koOverview,
    '../services/admin-overview': overview,
    '../services/flux-api': {
      fluxApi: {adminKyc: api.adminKyc, complianceCases: api.complianceCases, providers: api.providers, routesAdmin: api.routesAdmin, policies: api.policies},
      ticketApi: {listForAdmin: api.listForAdmin}
    },
    '../services/session': {session, navigate: (path, params) => navigations.push([path, params])}
  });
  const page = new ViewModel({});
  return {page, api, session, navigations, calls, queues};
}

test('overview derives only API-backed attention and marks a full KYC page as 100+', () => {
  const result = overview.deriveAdminOverview({
    kyc: Array.from({length: 100}, (_, index) => kyc({applicationId: String(index), submittedAt: '2026-09-20T00:00:00Z'})),
    cases: [compliance({id: 'high', risk: 'HIGH', status: 'OPEN'})],
    tickets: [ticket({id: 'old', status: 'OPEN', createdAt: '2026-09-20T00:00:00Z'})],
    providers: [provider({id: 'provider', active: true, archivedAt: null})],
    routes: [route({id: 'degraded', providerId: 'provider', completedCount: 8, failedCount: 2, configuredSuccessRate: 99, effectiveSuccessRate: 80})],
    policies: [policy({id: 'unindexed', chunks: [{manual: true}]})],
    now: Date.parse('2026-09-22T12:00:00Z')
  });
  assert.equal(result.metrics.find(item => item.id === 'kyc-pending').value, '100+');
  assert.equal(result.metrics.find(item => item.id === 'compliance-high').value, '1');
  assert.equal(result.metrics.find(item => item.id === 'routes-attention').value, '1');
  assert.equal(result.metrics.find(item => item.id === 'policies-unindexed').value, '1');
});

test('one rejected overview source leaves fulfilled sources usable', async () => {
  const {page, api} = overviewPage();
  api.adminKyc.reject(new Error('KYC unavailable'));
  api.complianceCases.resolve([compliance({id: 'high', risk: 'HIGH', status: 'OPEN'})]);
  api.listForAdmin.resolve({items: [], total: 0}); api.providers.resolve([]); api.routesAdmin.resolve([]); api.policies.resolve([]);
  await page.loadOverview();
  assert.match(page.sources.kyc().error, /KYC unavailable/);
  assert.equal(page.sources.compliance().status, 'ready');
  assert.equal(page.metrics().find(item => item.id === 'kyc-pending').value, 'Unavailable');
  assert.equal(page.metrics().find(item => item.id === 'compliance-high').value, '1');
});

test('overview counts pending and aging KYC without resolved or verified records', () => {
  const result = overview.deriveAdminOverview({
    kyc: [
      kyc({applicationId: 'old', status: 'PENDING', submittedAt: '2026-09-20T00:00:00Z'}),
      kyc({applicationId: 'recent', status: 'PENDING', submittedAt: '2026-09-22T11:00:00Z'}),
      kyc({applicationId: 'verified', status: 'VERIFIED', submittedAt: '2026-09-20T00:00:00Z'})
    ],
    cases: [], tickets: [], providers: [], routes: [], policies: [], now: OVERVIEW_NOW
  });
  assert.equal(metricById(result.metrics, 'kyc-pending').value, '2');
  assert.equal(metricById(result.metrics, 'kyc-aging').value, '1');
  assert.deepEqual(Array.from(result.rows, row => row.id), ['old']);
  assert.equal(result.rows[0].area, 'KYC');
});

test('overview flags inactive, archived, degraded, and inactive-provider routes only', () => {
  const inactiveProvider = provider({id: 'inactive-provider', active: false});
  const result = overview.deriveAdminOverview({
    kyc: [], cases: [], tickets: [],
    providers: [provider({id: 'provider-1'}), inactiveProvider],
    routes: [
      route({id: 'healthy'}),
      route({id: 'inactive', active: false}),
      route({id: 'archived', archivedAt: '2026-01-01T00:00:00.000Z'}),
      route({id: 'degraded', completedCount: 90, failedCount: 10, effectiveSuccessRate: '90.00', configuredSuccessRate: '99.00'}),
      route({id: 'provider-inactive', providerId: 'inactive-provider'}),
      route({id: 'provider-missing', providerId: 'missing-provider'})
    ],
    policies: [], now: OVERVIEW_NOW
  });
  assert.equal(metricById(result.metrics, 'routes-attention').value, '5');
  assert.deepEqual(Array.from(result.rows, row => row.id).sort(), ['archived', 'degraded', 'inactive', 'provider-inactive', 'provider-missing']);
});

test('overview counts open and in-progress tickets and surfaces only aging rows', () => {
  const result = overview.deriveAdminOverview({
    kyc: [], cases: [], providers: [], routes: [], policies: [],
    tickets: [
      ticket({id: 'old-open', status: 'OPEN', createdAt: '2026-09-20T00:00:00Z'}),
      ticket({id: 'recent-progress', status: 'IN_PROGRESS', createdAt: '2026-09-22T11:00:00Z'}),
      ticket({id: 'old-resolved', status: 'RESOLVED', createdAt: '2026-09-20T00:00:00Z'}),
      ticket({id: 'old-closed', status: 'CLOSED', createdAt: '2026-09-20T00:00:00Z'})
    ],
    now: OVERVIEW_NOW
  });
  assert.equal(metricById(result.metrics, 'tickets-open').value, '2');
  assert.equal(metricById(result.metrics, 'tickets-aging').value, '1');
  assert.deepEqual(Array.from(result.rows, row => row.id), ['old-open']);
});

test('overview treats only policies without indexed chunks as unindexed', () => {
  const result = overview.deriveAdminOverview({
    kyc: [], cases: [], tickets: [], providers: [], routes: [],
    policies: [
      policy({id: 'indexed'}),
      policy({id: 'manual-only', chunks: [{manual: true}]}),
      policy({id: 'no-chunks', chunks: []}),
      policy({id: 'missing-chunks', chunks: undefined})
    ],
    now: OVERVIEW_NOW
  });
  assert.equal(metricById(result.metrics, 'policies-unindexed').value, '3');
  assert.deepEqual(Array.from(result.rows, row => row.id).sort(), ['manual-only', 'missing-chunks', 'no-chunks']);
});

test('overview orders attention by severity then age', () => {
  const result = overview.deriveAdminOverview({
    kyc: [kyc({applicationId: 'aging-kyc', status: 'PENDING', submittedAt: '2026-09-20T00:00:00Z'})],
    cases: [compliance({id: 'critical-case', createdAt: '2026-09-21T00:00:00Z'})],
    tickets: [ticket({id: 'aging-ticket', status: 'OPEN', createdAt: '2026-09-20T12:00:00Z'})],
    providers: [provider({id: 'provider-1'})],
    routes: [route({id: 'bad-route', completedCount: 8, failedCount: 2, configuredSuccessRate: 99, effectiveSuccessRate: 80})],
    policies: [policy({id: 'unindexed-policy', chunks: [{manual: true}], createdAt: '2026-09-21T00:00:00Z'})],
    now: OVERVIEW_NOW
  });
  assert.deepEqual(Array.from(result.rows, row => row.id), ['critical-case', 'bad-route', 'aging-kyc', 'aging-ticket', 'unindexed-policy']);
  assert.deepEqual(Array.from(result.rows, row => row.tone), ['critical', 'critical', 'warning', 'warning', 'warning']);
});

test('overview metrics link to filtered queues with source keys', () => {
  const result = overview.deriveAdminOverview({kyc: [], cases: [], tickets: [], providers: [], routes: [], policies: [], now: OVERVIEW_NOW});
  assert.deepEqual(JSON.parse(JSON.stringify(result.metrics.map(item => [item.id, item.path, item.params]))), [
    ['compliance-high', 'admin-compliance', {view: 'HIGH_RISK'}],
    ['kyc-pending', 'admin-kyc', {view: 'PENDING'}],
    ['kyc-aging', 'admin-kyc', {view: 'AGING'}],
    ['routes-attention', 'admin-routes', {view: 'CATALOGUE', status: 'ATTENTION'}],
    ['tickets-open', 'admin-tickets', {view: 'OPEN'}],
    ['tickets-aging', 'admin-tickets', {view: 'AGING'}],
    ['policies-unindexed', 'admin-policies', {view: 'UNINDEXED'}]
  ]);
});

test('loading overview metrics never display a false zero', () => {
  const {page} = overviewPage();
  assert.equal(page.loadAnnouncement(), 'Loading administrator attention data.');
  for (const item of page.metrics()) {
    assert.equal(item.value, 'Loading…', item.id);
    assert.equal(item.pending, true);
  }
  assert.equal(page.rows().length, 0);
  page.disconnected();
});

test('overview metrics always expose complete boolean binding state', async () => {
  const {page, api} = overviewPage();
  for (const item of page.metrics()) {
    assert.equal(typeof item.pending, 'boolean', `${item.id} pending`);
    assert.equal(typeof item.unavailable, 'boolean', `${item.id} unavailable`);
  }

  api.adminKyc.resolve([]); api.complianceCases.resolve([]); api.listForAdmin.resolve({items: [], total: 0});
  api.providers.resolve([]); api.routesAdmin.resolve([]); api.policies.resolve([]);
  await page.loadOverview();

  for (const item of page.metrics()) {
    assert.equal(item.pending, false, `${item.id} pending`);
    assert.equal(item.unavailable, false, `${item.id} unavailable`);
  }
  page.disconnected();
});

test('a provider-source failure suppresses route-derived rows', async () => {
  const {page, api} = overviewPage();
  api.adminKyc.resolve([]); api.complianceCases.resolve([compliance({id: 'high'})]);
  api.listForAdmin.resolve({items: [], total: 0});
  api.providers.reject(new Error('Providers unavailable'));
  api.routesAdmin.resolve([route({id: 'inactive', active: false})]);
  api.policies.resolve([]);
  await page.loadOverview();
  assert.equal(metricById(page.metrics(), 'routes-attention').value, 'Unavailable');
  assert.equal(page.rows().filter(row => row.area === 'Routing').length, 0);
  assert.equal(page.rows().filter(row => row.area === 'Compliance').length, 1);
  assert.equal(page.loadAnnouncement(), 'Overview loaded with unavailable sources.');
  page.disconnected();
});

test('a failed source can be retried without reloading fulfilled sources', async () => {
  const {page, api} = overviewPage();
  api.adminKyc.reject(new Error('KYC unavailable'));
  api.complianceCases.resolve([compliance({id: 'high'})]);
  api.listForAdmin.resolve({items: [], total: 0}); api.providers.resolve([]); api.routesAdmin.resolve([]); api.policies.resolve([]);
  await page.loadOverview();
  assert.equal(page.sources.kyc().status, 'error');
  api.adminKyc.resolve([kyc({applicationId: 'recovered'})]);
  await page.retrySource('kyc');
  assert.equal(page.sources.kyc().status, 'ready');
  assert.equal(metricById(page.metrics(), 'kyc-pending').value, '1');
  assert.equal(metricById(page.metrics(), 'compliance-high').value, '1');
  page.disconnected();
});

test('simultaneous retries for different sources both complete', async () => {
  const {page, api} = overviewPage();
  api.adminKyc.resolve([]); api.complianceCases.resolve([]); api.listForAdmin.resolve({items: [], total: 0});
  api.providers.resolve([]); api.routesAdmin.resolve([]); api.policies.resolve([]);
  await page.loadOverview();
  const kycGate = api.adminKyc.next();
  const complianceGate = api.complianceCases.next();
  const kycRetry = page.retrySource('kyc');
  const complianceRetry = page.retrySource('compliance');
  assert.equal(page.sources.kyc().status, 'loading');
  assert.equal(page.sources.compliance().status, 'loading');
  kycGate.resolve([kyc({applicationId: 'retried-kyc'})]);
  complianceGate.resolve([compliance({id: 'retried-case'})]);
  await Promise.all([kycRetry, complianceRetry]);
  assert.equal(page.sources.kyc().status, 'ready');
  assert.equal(page.sources.compliance().status, 'ready');
  assert.equal(metricById(page.metrics(), 'kyc-pending').value, '1');
  assert.equal(metricById(page.metrics(), 'compliance-high').value, '1');
  page.disconnected();
});

test('a late overview result is ignored after session clear', async () => {
  const {page, api, session} = overviewPage();
  const gates = [api.adminKyc.next(), api.complianceCases.next(), api.listForAdmin.next(), api.providers.next(), api.routesAdmin.next(), api.policies.next()];
  const pending = page.loadOverview();
  session.clear();
  gates[0].resolve([]); gates[1].resolve([]); gates[2].resolve({items: [], total: 0});
  gates[3].resolve([]); gates[4].resolve([]); gates[5].resolve([]);
  await pending;
  assert.equal(page.sources.kyc().status, 'loading');
  assert.equal(page.sources.kyc().data.length, 0);
  assert.equal(metricById(page.metrics(), 'kyc-pending').value, 'Loading…');
  page.disconnected();
});

test('a late overview result is ignored after disconnected', async () => {
  const {page, api} = overviewPage();
  const gates = [api.adminKyc.next(), api.complianceCases.next(), api.listForAdmin.next(), api.providers.next(), api.routesAdmin.next(), api.policies.next()];
  const pending = page.loadOverview();
  page.disconnected();
  gates[0].resolve([]); gates[1].resolve([]); gates[2].resolve({items: [], total: 0});
  gates[3].resolve([]); gates[4].resolve([]); gates[5].resolve([]);
  await pending;
  assert.equal(page.sources.compliance().status, 'loading');
  assert.equal(metricById(page.metrics(), 'compliance-high').value, 'Loading…');
});

test('overview metrics open filtered queues unless unavailable or pending', async () => {
  const {page, api, navigations} = overviewPage();
  api.adminKyc.resolve([kyc({applicationId: 'queued'})]);
  api.complianceCases.resolve([]); api.listForAdmin.resolve({items: [], total: 0});
  api.providers.resolve([]); api.routesAdmin.resolve([]); api.policies.resolve([]);
  await page.loadOverview();
  page.open(metricById(page.metrics(), 'kyc-pending'));
  assert.equal(JSON.stringify(navigations), JSON.stringify([['admin-kyc', {view: 'PENDING'}]]));
  page.open({...metricById(page.metrics(), 'kyc-pending'), unavailable: true, value: 'Unavailable'});
  page.open({...metricById(page.metrics(), 'kyc-pending'), pending: true, value: 'Loading…'});
  assert.equal(navigations.length, 1);
  page.disconnected();
});

test('overview template stays attention-first with an admin gate and no analytics copy', () => {
  const html = fsOverview.readFileSync(pathOverview.join(__dirname, '../src/ts/views/admin.html'), 'utf8');
  for (const token of ['Operations requiring attention', 'attention-metrics', 'Prioritized work', 'Open filtered queue', 'source-health', 'retrySource', 'loadAnnouncement', 'if:session.isAdmin()']) assert.ok(html.includes(token), token);
  assert.match(html, /role="status"/);
  assert.match(html, /role="alert"/);
  assert.doesNotMatch(html, /adminTab/);
  assert.doesNotMatch(html, /Revenue|Customer growth|Total volume|<canvas|Audit trail/i);
});
