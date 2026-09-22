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
  const api = load('ts/services/flux-api.ts', {}, {
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
