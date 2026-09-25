// tests/admin-ui-contract.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../src');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('FluxPay uses the deliberate system font stack without a web-font dependency', () => {
  const app = read('css/app.css');
  const home = read('css/home.css');
  for (const name of ['Segoe UI Variable Text', 'Segoe UI', 'Helvetica Neue']) {
    assert.ok(app.includes(name), name);
    assert.ok(home.includes(name), `home ${name}`);
  }
  assert.doesNotMatch(app, /src:\s*url\(|@font-face/);
});

test('admin CSS defines stable lists, one workflow modal, and wide-table actions', () => {
  const css = read('css/admin-console.css');
  for (const token of [
    '.admin-list-surface',
    '.review-list-row',
    '.admin-workflow-modal',
    '.admin-modal-header',
    '.admin-modal-body',
    '.admin-modal-footer',
    '.admin-wide-table',
    '.sticky-action',
  ]) assert.ok(css.includes(token), token);
  assert.match(css, /\.admin-workflow-modal\s*\{[^}]*max-height:\s*90dvh/);
  assert.match(css, /\.sticky-action\s*\{[^}]*position:\s*sticky[^}]*right:\s*0/);
});

test('overview metric CSS is a compact single scrolling row', () => {
  const css = read('css/admin-console.css');
  assert.match(css, /\.attention-metrics\s*\{[^}]*grid-auto-flow:\s*column[^}]*overflow-x:\s*auto/);
  assert.match(css, /\.attention-metric\s*\{[^}]*min-height:\s*80px[^}]*text-align:\s*center/);
  assert.match(css, /\.attention-metric-label\s*\{[^}]*white-space:\s*nowrap/);
});

test('admin record workflows are modal-only and icon actions are named', () => {
  const reviewRoutes = ['admin-kyc', 'admin-compliance', 'admin-tickets'];
  for (const route of reviewRoutes) {
    const html = read(`ts/views/${route}.html`);
    assert.match(html, /class="review-list-row"/);
    assert.match(html, /class="admin-confirmation/);
    assert.match(html, /class="[^"]*admin-workflow-modal/);
    assert.doesNotMatch(html, /class="record-detail"|class="evidence-panel"/);
    assert.doesNotMatch(html, />\s*Open\s*<\/button>/);
  }
  for (const route of ['admin-providers', 'admin-routes', 'admin-policies']) {
    const html = read(`ts/views/${route}.html`);
    for (const tag of html.matchAll(/<button[^>]*class="[^"]*icon-action[^"]*"[^>]*>/g)) {
      assert.match(tag[0], /aria-label="[^"]+"/);
      assert.match(tag[0], /title="[^"]+"/);
    }
  }
});

test('workflow modals keep the backdrop separate from the white panel', () => {
  const css = read('css/admin-console.css');
  const compliance = read('ts/views/admin-compliance.html');
  const policies = read('ts/views/admin-policies.html');
  const routes = read('ts/views/admin-routes.html');
  const modalRule = css.match(/\.admin-confirmation \.admin-workflow-modal\s*\{([^}]*)\}/s)?.[1] || '';
  assert.match(modalRule, /background:\s*#fff/);
  assert.match(modalRule, /border-radius:/);
  assert.match(css, /\.admin-modal-footer\s*\{[^}]*align-items:\s*flex-end/s);
  assert.match(compliance, /class="panel admin-workflow-modal compliance-review-modal"/);
  assert.doesNotMatch(policies, /class="[^"]*modal-backdrop[^"]*admin-workflow-modal/);
  assert.match(policies, /class="panel admin-workflow-modal policy-detail-modal"/);
  assert.match(routes, /class="admin-modal-form route-edit-form"/);
  assert.match(routes, /class="admin-modal-body route-edit-body"/);
});

test('KYC modal keeps facts inline and decision controls comfortably sized', () => {
  const html = read('ts/views/admin-kyc.html');
  const css = read('css/admin-console.css');
  assert.match(html, /class="detail-grid kyc-inline-details"/);
  for (const label of ['Document type:', 'Document number:', 'Submitted:']) assert.ok(html.includes(label), label);
  assert.match(html, /class="kyc-decision-form"/);
  assert.match(css, /\.kyc-decision-form\s*\{[^}]*display:\s*grid !important[^}]*grid-template-columns:\s*1fr/s);
  assert.match(css, /\.admin-modal-header h2\s*\{[^}]*margin:\s*4px 0/s);
  assert.match(css, /\.admin-page \.close-button\s*\{[^}]*display:\s*inline-grid[^}]*place-items:\s*center/s);
});

test('KYC and compliance keep scrollable evidence beside stationary decision controls', () => {
  const css = read('css/admin-console.css');
  for (const route of ['admin-kyc', 'admin-compliance']) {
    const html = read('ts/views/' + route + '.html');
    assert.match(html, /class="[^"]*admin-modal-workspace[^"]*"/, route + ' workspace');
    assert.match(html, /class="[^"]*admin-modal-info-pane[^"]*"/, route + ' information pane');
    assert.match(html, /class="[^"]*admin-modal-action-pane[^"]*"/, route + ' action pane');
  }
  assert.match(css, /\.admin-modal-workspace\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1\.65fr\)\s+minmax\(280px,\s*0?\.85fr\)[^}]*overflow:\s*hidden/s);
  assert.match(css, /\.admin-modal-info-pane\s*\{[^}]*overflow-y:\s*auto/s);
  assert.match(css, /\.admin-modal-action-pane\s*\{[^}]*border-left:[^}]*overflow-y:\s*auto/s);
  assert.match(css, /@media \(max-width: 900px\)[\s\S]*\.admin-modal-workspace\s*\{[^}]*grid-template-columns:\s*1fr/s);
});

test('policy details do not render the implementation document hash', () => {
  const html = read('ts/views/admin-policies.html');
  assert.doesNotMatch(html, /Document hash|text:documentHash/);
  assert.match(html, /<dt>Created<\/dt>/);
});

test('admin read-only identifiers use copy-only controls instead of rendering UUIDs', () => {
  const compliance = read('ts/views/admin-compliance.html');
  const tickets = read('ts/views/admin-tickets.html');
  const policies = read('ts/views/admin-policies.html');

  for (const binding of ['text:id', 'text:paymentId', 'text:reviewReference', 'text:decidedBy']) {
    assert.doesNotMatch(compliance, new RegExp(binding.replace(':', '\\:')));
  }
  for (const binding of ['text:userId', 'text:paymentId', 'text:assigneeAdminId']) {
    assert.doesNotMatch(tickets, new RegExp(binding.replace(':', '\\:')));
  }
  assert.doesNotMatch(policies, /item\.paymentId|guidanceCaseViewer\(\)\.paymentId|Case guidance[^<]*complianceCaseId/);

  for (const label of ['Copy case ID', 'Copy payment ID', 'Copy review reference']) {
    assert.ok(compliance.includes(`aria-label="${label}"`), label);
  }
  for (const label of ['Copy customer ID', 'Copy payment ID', 'Copy assignee ID']) {
    assert.ok(tickets.includes(`aria-label="${label}"`), label);
  }
  assert.match(policies, /aria-label="Copy (case|payment) ID"/);
  assert.match(read('ts/views/admin-copilot.html'), /<input[^>]*aria-label="Payment ID"/);
});

test('admin modal spacing uses a consistent rhythm without three-column detail congestion', () => {
  const css = read('css/admin-console.css');
  assert.match(css, /\.admin-modal-header\s*\{[^}]*padding:\s*20px 24px 16px/s);
  assert.match(css, /\.admin-modal-body\s*\{[^}]*padding:\s*20px 24px/s);
  assert.match(css, /\.admin-modal-action-pane\s*\{[^}]*gap:\s*16px[^}]*padding:\s*20px 24px/s);
  assert.match(css, /\.modal-detail-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)[^}]*gap:\s*18px/s);
});

test('short admin workflows stay compact instead of stretching across the viewport', () => {
  const css = read('css/admin-console.css');
  assert.match(css, /\.compliance-review-queue\s*\{[^}]*max-width:\s*860px[^}]*margin:\s*0 auto/s);
  assert.match(css, /\.compliance-review-queue\s*>\s*label\s*\{[^}]*max-width:\s*360px/s);
  assert.match(css, /\.compliance-review-queue\s+:is\(\.review-list-heading,\s*\.review-list-row\)\s*\{[^}]*grid-template-columns:\s*minmax\(90px,\s*0?\.45fr\) minmax\(220px,\s*1\.5fr\) minmax\(100px,\s*0?\.55fr\) 24px/s);
  assert.match(css, /\.provider-editor-modal\s*\{[^}]*width:\s*min\(760px,\s*calc\(100vw - 40px\)\)/s);
  assert.match(css, /\.provider-editor-body\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/s);
  assert.match(css, /\.route-editor-modal\s*\{[^}]*width:\s*min\(900px,\s*calc\(100vw - 40px\)\)/s);
  assert.match(css, /\.policy-detail-modal\s*\{[^}]*grid-template-rows:\s*auto auto auto[^}]*width:\s*min\(880px,\s*calc\(100vw - 40px\)\)/s);
  assert.match(css, /:is\(\.provider-editor-modal,\s*\.route-editor-modal\)\s*:is\(input,\s*select\)\s*\{[^}]*min-height:\s*42px[^}]*padding:\s*10px 12px/s);
  assert.match(css, /:is\(\.provider-editor-modal,\s*\.route-editor-modal\)\s+label\s*\{[^}]*margin-bottom:\s*10px/s);
  assert.match(css, /\.policy-detail-modal \.admin-modal-body\s*\{[^}]*max-height:\s*56dvh/s);
});

test('Copilot uses one payment text field and centers its welcome mark', () => {
  const html = read('ts/views/admin-copilot.html');
  const css = read('css/admin-console.css');
  const toolbar = html.slice(html.indexOf('copilot-chat-toolbar'), html.indexOf('</header>', html.indexOf('copilot-chat-toolbar')));
  assert.doesNotMatch(toolbar, /<select/);
  assert.match(toolbar, /<input[^>]*aria-label="Payment ID"/);
  assert.match(css, /\.copilot-avatar\s*\{[^}]*margin:\s*0 auto/s);
});

test('dense modal forms collapse before their controls can clip', () => {
  const css = read('css/admin-console.css');
  assert.match(css, /\.kyc-decision-form\s*\{[^}]*grid-template-columns:\s*1fr/);
  assert.match(css, /\.compliance-decision-form\s*\{[^}]*grid-template-columns:\s*1fr/);
  assert.match(css, /@media \(max-width: 640px\)[\s\S]*\.route-edit-form \.form-row\s*\{[^}]*grid-template-columns:\s*1fr/);
});
