const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript');
const source=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/ts/services/flux-api.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
function api(fetch,events=[]){const context={exports:{},window:{dispatchEvent:event=>events.push(event)},sessionStorage:{getItem:()=> 'test',removeItem(){}},crypto:{randomUUID:()=> 'idempotency-test'},CustomEvent:class{constructor(type,{detail}){this.type=type;this.detail=detail;}},TextDecoder,DOMException,Event,fetch,FormData,Blob};vm.runInNewContext(source,context);return context.exports.fluxApi;}

test('legacy and current quote responses submit the exact route code and emit success only after completion',async()=>{
 for(const method of ['quotes','getQuotes'])for(const quote of [{id:'q1',route:'BANK_TRANSFER'},{id:'q1',routeCode:'BANK_TRANSFER'},{id:'q1',routeCode:' BANK_TRANSFER ',route:'OLDER_CODE'}]){
  const calls=[],events=[];const client=api(async(url,options)=>{calls.push({url,...options});return {ok:true,status:200,json:async()=>({data:url.endsWith('/quotes')?{quotes:[quote],recommendedQuoteId:'q1',expiresAt:'unchanged'}:{status:'COMPLETED',sourceAmount:'100',sourceCurrency:'USD'}})};},events);
  const result=await client[method]('p1');assert.equal(result.quotes[0].routeCode,'BANK_TRANSFER');assert.equal(result.recommendedQuoteId,'q1');assert.equal(result.expiresAt,'unchanged');assert.equal(events.length,0);
  await client.payout('p1',result.quotes[0].routeCode);assert.deepEqual(JSON.parse(calls[1].body),{routeCode:'BANK_TRANSFER'});
  assert.equal(events.length,1);assert.equal(events[0].type,'fluxpay:transaction-success');assert.equal(events[0].detail.title,'Money sent');
 }
});
test('blank payout or switch routes are rejected before any network call',async()=>{
 let calls=0;const client=api(async()=>{calls++;throw Error('Unexpected request');});
 for(const value of [undefined,null,'','  ']){await assert.rejects(client.payout('p1',value),/Refresh the delivery options/);await assert.rejects(client.switchRoute('p1',value,'q1'),/Refresh the delivery options/);}
 assert.equal(calls,0);
});
test('missing route codes are not invented from route IDs or names',async()=>{
 const client=api(async()=>({ok:true,status:200,json:async()=>({data:{quotes:[{id:'q1',routeId:'catalogue-uuid',routeName:'Bank transfer'}]}})}));
 const result=await client.getQuotes('p1');assert.equal(result.quotes[0].routeCode,'');
});
test('bank list, link, top-up, withdrawal and wallet transfer use the exact contracts',async()=>{
 const calls=[],client=api(async(url,options)=>{calls.push({url,...options});return {ok:true,status:200,json:async()=>({data:[]})};});
 await client.bankAccounts();await client.linkBank({bankName:'Example Bank',accountLast4:'1234',currency:'USD'});await client.bankTopup('bank-id',{amount:'10',note:'Test'});await client.withdraw({bankAccountId:'bank-id',currency:'USD',amount:'5',note:''});await client.walletTransfer({toEmail:'qa@example.test',fromCurrency:'USD',toCurrency:'EUR',amount:'10',amountMode:'SOURCE',note:''});
 assert.deepEqual(calls.map(c=>[c.method,c.url]),[['GET','/api/bank-accounts'],['POST','/api/bank-accounts/link'],['POST','/api/bank-accounts/bank-id/topup'],['POST','/api/wallets/withdraw'],['POST','/api/wallets/transfer']]);for(const c of calls.slice(1)){assert.equal(c.headers['Idempotency-Key'],'idempotency-test');assert.equal(c.headers.Authorization,'Bearer test');}assert.deepEqual(JSON.parse(calls[2].body),{amount:'10',note:'Test'});
});
test('stream parser handles fragmented UTF-8 and SSE frames, then stops on done',async()=>{
 const encoder=new TextEncoder(),data=encoder.encode('data: {"delta":"Hello €"}\r\n\r\ndata: {"delta":" world"}\n\nevent: done\ndata: {"done":true}\n\n');let offset=0,cancelled=false;const client=api(async()=>({ok:true,status:200,body:{getReader:()=>({read:async()=>offset<data.length?{value:data.slice(offset,offset+=3),done:false}:{done:true},cancel:async()=>{cancelled=true;},releaseLock(){}})}}));let answer='';await client.streamCopilot('Question',undefined,delta=>answer+=delta,new AbortController().signal);assert.equal(answer,'Hello € world');assert.equal(cancelled,true);
});
test('a truncated stream is reported instead of presenting an incomplete answer as complete',async()=>{
 let read=false;const client=api(async()=>({ok:true,status:200,body:{getReader:()=>({read:async()=>read?{done:true}:(read=true,{value:new TextEncoder().encode('data: {"delta":"Partial"}\n\n'),done:false}),cancel:async()=>{},releaseLock(){}})}}));let text='';await assert.rejects(client.streamCopilot('Question',undefined,d=>text+=d,new AbortController().signal),/interrupted/);assert.equal(text,'Partial');
});
test('failed funding uses customer wording without exposing the endpoint implementation name',async()=>{
 const client=api(async()=>({ok:false,status:404,json:async()=>({message:'Demo funding is disabled'})}));await assert.rejects(client.fund({currency:'USD',amount:'1'}),/Adding money is disabled/);
});
test('KYC uploads real multipart bytes without overriding the browser boundary',async()=>{
 let captured;const client=api(async(url,options)=>{captured={url,...options};return {ok:true,status:201,json:async()=>({data:{status:'PENDING'}})};});
 await client.uploadKyc('PASSPORT','TEST123',[new Blob(['test document'],{type:'application/pdf'})]);
 assert.equal(captured.url,'/api/kyc/applications');assert.ok(captured.body instanceof FormData);assert.equal(captured.body.get('docType'),'PASSPORT');assert.equal(await captured.body.get('files').text(),'test document');assert.equal(captured.headers['Content-Type'],undefined);assert.equal(captured.headers.Authorization,'Bearer test');
});
test('KYC preview is authenticated, not cached, and rejects non-document responses',async()=>{
 let captured;const client=api(async(url,options)=>{captured={url,...options};return {ok:true,status:200,blob:async()=>new Blob(['x'],{type:'text/html'})};});
 await assert.rejects(client.kycDocument('doc-id'),/cannot be previewed/);assert.equal(captured.url,'/api/kyc/documents/doc-id/content');assert.equal(captured.cache,'no-store');assert.equal(captured.headers.Authorization,'Bearer test');
});
