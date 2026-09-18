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
  const dependencies={knockout:ko,'ojs/ojcorerouter':Router,'ojs/ojmodulerouter-adapter':ModuleAdapter,'ojs/ojknockoutrouteradapter':Selection,'ojs/ojurlparamadapter':class {},'ojs/ojcontext':{getPageContext:()=>({getBusyContext:()=>({applicationBootstrapComplete(){}})})},'ojs/ojmodule-element':{},'./services/session':{session,navigate}};
  const context={exports:{},require:name=>dependencies[name],window:{addEventListener:(name,fn)=>events.set(name,fn),scrollTo(){}},document:{addEventListener(){}},location:{hash:''},history:{replaceState(){}},URLSearchParams};
  vm.runInNewContext(compiled,context);
  return {root:context.exports.default,session,navigate,calls,current,settle:async()=>{await new Promise(resolve=>setImmediate(resolve));}};
}
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
test('admin deep link has no sidebar gutter, while public home remains public',async()=>{
  const f=fixture('admin','ADMIN');await f.settle();assert.equal(f.root.isAdminWorkspace(),true);
  f.navigate('home');assert.equal(f.root.isAdminWorkspace(),false);assert.equal(f.root.isPublic(),true);
  const html=read('index.html');
  assert.ok(html.includes('visible:!isPublic()&&!isAdminWorkspace()'));
  assert.ok(html.includes('visible:!session.isAdmin()&&!isAdminWorkspace()'));
  assert.match(read('css/workspace.css'),/\.workspace-shell\.admin-shell #main\s*\{\s*margin-left:\s*0/);
});
