const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const ko = require('knockout');
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8');

function presentation(reduced = false) {
  const instances=[];
  class Page { password=ko.observable(''); disconnected(){this.closed=true;} }
  class Motion { constructor(root,scene,paused){this.paused=paused;this.refreshes=0;this.disposed=false;instances.push(this);} refresh(){this.refreshes++;} dispose(){this.disposed=true;} }
  const context={exports:{},require:name=>name==='knockout'?ko:name==='./page'?{Page}:{LandingMotion:Motion},window:{matchMedia:()=>({matches:reduced})},document:{querySelector:()=>({})}};
  vm.runInNewContext(ts.transpileModule(read('ts/services/auth-page.ts'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);
  return {page:new context.exports.AuthPage(),instances};
}
test('password reveal is presentation-only and resets when leaving the screen',()=>{
  const {page}=presentation();assert.equal(page.passwordVisible(),false);
  page.password('test-only');page.togglePassword();assert.equal(page.passwordVisible(),true);
  assert.equal(page.password(),'test-only');page.disconnected();assert.equal(page.password(),'');
  assert.equal(page.passwordVisible(),false);assert.equal(page.closed,true);
});
test('auth background respects reduced motion and cleans up on navigation',()=>{
  const {page,instances}=presentation(true);page.connected();assert.equal(instances[0].paused(),true);
  page.toggleMotion();assert.equal(instances[0].paused(),false);assert.equal(instances[0].refreshes,1);
  page.connected();assert.equal(instances[0].disposed,true);
  page.disconnected();assert.equal(instances[1].disposed,true);
});
test('auth forms keep real submit bindings, native validation and accessible password controls',()=>{
  for(const [screen,action] of [['login','signIn'],['register','signUp']]) {
    const html=read('ts/views/'+screen+'.html');
    assert.ok(html.includes('submit:'+action));
    assert.ok(html.includes('disable:busy'));
    assert.ok(html.includes('role="alert"'));
    assert.ok(html.includes('autocomplete="username"'));
    assert.ok(html.includes('name="password"'));
    assert.ok(html.includes('aria-controls="'+(screen==='login'?'login':'signup')+'-password"'));
    for(const match of html.matchAll(/(?:data-src|poster)="([^"]+)"/g))assert.ok(fs.existsSync(path.join(__dirname,'../src',match[1])));
  }
  assert.ok(read('ts/views/register.html').includes('minlength="8"'));
  assert.ok(read('ts/views/register.html').includes('maxlength="72"'));
});
test('workspace stylesheet is loaded after existing styles and is isolated from public pages',()=>{
  const index=read('index.html');assert.ok(index.indexOf('css/workspace.css')>index.indexOf('css/app.css'));
  const css=read('css/workspace.css');
  for(const selector of ['.dashboard-grid','.table-scroll','.balance-card','.recipient-card','.progress-steps','.timeline','.verification-symbol','.profile-badge','.modal'])assert.ok(css.includes(selector));
  assert.ok(css.includes('prefers-reduced-motion'));
  assert.ok(!css.includes('url(http'));
  assert.ok(css.includes('animation: fw-page-enter'));
  const pageAnimation=css.split('@keyframes fw-page-enter')[1].split('@keyframes fw-enter')[0];
  assert.ok(!pageAnimation.includes('transform:'), 'page animation must not trap fixed-position dialogs');
});
