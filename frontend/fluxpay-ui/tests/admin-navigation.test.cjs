const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ko = require('knockout');
const ts = require('typescript');
const read = file => fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
const compiled = ts.transpileModule(read('ts/appController.ts'), {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;

function fixture(initialPath='home',role=null,restoredRole=role) {
  const events=new Map();const calls=[];const current=ko.observable(initialPath);
  const user=ko.observable(role?{role}:null);
  const session={user,isAdmin:ko.pureComputed(()=>user()?.role==='ADMIN'),restore:async()=>{user(restoredRole?{role:restoredRole}:null);},clear:()=>user(null)};
  const navigate=(path,params={})=>events.get('fluxpay:navigate')({detail:{path,params}});
  class Router {constructor(){} sync(){return Promise.resolve();} go(route){calls.push(route);current(route.path);return Promise.resolve();}}
  class ModuleAdapter {constructor(){this.koObservableConfig=ko.observable({});}}
  class Selection {constructor(){this.path=current;}}
  const dependencies={knockout:ko,'ojs/ojcorerouter':Router,'ojs/ojmodulerouter-adapter':ModuleAdapter,'ojs/ojknockoutrouteradapter':Selection,'ojs/ojurlparamadapter':class {},'ojs/ojurlpathparamadapter':class {},'ojs/ojcontext':{getPageContext:()=>({getBusyContext:()=>({applicationBootstrapComplete(){}})})},'ojs/ojmodule-element':{},'./services/session':{session,navigate}};
  const context={exports:{},require:name=>dependencies[name],window:{addEventListener:(name,fn)=>events.set(name,fn),scrollTo(){}},document:{addEventListener(){}},location:{hash:''},history:{replaceState(){}},URLSearchParams};
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
  assert.deepEqual(Array.from(f.root.visibleNav(),n=>n.path),['admin','admin-tickets']);
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
test('admin deep link has no sidebar gutter, while public home remains public',async()=>{
  const f=fixture('admin','ADMIN');await f.settle();assert.equal(f.root.isAdminWorkspace(),true);
  f.navigate('home');assert.equal(f.root.isAdminWorkspace(),false);assert.equal(f.root.isPublic(),true);
  const html=read('index.html');
  assert.ok(html.includes('visible:!isPublic()&&!isAdminWorkspace()'));
  assert.ok(!html.includes('class="sidebar"'));
  assert.match(read('css/workspace.css'),/\.workspace-shell\.admin-shell #main\s*\{\s*margin-left:\s*0/);
});

test('five customer tabs retain Activity selection for transfer detail and More for secondary pages',async()=>{
  const f=fixture('dashboard','CUSTOMER');await f.settle();
  assert.deepEqual(Array.from(f.root.bottomNav,n=>n.label),['Home','Wallets','Send','Activity','More']);
  f.navigate('tracking',{payment:'payment-123'});assert.equal(f.current(),'activity');assert.equal(f.calls.at(-1).params.id,'payment-123');assert.equal(f.root.activeTab(),'payments-list');
  f.navigate('tracking');assert.equal(f.current(),'payments-list');
  f.navigate('tickets');assert.equal(f.root.activeTab(),'account');
  assert.ok(!read('index.html').includes('<aside class="sidebar"'));
  assert.ok(read('index.html').includes('id="helper-host"'));
});
test('old tracking matrix bookmarks migrate without losing the payment reference',async()=>{
  const f=fixture();await f.settle();assert.equal(f.migrate('/tracking;payment=111-222'),'activity/111-222');assert.equal(f.migrate('tracking;unused=value'),'payments-list');assert.equal(f.migrate('dashboard'),'dashboard');
});

test('administration exposes transfer routing alongside verification and compliance sections',()=>{
  const source=read('ts/viewModels/admin.ts');
  assert.match(source,/\{\s*id:\s*'routing',\s*label:\s*'Transfer routing'\s*\}/);
  const html=read('ts/views/admin.html');
  assert.ok(html.includes("adminTab()==='routing'"));
  assert.ok(html.includes('with:routing'));
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
