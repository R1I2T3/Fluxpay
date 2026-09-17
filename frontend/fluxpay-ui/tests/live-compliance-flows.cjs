// Opt-in LOCAL integration check through the actual frontend API client and proxy.
// Creates one temporary policy and one open manual case; finally removes only those IDs.
// Existing cases/payments/policies are never changed. No credentials are printed.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript'),assert=require('node:assert/strict');
if(!process.argv.includes('--fixtures')){console.log('Use --fixtures to authorize temporary local QA policy/case creation and cleanup.');process.exit(0);}
const settings={...process.env},env=path.resolve(__dirname,'../../../.env');
if(fs.existsSync(env))for(const line of fs.readFileSync(env,'utf8').split(/\r?\n/)){const m=line.match(/^([A-Z0-9_]+)=(.*)$/);if(m&&settings[m[1]]===undefined)settings[m[1]]=m[2];}
let token='',policyId,caseId;
const context={exports:{},window:{FLUXPAY_API_URL:'http://127.0.0.1:8000',dispatchEvent:()=>{}},Event,crypto:require('node:crypto').webcrypto,sessionStorage:{getItem:()=>token,removeItem:()=>{token='';}},fetch:(url,options)=>fetch(url,{...options,signal:AbortSignal.timeout(125000)})};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/ts/services/flux-api.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);
const api=context.exports.fluxApi;
(async()=>{
 if(!settings.SEED_ADMIN_PASSWORD)throw Error('SEED_ADMIN_PASSWORD is required; no credentials guessed.');
 const auth=await api.login({email:settings.SEED_ADMIN_EMAIL||'admin@local.fluxpay',password:settings.SEED_ADMIN_PASSWORD});token=auth.token;assert.equal(auth.user.role,'ADMIN');
 console.log('PASS: admin login through frontend proxy');
 if(process.argv.includes('--index-seeded-policies')){
  const titles=['Customer Identity Verification Policy','New Recipient Review Policy','High Value Payment Policy','Payment Hold and Recovery Policy','Country Transfer Rules'];
  const seeded=(await api.policies()).filter(p=>/^0+5e0[1-5]$/i.test(p.id.replace(/-/g,''))&&titles.includes(p.title));
  assert.equal(seeded.length,5,'Expected exactly the five known V603 seed policies; refusing to index other documents.');
  for(const policy of seeded){const result=await api.indexPolicy(policy.id);console.log('INDEXED SEEDED POLICY: '+policy.title+' ('+result.chunkCount+' chunks)');}
 }
 try{
  const title='Disposable QA policy '+require('node:crypto').randomUUID();
  const content='For the FluxPay QA review procedure, verify supporting source-of-funds evidence before approving a payment. Record the review decision and its reason. Manual cases record an assessment but do not change payment state.';
  const policy=await api.createPolicy({title,category:'PAYMENT_REVIEW',content});policyId=policy.id;
  assert.ok((await api.policies()).some(p=>p.id===policyId));assert.equal((await api.policy(policyId)).title,title);
  await api.addPolicyChunk(policyId,'QA manual chunk: retain a clear review note.');assert.equal((await api.policyChunks(policyId)).length,1);
  console.log('PASS: policy create/list/detail/add chunk/list chunks');
  await assert.rejects(()=>api.createPolicy({title,category:'PAYMENT_REVIEW',content}));console.log('PASS: duplicate policy is rejected');
  const cases=await api.complianceCases('ALL');
  if(cases.length){const created=await api.createComplianceCase({paymentId:cases[0].paymentId,risk:'LOW',riskReasons:['Disposable frontend integration check'],suggestedAction:'QA only: delete this open manual case after verification.'});caseId=created.id;assert.equal((await api.complianceCase(caseId)).reviewReference,null);assert.ok((await api.complianceCases('OPEN')).some(c=>c.id===caseId));console.log('PASS: manual case create/list/status filter/detail; no payment decision made');}
  if(process.argv.includes('--ai')){
   const indexed=await api.indexPolicy(policyId);assert.ok(indexed.chunkCount>0);console.log('PASS: policy index published ('+indexed.chunkCount+' chunks)');
   const answer=await api.askCopilot('What must be verified and recorded in the FluxPay QA review procedure?');assert.ok(answer.answer);assert.ok(answer.sources.length>0);console.log('PASS: Copilot returned an answer with '+answer.sources.length+' cited sources');
  }
 }finally{
  if(caseId){await api.deleteComplianceCase(caseId);console.log('CLEANUP: temporary open manual case deleted (204 handled)');}
  if(policyId){await api.deletePolicy(policyId);console.log('CLEANUP: temporary policy/chunks/index deleted (204 handled)');}
 }
})().catch(error=>{console.error('FAIL: '+error.message);process.exitCode=1;});
