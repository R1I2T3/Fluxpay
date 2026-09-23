const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ko = require('knockout');
const ts = require('typescript');
const read = file => fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
const compiled = ts.transpileModule(read('ts/appController.ts'), {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
const adminConsoleCompiled = ts.transpileModule(read('ts/services/admin-console.ts'), {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
function loadAdminConsole() {
  const context = {exports:{}};
  vm.runInNewContext(adminConsoleCompiled, context);
  return context.exports;
}
const adminConsole = loadAdminConsole();

function fixture(initialPath='home',role=null,restoredRole=role,env={}) {
  const events=new Map();const calls=[];const current=ko.observable(initialPath);
  const user=ko.observable(role?{role}:null);
  const session={user,isAdmin:ko.pureComputed(()=>user()?.role==='ADMIN'),restore:async()=>{user(restoredRole?{role:restoredRole}:null);},clear:()=>user(null)};
  const navigate=(path,params={})=>events.get('fluxpay:navigate')({detail:{path,params}});
  class Router {constructor(){} sync(){return Promise.resolve();} go(route){calls.push(route);current(route.path);return Promise.resolve();}}
  class ModuleAdapter {constructor(){this.koObservableConfig=ko.observable({});}}
  class Selection {constructor(){this.path=current;}}
  const hostname = env.hostname || 'localhost';
  const configuredEnv = Object.prototype.hasOwnProperty.call(env,'FLUXPAY_ENVIRONMENT') ? env.FLUXPAY_ENVIRONMENT : undefined;
  const dependencies={knockout:ko,'ojs/ojcorerouter':Router,'ojs/ojmodulerouter-adapter':ModuleAdapter,'ojs/ojknockoutrouteradapter':Selection,'ojs/ojurlparamadapter':class {},'ojs/ojurlpathparamadapter':class {},'ojs/ojcontext':{getPageContext:()=>({getBusyContext:()=>({applicationBootstrapComplete(){}})})},'ojs/ojmodule-element':{},'./services/session':{session,navigate},'./services/admin-console':adminConsole};
  const context={exports:{},require:name=>dependencies[name],window:{addEventListener:(name,fn)=>events.set(name,fn),scrollTo(){},location:{hostname},FLUXPAY_ENVIRONMENT:configuredEnv},document:{addEventListener(){}},location:{hash:'',search:'',pathname:'/'},history:{replaceState(){}},URLSearchParams};
  dependencies['./services/notifications']=require('./notification-fixture.cjs')();
  const sounds=[];
  dependencies['./services/transaction-sound']={prepareTransactionSound(){},playTransactionSuccessSound(){sounds.push('success');}};
  vm.runInNewContext(compiled,context);
  return {root:context.exports.default,migrate:context.exports.migrateTrackingBookmark,session,navigate,calls,current,events,sounds,settle:async()=>{await new Promise(resolve=>setImmediate(resolve));}};
}

test('success dialog opens for signed-in transactions and closes on activity navigation or logout',async()=>{
  const f=fixture('wallets','CUSTOMER');await f.settle();const detail={title:'Money added',description:'Added to wallet',amount:'$10.00'};
  f.events.get('fluxpay:transaction-success')({detail});assert.equal(f.root.transactionSuccess(),detail);assert.equal(f.sounds.length,1);
  f.root.viewTransactionActivity();assert.equal(f.root.transactionSuccess(),undefined);assert.equal(f.calls.at(-1).path,'payments-list');
  f.events.get('fluxpay:transaction-success')({detail});f.session.clear();assert.equal(f.root.transactionSuccess(),undefined);
  f.events.get('fluxpay:transaction-success')({detail});assert.equal(f.root.transactionSuccess(),undefined);assert.equal(f.sounds.length,2);
  const html=read('index.html');assert.ok(html.includes('experienceDialog:closeTransactionSuccess'));assert.ok(html.includes('aria-describedby="transaction-success-description"'));assert.ok(html.includes("attr:{inert:transactionSuccess()?'':null}"));
});
test('verification reminder follows server status and disappears after submission',async()=>{
  const f=fixture('dashboard','CUSTOMER');await f.settle();
  for(const status of ['NONE','NOT_SUBMITTED','UNVERIFIED']){f.session.user({role:'CUSTOMER',kycStatus:status});assert.equal(f.root.needsVerificationSubmission(),true);}
  for(const status of ['PENDING','VERIFIED','REJECTED']){f.session.user({role:'CUSTOMER',kycStatus:status});assert.equal(f.root.needsVerificationSubmission(),false);}
  f.session.user({role:'ADMIN',kycStatus:'NONE'});assert.equal(f.root.needsVerificationSubmission(),false);
  f.session.user(null);assert.equal(f.root.needsVerificationSubmission(),false);
});
test('admin login dashboard request opens Administration without customer navigation',async()=>{
  const f=fixture();await f.settle();f.session.user({role:'ADMIN'});f.navigate('dashboard');
  assert.equal(f.calls.at(-1).path,'admin');assert.equal(f.root.accountPath(),'admin');
  assert.deepEqual(Array.from(f.root.visibleNav(),n=>n.path),['admin']);
  assert.equal(f.root.isAdminWorkspace(),true);
});
test('restoring an admin on an old dashboard bookmark opens Administration',async()=>{
  const f=fixture('dashboard',null,'ADMIN');await f.settle();
  assert.equal(f.current(),'admin');assert.equal(f.root.isAdminWorkspace(),true);
});
test('customer login and navigation remain unchanged',async()=>{
  const f=fixture('home','CUSTOMER');await f.settle();f.navigate('dashboard');
  assert.equal(f.current(),'dashboard');assert.equal(f.root.accountPath(),'dashboard');
  assert.equal(f.root.isAdminWorkspace(),false);
  assert.ok(f.root.visibleNav().some(n=>n.path==='wallets'));
  assert.ok(!f.root.visibleNav().some(n=>n.path==='admin'));
  f.root.menuOpen(true);f.navigate('wallets');assert.equal(f.root.menuOpen(),false);
});
test('signed-out and customer admin deep links keep the customer shell',async()=>{
  const signedOut=fixture('admin');await signedOut.settle();
  assert.equal(signedOut.root.isAdminWorkspace(),false);
  const customer=fixture('admin-routes','CUSTOMER');await customer.settle();
  assert.equal(customer.root.isAdminWorkspace(),false);
  assert.ok(customer.root.bottomNav.length>0);
});
test('admin shell exposes the approved grouped routes and no Governance group', async () => {
  const f=fixture('admin','ADMIN');await f.settle();
  assert.deepEqual(Array.from(f.root.adminNavGroups,group=>[group.label,Array.from(group.items,item=>item.path)]),[
    ['Overview',['admin']],
    ['Operations',['admin-kyc','admin-compliance','admin-tickets']],
    ['Money Movement',['admin-providers','admin-routes']],
    ['Policy & AI',['admin-policies','admin-copilot']]
  ]);
  assert.equal(f.root.isAdminWorkspace(),true);
  assert.equal(f.root.activeAdminPath(),'admin');
  assert.ok(!JSON.stringify(f.root.adminNavGroups).includes('Audit'));
  assert.ok(!JSON.stringify(f.root.adminNavGroups).includes('Governance'));
});
test('environment display cannot change the API base URL', () => {
  const controller=read('ts/appController.ts');
  const api=read('ts/services/flux-api.ts');
  assert.match(controller,/FLUXPAY_ENVIRONMENT/);
  assert.match(api,/FLUXPAY_API_URL/);
  assert.doesNotMatch(api,/FLUXPAY_ENVIRONMENT/);
  assert.doesNotMatch(controller,/API_PROXY/);
});
test('sidebar markup is guarded to administrator routes without an environment badge', () => {
  const html=read('index.html');
  const css=read('css/admin-console.css');
  const workspaceCss=read('css/workspace.css');
  assert.match(html,/<!-- ko if:isAdminWorkspace -->[\s\S]*class="admin-sidebar"/);
  assert.match(html,/foreach:adminNavGroups/);
  assert.match(html,/'aria-current':\$root\.activeAdminPath\(\)===path\?'page':null/);
  assert.doesNotMatch(html,/class="admin-environment"/);
  assert.doesNotMatch(html,/Audit Log|Governance/);
  assert.doesNotMatch(css,/\.admin-environment/);
  assert.doesNotMatch(workspaceCss,/\.workspace-shell\.admin-shell #main\s*\{\s*margin-left:\s*0;/);
});

test('admin workspaces use responsive cards and queue cells wrap their data', () => {
  const css=read('css/admin-console.css');
  assert.match(css,/\.review-workspace\s*\{[^}]*gap:\s*16px[^}]*border:\s*0[^}]*background:\s*transparent/);
  assert.match(css,/\.review-workspace:not\(\.has-selection\)\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*760px\)[^}]*min-height:\s*0/);
  assert.match(css,/\.copilot-context h2[^}]*font-size:\s*20px/);
  assert.match(css,/\.dense-table td\s*\{[^}]*white-space:\s*normal[^}]*overflow-wrap:\s*anywhere/);
  assert.match(css,/@media\s*\(max-width:\s*1320px\)\s*\{[\s\S]*?\.review-workspace,\s*\.copilot-workspace\s*\{[^}]*grid-template-columns:\s*minmax\(300px,\s*36%\) minmax\(0,\s*1fr\)/);
});
test('environment label follows configuration without changing navigation or API stubs', async () => {
  const sandbox=fixture('admin','ADMIN');await sandbox.settle();
  const staging=fixture('admin','ADMIN','ADMIN',{hostname:'localhost',FLUXPAY_ENVIRONMENT:'STAGING'});await staging.settle();
  assert.equal(sandbox.root.environment.label,'Sandbox');
  assert.equal(staging.root.environment.label,'Staging');
  assert.deepEqual(
    Array.from(staging.root.adminNavGroups,group=>Array.from(group.items,item=>item.path)),
    Array.from(sandbox.root.adminNavGroups,group=>Array.from(group.items,item=>item.path))
  );
  assert.equal(staging.root.adminPaths.length,sandbox.root.adminPaths.length);
});

test('five customer tabs retain Activity selection for transfer detail and More for secondary pages',async()=>{
  const f=fixture('dashboard','CUSTOMER');await f.settle();
  assert.deepEqual(Array.from(f.root.bottomNav,n=>n.label),['Home','Wallets','Send','Activity','More']);
  f.navigate('tracking',{payment:'payment-123'});assert.equal(f.current(),'activity');assert.equal(f.calls.at(-1).params.id,'payment-123');assert.equal(f.root.activeTab(),'payments-list');
  f.navigate('tracking');assert.equal(f.current(),'payments-list');
  f.navigate('tickets');assert.equal(f.root.activeTab(),'account');
  assert.ok(read('index.html').includes('id="helper-host"'));
  assert.match(read('index.html'),/<!-- ko if:isAdminWorkspace -->[\s\S]*class="admin-sidebar"/);
});
test('old tracking matrix bookmarks migrate without losing the payment reference',async()=>{
  const f=fixture();await f.settle();assert.equal(f.migrate('/tracking;payment=111-222'),'activity/111-222');assert.equal(f.migrate('tracking;unused=value'),'payments-list');assert.equal(f.migrate('dashboard'),'dashboard');
});

test('administration is a risk-prioritized overview without a tab host',()=>{
  const source=read('ts/viewModels/admin.ts');
  assert.doesNotMatch(source,/adminTab|selectTab/);
  assert.match(source,/deriveAdminOverview/);
  assert.match(source,/loadOverview/);
  assert.match(source,/retrySource/);
  const html=read('ts/views/admin.html');
  assert.match(html,/Operations requiring attention/);
  assert.match(html,/Prioritized work/);
  assert.ok(html.includes('if:session.isAdmin()'));
  assert.doesNotMatch(html,/adminTab/);
});

test('quick-pay recipient and new-person intent use URL path parameters, keeping Send selected',async()=>{
  const f=fixture('dashboard','CUSTOMER');await f.settle();f.navigate('payments-new',{recipient:'r2'});
  assert.equal(f.calls.at(-1).path,'send');assert.equal(f.calls.at(-1).params.recipient,'r2');assert.equal(f.root.activeTab(),'payments-new');
  f.navigate('payments-new',{action:'add-recipient'});assert.equal(f.calls.at(-1).params.recipient,'new');
  assert.match(read('ts/appController.ts'),/path:'send\/\{recipient\}'/);
  f.navigate('wallets',{action:'add-money'});assert.equal(f.calls.at(-1).path,'add-money');
});

test('dashboard wallet actions preserve their intent in reloadable routes and select Wallets',async()=>{
  const f=fixture('dashboard','CUSTOMER');await f.settle();
  for(const action of ['transfer','withdraw']){f.navigate('wallets',{action});assert.equal(f.calls.at(-1).path,'wallet-action');assert.equal(f.calls.at(-1).params.action,action);assert.equal(f.root.activeTab(),'wallets');}
  assert.match(read('ts/appController.ts'),/path:'wallet-action\/\{action\}'/);
});
