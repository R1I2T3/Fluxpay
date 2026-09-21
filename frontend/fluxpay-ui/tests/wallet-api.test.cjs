const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript');
const source=ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/ts/services/flux-api.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
function api(fetch){const context={exports:{},window:{dispatchEvent(){}},sessionStorage:{getItem:()=> 'test',removeItem(){}},crypto:{randomUUID:()=> 'idempotency-test'},TextDecoder,DOMException,Event,fetch,FormData,Blob};vm.runInNewContext(source,context);return context.exports.fluxApi;}
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
