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
