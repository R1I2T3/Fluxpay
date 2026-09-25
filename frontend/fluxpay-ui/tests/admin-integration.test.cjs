const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.join(__dirname,'../src');
const read=file=>fs.readFileSync(path.join(root,file),'utf8');

const routes=['admin','admin-kyc','admin-compliance','admin-tickets','admin-payment-operations','admin-providers','admin-routes','admin-policies','admin-copilot'];
const templates=routes.map(route=>read(`ts/views/${route}.html`));

test('every approved administrator route has a view model and template',()=>{
  const controller=read('ts/appController.ts');
  for(const route of routes){
    assert.ok(fs.existsSync(path.join(root,`ts/viewModels/${route}.ts`)),route+' view model');
    assert.ok(fs.existsSync(path.join(root,`ts/views/${route}.html`)),route+' template');
    assert.ok(controller.includes(`path:'${route}'`),route+' router entry');
  }
});

test('unsupported governance and unrelated destructive resource controls are absent',()=>{
  const joined=templates.join('\n');
  assert.doesNotMatch(joined,/Audit Log|Governance/);
  assert.doesNotMatch(read('ts/views/admin-compliance.html'),/delete-case|Delete manual case/);
  assert.doesNotMatch(read('ts/views/admin-providers.html'),/requestDeleteProvider|Delete provider|Remove provider/i);
  assert.doesNotMatch(read('ts/views/admin-routes.html'),/requestDeleteRoute|Delete route|Remove route/i);
  assert.match(read('ts/views/admin-policies.html'),/askConfirmation\('delete-policy'\)/);
});

test('templates use safe bindings and accessible status text',()=>{
  for(const html of templates){
    assert.doesNotMatch(html,/data-bind="[^"]*\bhtml\s*:/);
    assert.match(html,/Administrator access required/);
    assert.match(html,/session\.isAdmin\(\)/);
    assert.match(html,/role="alert"/);
    assert.match(html,/role="status"[^>]*aria-live="polite"/);
  }
  assert.match(read('ts/views/admin-copilot.html'),/policyAnswer:/);
  assert.match(read('index.html'),/aria-current/);
  assert.match(read('index.html'),/Skip to content/);
  assert.match(read('ts/views/admin.html'),/Open filtered queue/);
});

test('every administrator workspace announces its loading state politely',()=>{
  const expected={
    'admin-kyc':'Loading KYC reviews',
    'admin-compliance':'Loading compliance cases',
    'admin-tickets':'Loading support tickets',
    'admin-payment-operations':'Loading payment operations',
    'admin-providers':'Loading providers',
    'admin-routes':'Loading payout routes',
    'admin-policies':'Loading policies',
    'admin-copilot':'Loading Compliance Copilot'
  };
  for(const [route,message] of Object.entries(expected)){
    const html=read(`ts/views/${route}.html`);
    assert.match(html,new RegExp(`class="[^"]*admin-loading-status[^"]*"\\s+role="status"\\s+aria-live="polite"\\s+data-bind="[^"]*busy\\(\\)[^"]*${message}`),route);
  }
});

test('overview and support controls are inside the administrator gate',()=>{
  for(const route of ['admin','admin-tickets']){
    const html=read(`ts/views/${route}.html`);
    const gate=html.indexOf('<!-- ko if: session.isAdmin() -->');
    assert.ok(gate>=0&&gate<html.indexOf('<header'),route);
  }
});

test('review queues use native open buttons and keyboard focus targets',()=>{
  const reviews=[
    ['admin-kyc','kyc-record-heading'],
    ['admin-compliance','compliance-record-heading'],
    ['admin-tickets','support-record-heading']
  ];
  for(const [route,heading] of reviews){
    const html=read(`ts/views/${route}.html`);
    const viewModel=read(`ts/viewModels/${route}.ts`);
    if(route==='admin-kyc') {
      assert.match(html,/class="review-list-row"/);
      assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
      assert.match(viewModel,/openReviewDocument/);
    } else if(route==='admin-compliance') {
      assert.match(html,/class="review-list-row"/);
      assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
      assert.match(viewModel,/copyIdentifier/);
    } else {
      assert.match(html,/class="review-list-row"/);
      assert.doesNotMatch(html,/>\s*Open\s*<\/button>/);
      assert.match(viewModel,/closeTicket/);
    }
    assert.match(html,new RegExp(`id="${heading}"[^>]*tabindex="-1"`));
    assert.match(viewModel,/focusRecordHeading/);
  }
});

test('environment display and API routing remain separate concerns',()=>{
  assert.match(read('ts/appController.ts'),/FLUXPAY_ENVIRONMENT/);
  assert.doesNotMatch(read('ts/services/flux-api.ts'),/FLUXPAY_ENVIRONMENT/);
  assert.match(read('ts/services/flux-api.ts'),/FLUXPAY_API_URL/);
  assert.match(fs.readFileSync(path.join(__dirname,'../scripts/hooks/before_serve.js'),'utf8'),/API_PROXY/);
});

test('the existing authentication expiry boundary remains unchanged',()=>{
  const api=read('ts/services/flux-api.ts');
  // Prettier inserts spaces around `===`; match the boundary token tolerantly.
  assert.match(api,/response\.status\s*===\s*401/);
  assert.match(api,/sessionStorage\.removeItem\('fluxpay\.token'\)/);
  assert.match(api,/fluxpay:expired/);
  assert.match(read('ts/services/session.ts'),/window\.addEventListener\('fluxpay:expired'/);
});
