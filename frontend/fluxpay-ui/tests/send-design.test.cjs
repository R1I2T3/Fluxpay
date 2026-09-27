const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript'),ko=require('knockout');
const read=file=>fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
test('preferred quotes follow the server recommendation and retain expiry and payment safeguards',()=>{
 const html=read('ts/views/payments-new.html'),css=read('css/product-polish.css');
 assert.ok(html.includes('css:{preferred:recommended}'));
 assert.ok(html.includes("recommended?'Preferred option':'Delivery option'"));
 assert.ok(html.includes('disable:!$parent.quoteValid()||$parent.busy()'));
 assert.ok(html.includes('click:$parent.chooseQuote'));assert.ok(html.includes('click:sendPayment'));
 assert.ok(html.includes('click:saveDraft'));assert.ok(html.includes('commits funds'));
 assert.match(css,/\.quote-card\.recommended\s*\{[^}]*border-color:\s*#2465d8/);
 assert.ok(css.includes('@media (max-width: 600px)'));
});
test('step headings receive focus only after a change and cleanup cancels pending work',()=>{
 const timers=new Map(),disposals=[];let id=0,focused=0,scrolled=0,modal=false;
 const bindings={};const mockKo={bindingHandlers:bindings,utils:{domNodeDisposal:{addDisposeCallback:(_,fn)=>disposals.push(fn)}}};
 const context={exports:{},require:()=>mockKo,window:{setTimeout:fn=>{timers.set(++id,fn);return id;}},clearTimeout:id=>timers.delete(id),document:{querySelector:()=>modal?{}:null}};
 vm.runInNewContext(ts.transpileModule(read('ts/services/experience-dialog.ts'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);
 const step=ko.observable(1),element={isConnected:true,closest:()=>null,focus:()=>focused++,scrollIntoView:()=>scrolled++};
 bindings.experienceStepFocus.init(element,()=>step);assert.equal(timers.size,0);
 step(2);for(const [id,fn] of timers){timers.delete(id);fn();}assert.equal(focused,1);assert.equal(scrolled,1);
 modal=true;step(3);for(const [id,fn] of timers){timers.delete(id);fn();}assert.equal(focused,1);
 step(4);disposals[0]();assert.equal(timers.size,0);step(1);assert.equal(timers.size,0);
});
