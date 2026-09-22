const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const ko=require('knockout');
const read=file=>fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
const compile=file=>ts.transpileModule(read(file),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
const id='11111111-1111-4111-8111-111111111111';
const policy={id,title:'QA policy',category:'PAYMENT_REVIEW',content:'Review source of funds.',documentHash:'test-hash',createdAt:'2026-09-17T10:00:00Z',chunks:[]};
const manual={id,paymentId:id,reviewReference:null,risk:'HIGH',status:'OPEN',riskReasons:['QA reason'],suggestedAction:'Review records',decidedBy:null,decidedAt:null,decisionReason:null,createdAt:policy.createdAt};
function workspace(overrides={},admin=true,runtime={}){
  const calls=[];
  const api=new Proxy(overrides,{get:(obj,name)=>async(...args)=>{calls.push([name,...args]);if(name in obj)return obj[name](...args);if(name==='policies')return [policy];if(name==='policy'||name==='createPolicy')return {...policy};if(name==='policyChunks')return [];if(name==='complianceCases')return [manual];if(name==='complianceCase'||name==='createComplianceCase')return {...manual};if(name==='decideComplianceCase')return {...manual,status:args[1]==='approve'?'APPROVED':'REJECTED'};if(name==='indexPolicy')return {policyDocumentId:id,chunkCount:2};if(name==='askCopilot')return {answer:'A sourced answer',sources:[]};}});
  const session={user:ko.observable({role:admin?'ADMIN':'USER'})};session.isAdmin=ko.pureComputed(()=>session.user()?.role==='ADMIN');
  const context={exports:{},require:name=>name==='knockout'?ko:name==='./session'?{session}:{fluxApi:api},...runtime};
  vm.runInNewContext(compile('ts/services/compliance-workspace.ts'),context);
  return {page:new context.exports.ComplianceWorkspace(),calls,session};
}
test('all 15 new endpoints use correct URLs, verbs, bodies and auth; DELETE accepts 204',async()=>{
  const calls=[];
  const context={exports:{},window:{},sessionStorage:{getItem:()=> 'test-token'},crypto:{randomUUID:()=>id},fetch:async(url,options)=>{calls.push([url,options]);return {ok:true,status:options.method==='DELETE'?204:200,json:async()=>({data:[]})};}};
  vm.runInNewContext(compile('ts/services/flux-api.ts'),context);const api=context.exports.fluxApi;
  await api.policies();await api.policy(id);await api.createPolicy({title:'Policy',category:'AML',content:'Text'});await api.updatePolicy(id,{title:'Updated policy',category:'AML',content:'Updated text'});await api.policyChunks(id);await api.addPolicyChunk(id,'Chunk');await api.indexPolicy(id);await api.deletePolicy(id);
  await api.complianceCases('OPEN');await api.complianceCase(id);await api.createComplianceCase({paymentId:id,risk:'LOW',riskReasons:['Reason'],suggestedAction:'Review'});await api.decideComplianceCase(id,'approve','Approved');await api.decideComplianceCase(id,'reject','Rejected');await api.deleteComplianceCase(id);await api.askCopilot('Question',id);
  assert.equal(calls.length,15);
  assert.deepEqual(calls.map(([url,o])=>[o.method,url]),[['GET','/api/policies'],['GET',`/api/policies/${id}`],['POST','/api/policies'],['PUT',`/api/policies/${id}`],['GET',`/api/policies/${id}/chunks`],['POST',`/api/policies/${id}/chunks`],['POST',`/api/policies/${id}/index`],['DELETE',`/api/policies/${id}`],['GET','/api/compliance/cases?status=OPEN'],['GET',`/api/compliance/cases/${id}`],['POST','/api/compliance/cases'],['PUT',`/api/compliance/cases/${id}/approve`],['PUT',`/api/compliance/cases/${id}/reject`],['DELETE',`/api/compliance/cases/${id}`],['POST','/api/copilot/ask']]);
  for(const [,options] of calls)assert.equal(options.headers.Authorization,'Bearer test-token');
  assert.deepEqual(JSON.parse(calls[3][1].body),{title:'Updated policy',category:'AML',content:'Updated text'});
  assert.deepEqual(JSON.parse(calls[11][1].body),{decisionReason:'Approved'});
  await api.complianceCases('ALL');assert.equal(calls.at(-1)[0],'/api/compliance/cases');
  await api.askCopilot('Question');assert.deepEqual(JSON.parse(calls.at(-1)[1].body),{question:'Question'});
});

test('live Copilot accumulates text and offers the separate cited response without duplicate automatic requests',async()=>{
 const {page,calls}=workspace({streamCopilot:async(q,id,delta)=>{delta('Live ');delta('answer');}},true,{AbortController});page.liveResponse(true);page.question('When is review needed?');await page.ask();assert.equal(page.answer().answer,'Live answer');assert.equal(page.streamedAnswer(),true);assert.equal(calls.filter(c=>c[0]==='askCopilot').length,0);page.liveResponse(false);await page.ask();assert.equal(page.streamedAnswer(),false);assert.equal(page.answer().answer,'A sourced answer');
});

test('saving manual chunk and guidance edits closes their editor after a successful write',async()=>{
 const {page}=workspace({policyGuidance:async()=>[],updatePolicyGuidance:async()=>({}),updatePolicyChunk:async()=>({})});page.policy({...policy});page.chunkEdit({id:'chunk',manual:true,content:'Old'});page.chunkEditContent('Updated');await page.saveChunkEdit();assert.equal(page.chunkEdit(),undefined);page.guidanceEdit({id:'guidance',content:'Old'});page.guidanceEditContent('Updated');await page.saveGuidanceEdit();assert.equal(page.guidanceEdit(),undefined);
});
test('policy import parser accepts a single object or array and rejects invalid input without state',()=>{
  const context={exports:{},require:name=>name==='knockout'?ko:name==='./session'?{session:{}}:{fluxApi:{}}};
  vm.runInNewContext(compile('ts/services/compliance-workspace.ts'),context);
  const parse=context.exports.parsePolicyImport;
  const single=parse(JSON.stringify({title:'  Source checks ',category:'AML',content:'  Verify origin of funds. '}));
  assert.equal(single.length,1);assert.equal(single[0].title(),'Source checks');assert.equal(single[0].content(),'Verify origin of funds.');
  const many=parse([{title:'KYC review',category:'KYC',content:'Collect evidence.'},{title:'Country rules',category:'COUNTRY_RULE',content:'Apply corridor rules.'}]);
  assert.equal(many.length,2);assert.notEqual(many[0].id,many[1].id);
  for(const value of ['{',{title:'',category:'AML',content:'Text'},{title:'Valid',category:'INVALID',content:'Text'},[{title:'Same',category:'AML',content:'Text'},{title:' same ',category:'AML',content:' text '}],null])assert.throws(()=>parse(value));
});
test('selecting the provided policy JSON appends all three policies to the visible draft list',async()=>{
  class Reader { readAsText(file){this.result=file.text;Promise.resolve().then(()=>this.onload());} }
  const {page}=workspace({},true,{FileReader:Reader});
  const input={files:[{name:'policy.json',type:'application/json',text:JSON.stringify([
    {title:'High-value transfer review1',category:'PAYMENT_REVIEW',content:'ABCD'},
    {title:'High-value transfer review2',category:'PAYMENT_REVIEW',content:'EFGH'},
    {title:'High-value transfer review3',category:'PAYMENT_REVIEW',content:'IJKL'}
  ])}],value:'selected'};
  page.loadPolicyJson(page,{target:input});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(input.value,'');
  assert.equal(JSON.stringify(page.policyDrafts().map(draft=>[draft.title(),draft.category(),draft.content()])),JSON.stringify([
    ['High-value transfer review1','PAYMENT_REVIEW','ABCD'],
    ['High-value transfer review2','PAYMENT_REVIEW','EFGH'],
    ['High-value transfer review3','PAYMENT_REVIEW','IJKL']
  ]));
  page.dispose();
});
test('batch policy creation preserves failed drafts in display order with server errors',async()=>{
  const {page,calls}=workspace({createPolicy:async body=>{if(body.title==='Rejected')throw Error('Duplicate policy title');return {...policy,title:body.title};}});
  page.addManualPolicyDraft();page.draftEditor().title('Accepted');page.draftEditor().category('AML');page.draftEditor().content('First policy');page.savePolicyDraft();
  page.addManualPolicyDraft();page.draftEditor().title('Rejected');page.draftEditor().category('KYC');page.draftEditor().content('Second policy');page.savePolicyDraft();
  const [first,second]=page.policyDrafts();
  await page.createPolicyDrafts();
  assert.deepEqual(calls.filter(c=>c[0]==='createPolicy').map(c=>c[1].title),['Accepted','Rejected']);
  assert.equal(page.policyDrafts().length,1);assert.equal(page.policyDrafts()[0].id,second.id);assert.match(second.error(),/Duplicate policy title/);page.dispose();
});
test('publishing imported policy drafts clears the draft list only after every policy is created',async()=>{
  const {page,calls}=workspace({createPolicy:async body=>({...policy,title:body.title}),policies:async()=>[policy]});
  page.addManualPolicyDraft();
  const draft=page.draftEditor();draft.title('Imported policy');draft.category('AML');draft.content('Unique imported policy text.');page.savePolicyDraft();
  await page.createPolicyDrafts();
  assert.equal(calls.filter(call=>call[0]==='createPolicy').length,1);
  assert.equal(page.policyDrafts().length,0);
  assert.match(page.notice(),/added/i);page.dispose();
});
test('saving a policy edit clears stale chunks when the edit choice is enabled',async()=>{
  const updated={...policy,title:'Updated policy',content:'Updated content',chunks:[{id:'stale'}]};
  const {page,calls}=workspace({updatePolicy:async()=>updated,policies:async()=>[updated]});
  page.policy(policy);page.chunks([{id:'old',policyDocumentId:id,chunkNumber:1,content:'old',createdAt:policy.createdAt}]);
  page.openPolicyEdit(policy);page.policyEdit().title(' Updated policy ');page.policyEdit().content(' Updated content ');
  await page.savePolicyEdit();
  assert.equal(JSON.stringify(calls.find(c=>c[0]==='updatePolicy')),JSON.stringify(['updatePolicy',id,{title:'Updated policy',category:'PAYMENT_REVIEW',content:'Updated content',clearExistingChunks:true}]));
  assert.equal(page.policy().title,'Updated policy');assert.equal(page.chunks().length,0);assert.equal(page.policyEdit(),undefined);assert.equal(page.policies()[0].title,'Updated policy');assert.equal(page.notice(),'Policy updated. Rebuild its index before asking Copilot.');page.dispose();
});
test('saving a policy edit retains chunks when the edit choice is disabled',async()=>{
  const updated={...policy,title:'Updated policy',content:'Updated content'};
  const oldChunk={id:'old',policyDocumentId:id,chunkNumber:1,content:'old',createdAt:policy.createdAt};
  const {page,calls}=workspace({updatePolicy:async()=>updated,policies:async()=>[updated]});
  page.policy(policy);page.chunks([oldChunk]);page.openPolicyEdit(policy);page.policyEditClearChunks(false);
  await page.savePolicyEdit();
  assert.equal(JSON.stringify(calls.find(c=>c[0]==='updatePolicy')),JSON.stringify(['updatePolicy',id,{title:'QA policy',category:'PAYMENT_REVIEW',content:'Review source of funds.',clearExistingChunks:false}]));
  assert.deepEqual(page.chunks(),[oldChunk]);assert.match(page.notice(),/retained/);page.dispose();
});
test('a failed policy edit retains the selected chunk retention choice',async()=>{
  const {page}=workspace({updatePolicy:async()=>{throw Error('Policy update rejected');}});
  page.openPolicyEdit(policy);page.policyEditClearChunks(false);
  await page.savePolicyEdit();
  assert.equal(page.policyEditClearChunks(),false);assert.match(page.policyEdit().error(),/rejected/);page.dispose();
});
test('non-admin cannot load or mutate global compliance data',async()=>{const {page,calls}=workspace({},false);await page.loadCases();await page.savePolicy();await page.ask();assert.equal(calls.length,0);assert.match(page.error(),/administrator/);page.dispose();});
test('policy create, detail, chunks and index flow has no bound policy delete action',async()=>{
  const {page,calls}=workspace();page.title(' QA policy ');page.content(' Policy text ');await page.savePolicy();assert.equal(page.policy().id,id);assert.equal(page.policyForm(),false);
  await page.openPolicy({id});page.chunkContent(' Extra passage ');await page.addChunk();assert.equal(page.chunkContent(),'');
  page.askConfirmation('index');assert.equal(calls.filter(c=>c[0]==='indexPolicy').length,0);await page.confirm();assert.match(page.notice(),/2 chunks/);
  assert.equal(calls.filter(c=>c[0]==='deletePolicy').length,0);
  assert.doesNotMatch(read('ts/views/admin-policies.html'),/askConfirmation\('delete-policy'\)|deletePolicy|Delete policy/i);
  page.dispose();
});
test('policy deletion endpoint wrapper issues an authorized DELETE',async()=>{
  const calls=[];
  const context={exports:{},window:{},sessionStorage:{getItem:()=> 'test-token'},crypto:{randomUUID:()=>id},fetch:async(url,options)=>{calls.push([url,options]);return {ok:true,status:204,json:async()=>({})};}};
  vm.runInNewContext(compile('ts/services/flux-api.ts'),context);
  await context.exports.fluxApi.deletePolicy(id);
  assert.deepEqual(calls.map(([url,options])=>[options.method,url]),[['DELETE',`/api/policies/${id}`]]);
  assert.equal(calls[0][1].headers.Authorization,'Bearer test-token');
});
test('policy validation retains user input and does not call API',async()=>{const {page,calls}=workspace();page.title('x'.repeat(201));page.content('text');await page.savePolicy();assert.equal(calls.length,0);assert.equal(page.title().length,201);assert.match(page.error(),/200/);page.dispose();});
test('index outage preserves selected document and does not claim success',async()=>{const {page}=workspace({indexPolicy:async()=>{throw Error('Embedding service unavailable');}});page.policy(policy);page.askConfirmation('index');await page.confirm();assert.match(page.error(),/unavailable/);assert.equal(page.confirmation(),'index');assert.equal(page.notice(),'');assert.equal(page.busy(),false);page.dispose();});
test('manual case sends trimmed reasons as an array and does not promise payment update',async()=>{const {page,calls}=workspace();page.paymentId(id);page.reasons(' First reason\n\n Second reason ');page.suggestedAction(' Review source ');await page.saveCase();const body=calls.find(c=>c[0]==='createComplianceCase')[1];assert.equal(JSON.stringify(body.riskReasons),'["First reason","Second reason"]');assert.match(page.notice(),/do not change/);assert.equal(page.selectedCase().id,id);page.dispose();});
test('case validation rejects invalid UUID and overlong suggested action',async()=>{const {page,calls}=workspace();page.paymentId('not-a-uuid');page.reasons('Reason');page.suggestedAction('Action');await page.saveCase();assert.equal(calls.length,0);page.paymentId(id);page.suggestedAction('x'.repeat(401));await page.saveCase();assert.equal(calls.length,0);page.dispose();});
test('payment-linked approval and rejection use server-authored identity only',async()=>{for(const action of ['approve','reject']){const {page,calls}=workspace();page.selectedCase({...manual,reviewReference:'review-1'});page.decisionReason(' Verified supporting documents ');page.askConfirmation(action);await page.confirm();const call=calls.find(c=>c[0]==='decideComplianceCase');assert.equal(JSON.stringify(call),JSON.stringify(['decideComplianceCase',id,action,'Verified supporting documents']));assert.match(page.notice(),/linked payment has been updated/);assert.equal(page.confirmation(),'');page.dispose();}});
test('expired review leaves case open and displays server reason',async()=>{const {page}=workspace({decideComplianceCase:async()=>{throw Error('The selected quote has expired during review.');}});page.selectedCase({...manual,reviewReference:'review-1'});page.askConfirmation('approve');await page.confirm();assert.equal(page.selectedCase().status,'OPEN');assert.match(page.error(),/expired/);assert.equal(page.notice(),'');page.dispose();});
test('only open manual cases can be deleted',async()=>{for(const value of [{...manual,reviewReference:'auto-review'},{...manual,status:'APPROVED'}]){const {page,calls}=workspace();page.selectedCase(value);page.askConfirmation('delete-case');await page.confirm();assert.equal(calls.length,0);assert.match(page.error(),/Only open manual/);page.dispose();}const {page,calls}=workspace();page.selectedCase(manual);page.askConfirmation('delete-case');await page.confirm();assert.equal(page.selectedCase(),undefined);assert.equal(calls[0][0],'deleteComplianceCase');page.dispose();});
test('Copilot handles citations, optional UUID, no-source fallback and provider errors',async()=>{const {page,calls}=workspace({askCopilot:async()=>({answer:'Review is required.',sources:[{policyDocumentId:id,title:'Policy',chunkNumber:1,excerpt:'Review clause'}]})});page.question(' What requires review? ');await page.ask();assert.equal(calls[0][2],undefined);assert.equal(page.answer().sources[0].policyDocumentId,id);page.copilotPaymentId('invalid');await page.ask();assert.match(page.error(),/UUID/);assert.equal(calls.length,1);page.dispose();const second=workspace({askCopilot:async()=>{throw Error('Compliance Copilot is temporarily unavailable.');}});second.page.question('Question');await second.page.ask();assert.equal(second.page.answer(),undefined);assert.match(second.page.error(),/unavailable/);second.page.dispose();});
test('rapid duplicate actions are blocked and logout clears late responses',async()=>{let finish;const {page,calls,session}=workspace({policies:()=>new Promise(resolve=>finish=resolve)});const first=page.loadPolicies();await page.loadPolicies();assert.equal(calls.length,1);session.user(null);finish([policy]);await first;assert.equal(page.policies().length,0);page.dispose();});
test('admin templates render untrusted policy and AI text with text bindings, never HTML',()=>{const files=['ts/views/admin.html','ts/views/admin-policies.html','ts/views/admin-compliance.html','ts/views/admin-copilot.html'];for(const file of files)assert.ok(!/data-bind="[^"]*\bhtml\s*:/.test(read(file)),file);const policies=read('ts/views/admin-policies.html');for(const action of ['savePolicy','addChunk','confirm'])assert.ok(policies.includes(action),action);const compliance=read('ts/views/admin-compliance.html');for(const action of ['prepareDecision','confirm'])assert.ok(compliance.includes(action),action);const copilot=read('ts/views/admin-copilot.html');assert.ok(copilot.includes('askCited'));assert.ok(policies.includes('role="alertdialog"'));assert.ok(compliance.includes('maxlength="500"'));assert.ok(policies.includes('maxlength="200"'));});
test('Copilot answers safely render only bold Markdown and keep source cards compact',()=>{
  const html=read('ts/views/admin-copilot.html'),source=read('ts/services/compliance-workspace.ts'),css=read('css/workspace.css');
  assert.match(html,/policyAnswer:answer\(\)\.answer/);
  assert.ok(!html.includes('<h3 data-bind="text:answeredQuestion"></h3>'));
  assert.match(source,/ko\.bindingHandlers\.policyAnswer/);
  assert.match(source,/document\.createTextNode/);
  assert.match(source,/document\.createElement\('strong'\)/);
  assert.match(css,/\.copilot-sources h4\s*\{[^}]*font-size:\s*15px;/);
  assert.match(css,/\.copilot-sources blockquote\s*\{[^}]*font-size:\s*13px;/);
});
test('policy library template provides draft creation and compact accessible detail dialogs',()=>{
  const html=read('ts/views/admin-policies.html');
  for(const binding of ['policyDrafts','loadPolicyJson','addManualPolicyDraft','removePolicyDraft','requestPolicyDraftPublish','confirmPolicyDraftPublish','openPolicyEdit','requestPolicyEditSave','confirmPolicyEditSave','policyChanges','policySavedView','visiblePolicies','changeSavedView'])assert.ok(html.includes(binding),`missing ${binding}`);
  assert.match(html,/accept="\.json,application\/json"/);
  assert.match(html,/data-bind="foreach:policyDrafts"/);
  assert.match(html,/data-bind="foreach:visiblePolicies"/);
  assert.ok(html.includes('previousPolicyPage'));
  assert.ok(html.includes('nextPolicyPage'));
  assert.match(html,/role="dialog"\s+aria-modal="true"\s+aria-labelledby="policy-edit-title"/);
  assert.match(html,/role="alertdialog"\s+aria-modal="true"\s+aria-labelledby="policy-changes-title"/);
  assert.match(html,/data-bind="adminDialog:true"/);
  assert.match(html,/data-bind="text:content"/);
  assert.match(html,/type="checkbox"\s+data-bind="checked:policyEditClearChunks,disable:busy"/);
  assert.match(html,/Retaining existing chunks can leave Copilot using older material\./);
});
test('policy import picker accepts a batch of JSON files and appends their parsed drafts',()=>{
  const html=read('ts/views/admin-policies.html');
  const source=read('ts/services/compliance-workspace.ts');
  assert.match(html,/type="file"\s+multiple\s+accept="\.json,application\/json"/);
  assert.match(source,/Array\.from\(input\.files\?\?\[\]\)/);
  assert.match(source,/Promise\.allSettled/);
});
test('policy drafts use one compact table and policy editing provides direct advanced settings',()=>{
  const html=read('ts/views/admin-policies.html');
  assert.match(html,/class="table-scroll policy-draft-table"[\s\S]*foreach:policyDrafts/);
  assert.match(html,/openAdvancedFromEdit/);
  assert.match(html,/class="modal-back"[\s\S]*← Policy/);
  assert.match(html,/class="manual-chunk-form"/);
});
test('policy-draft preview truncates before the fixed action column',()=>{
  const css=read('css/workspace.css');
  assert.match(css,/\.policy-draft-table td\.draft-preview\s*\{[^}]*overflow:\s*hidden;[^}]*white-space:\s*nowrap;[^}]*text-overflow:\s*ellipsis;/s);
  assert.match(css,/\.policy-draft-table td:last-child\s*\{\s*width:\s*118px;/);
});
test('opening a compliance case uses a confirmation dialog rather than an inline panel below the list',()=>{
  const html=read('ts/views/admin-compliance.html'),css=read('css/workspace.css');
  assert.match(html,/<!-- ko if:confirmation -->\s*<div[^>]*class="admin-confirmation[^"]*"[^>]*role="alertdialog"[^>]*aria-modal="true"[^>]*aria-labelledby="compliance-confirm-title"/);
  assert.match(html,/aria-label="Cancel action"/);
  assert.match(html,/data-bind="text:decisionReason"/);
  assert.match(css,/\.compliance-case-dialog\s*\{[^}]*overflow:\s*hidden;[^}]*padding:\s*0;/);
  assert.match(css,/\.compliance-case-scroll\s*\{[^}]*overflow-y:\s*auto;[^}]*border-radius:\s*inherit;/);
});
test('focused compliance template shows response-backed case context without delete actions',()=>{
  const html=read('ts/views/admin-compliance.html');
  for(const field of ['reviewReference','reviewExpiresAt','requoteRequired','riskReasons','suggestedAction','decisionReason'])assert.ok(html.includes(field),field);
  assert.doesNotMatch(read('ts/views/admin-compliance.html'), /delete-case|Delete manual case/);
  assert.match(html,/navigateToCopilot/);
});
test('case-to-Copilot handoff includes the risk, reasons, and suggested action',()=>{
  const source=read('ts/services/admin-console.ts');
  assert.match(source,/Case context:/);
  assert.match(source,/Risk level:/);
  assert.match(source,/riskReasons/);
  assert.match(source,/Suggested action:/);
});
test('policy chunk maintenance keeps editing manual-only while allowing any chunk to be deleted',async()=>{
  const calls=[];
  const context={exports:{},window:{},sessionStorage:{getItem:()=> 'test-token'},crypto:{randomUUID:()=>id},fetch:async(url,options)=>{calls.push([url,options]);return {ok:true,status:options.method==='DELETE'?204:200,json:async()=>({data:{id,manual:true}})};}};
  vm.runInNewContext(compile('ts/services/flux-api.ts'),context);
  await context.exports.fluxApi.updatePolicyChunk(id,'chunk-1','Corrected guidance');
  await context.exports.fluxApi.deletePolicyChunk(id,'chunk-1');
  assert.deepEqual(calls.map(([url,options])=>[options.method,url]),[
    ['PUT',`/api/policies/${id}/chunks/chunk-1`],
    ['DELETE',`/api/policies/${id}/chunks/chunk-1`]
  ]);
  assert.deepEqual(JSON.parse(calls[0][1].body),{content:'Corrected guidance'});
  const html=read('ts/views/admin-policies.html');
  assert.match(html,/<!-- ko if:manual --><button[^>]*openChunkEdit/);
  assert.ok(html.includes('requestDeleteChunk'));
  assert.ok(!/requestDeleteChunk[^>]*visible:manual/.test(html));
  assert.ok(html.includes('requestDeleteGuidance'));
});

function paymentPage(api){
  const context={exports:{},require:name=>name==='knockout'?ko:name==='./flux-api'?{fluxApi:api}:{session:{user:ko.observable({role:'USER'})},navigate:()=>{}},window:{setInterval:()=>1,addEventListener:()=>{},removeEventListener:()=>{}},sessionStorage:{getItem:()=>null},clearInterval:()=>{},setInterval:()=>1,document:{visibilityState:'visible'}};
  vm.runInNewContext(compile('ts/services/page.ts'),context);const page=new context.exports.Page('payments-new');
  page.paymentId(id);page.payment({id,status:'QUOTED'});page.quotes([{id:'quote'}]);page.selectedQuote({id:'quote'});page.quoteExpires(new Date(Date.now()+600000).toISOString());page.confirmAction('confirm');return page;
}
test('202 review confirmation enters review-aware completion, not payout execution',async()=>{const page=paymentPage({confirm:async()=>({id,status:'UNDER_REVIEW'})});await page.executeAction();assert.equal(page.step(),3);assert.equal(page.payment().status,'UNDER_REVIEW');assert.equal(page.confirmAction(),'');page.disconnected();});
test('persisted compliance rejection is displayed even when confirm returns an error',async()=>{const page=paymentPage({confirm:async()=>{throw Error('Payment blocked');},payment:async()=>({id,status:'REJECTED'})});await page.executeAction();assert.equal(page.payment().status,'REJECTED');assert.equal(page.step(),3);assert.equal(page.error(),'');page.disconnected();});
test('expired quote confirmation remains an error, never a successful completion',async()=>{const page=paymentPage({confirm:async()=>{throw Error('Quote expired');},payment:async()=>({id,status:'QUOTED'})});await page.executeAction();assert.match(page.error(),/expired/);assert.notEqual(page.step(),3);page.disconnected();});
test('proxy allows longer AI requests without extending ordinary payment timeouts',async()=>{
  const timeouts=[];const transport={request:()=>({setTimeout:ms=>timeouts.push(ms),on:()=>{},destroy:()=>{}})};
  const context={module:{exports:{}},require:()=>transport,URL,process:{env:{}}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../scripts/hooks/before_serve.js'),'utf8'),context);
  const config=await context.module.exports({});
  for(const url of ['/api/copilot/ask','/api/policies/'+id+'/index','/api/payments'])config.preMiddleware[0]({url,headers:{},method:'POST',pipe:()=>{}},{},()=>{});
  assert.deepEqual(timeouts,[120000,120000,30000]);
});
