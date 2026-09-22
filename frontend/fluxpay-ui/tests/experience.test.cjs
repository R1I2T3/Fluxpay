const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript'),ko=require('knockout');
const root=path.join(__dirname,'../src/ts');
function fixture(name,overrides={},initialToken='',params={}){
  const calls=[],navigation=[],events=new Map(),cache=new Map();let token=initialToken;
  const session={user:ko.observable({role:'CUSTOMER',kycStatus:'NONE'}),restore:async()=>{},clear(){token='';this.user(null);}};
  const api=new Proxy(overrides,{get:(obj,key)=>async(...args)=>{calls.push([key,...args]);if(obj[key])return obj[key](...args);if(key==='wallets'||key==='recipients')return [];if(key==='payments')return {items:[],total:0};if(key==='ledger')return {entries:[],totalElements:0};throw Error('Unexpected API '+key);}});
  const window={setInterval:()=>1,addEventListener:(n,f)=>events.set(n,f),removeEventListener:n=>events.delete(n),dispatchEvent:e=>events.get(e.type)?.(e)};
  const storage={getItem:()=>token,setItem:(_,v)=>{token=v;},removeItem:()=>{token='';}};
  function load(file){
    if(cache.has(file))return cache.get(file);
    const module={exports:{}};
    const context={module,exports:module.exports,require:dep=>{
      if(dep==='knockout')return ko;
      if(dep.endsWith('/flux-api'))return {fluxApi:api};
      if(dep.endsWith('/session'))return {session,navigate:(...args)=>navigation.push(args)};
      if(dep.endsWith('/experience-dialog'))return {};
      if(dep.endsWith('/recipient-actions'))return {deleteRecipient:async id=>{calls.push(['deleteRecipient',id]);if(overrides.deleteRecipient)await overrides.deleteRecipient(id);}};
      return load(path.resolve(path.dirname(file),dep+'.ts'));
    },window,sessionStorage:storage,clearInterval(){},document:{visibilityState:'visible'},Event,Intl,Date,URLSearchParams,URL,AbortController};
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);
    cache.set(file,module.exports);return module.exports;
  }
  const Model=load(path.join(root,'viewModels',name+'.ts'));const page=new Model(params);
  return {page,calls,session,navigation,storage,Model};
}
function transferData(page){page.wallets([{walletId:'w1',currency:'USD'}]);page.walletId('w1');page.recipients([{id:'r1',currency:'INR',status:'ACTIVE'}]);page.recipientId('r1');page.amount('125.00');}

test('Others requires a nonblank reason before continuing or saving, with no API request on invalid input',async()=>{
  for(const action of ['createDraft','saveDraft'])for(const reason of ['', '   ', 'a'.repeat(251)]){
    const f=fixture('payments-new');transferData(f.page);f.page.purpose('OTHERS');f.page.purposeReason(reason);
    await f.page[action]();assert.match(f.page.error(),/reason/i);assert.equal(f.page.step(),1);assert.equal(f.calls.length,0);
  }
});
test('Others saves the trimmed reason, shows it on the receipt, and creates a new draft when changed',async()=>{
  const f=fixture('payments-new',{draft:async body=>({...body,id:'p'+Math.random(),status:'DRAFT'})});transferData(f.page);f.page.purpose('OTHERS');f.page.purposeReason('  Medical treatment  ');
  await f.page.saveDraft();assert.equal(f.calls[0][1].purposeReason,'Medical treatment');assert.equal(f.page.paymentReason(),'Medical treatment');
  f.page.backToDetails();f.page.purposeReason('Travel expenses');await f.page.saveDraft();assert.equal(f.calls.length,2);assert.equal(f.page.paymentReason(),'Travel expenses');
  f.page.backToDetails();f.page.purpose('FAMILY_SUPPORT');await f.page.saveDraft();assert.equal(f.calls.length,3);assert.ok(!('purposeReason' in f.calls[2][1]));
});
test('Skip for now opens dashboard without changing verification or making an API request',()=>{
  const f=fixture('kyc');assert.equal(f.page.canSkip(),true);f.page.skipForNow();assert.equal(f.navigation[0][0],'dashboard');assert.equal(f.session.user().kycStatus,'NONE');assert.equal(f.calls.length,0);
  for(const status of ['PENDING','VERIFIED','REJECTED']){f.page.kyc({status});assert.equal(f.page.canSkip(),false);}
});
test('save draft persists once, does not quote/confirm or navigate, and Back reuses unchanged draft',async()=>{
  const f=fixture('payments-new',{draft:async()=>({id:'p1',status:'DRAFT'}),quotes:async()=>({quotes:[{id:'q1'}],expiresAt:new Date(Date.now()+300000).toISOString()})});transferData(f.page);
  await f.page.saveDraft();assert.equal(f.page.step(),4);assert.equal(f.page.savedDraft(),true);assert.equal(f.navigation.length,0);assert.deepEqual(f.calls.map(c=>c[0]),['draft']);
  f.page.backToDetails();assert.equal(f.page.step(),1);assert.equal(f.page.amount(),'125.00');await f.page.createDraft();assert.equal(f.calls.filter(c=>c[0]==='draft').length,1);assert.equal(f.page.step(),2);assert.equal(f.page.savedDraft(),false);
});
test('quote countdown reacts to clock, never exceeds server expiry, and blocks expired confirmation',async()=>{
  const f=fixture('payments-new');f.page.quotes([{id:'q1'}]);f.page.selectedQuote({id:'q1'});const start=Date.now();f.page.quoteFetchedAt(start);f.page.quoteExpires(new Date(start+300000).toISOString());f.page.now(start);
  assert.equal(f.page.countdownLabel(),'5:00');f.page.now(start+61000);assert.equal(f.page.countdownLabel(),'3:59');f.page.quoteExpires(new Date(start+62000).toISOString());assert.equal(f.page.quoteCountdown(),1);
  f.page.now(start+63000);assert.equal(f.page.quoteValid(),false);await f.page.confirmQuote();assert.match(f.page.error(),/expired/);assert.equal(f.calls.length,0);
});
test('repeated click while draft request is pending cannot create duplicate writes',async()=>{
  let release;const f=fixture('payments-new',{draft:()=>new Promise(r=>{release=r;})});transferData(f.page);const pending=f.page.saveDraft();await f.page.saveDraft();assert.equal(f.calls.length,1);release({id:'p1',status:'DRAFT'});await pending;
});
test('recipient writes omit status; delete is confirmed and failures retain the recipient',async()=>{
  const f=fixture('recipients',{recipient:async()=>({}),deleteRecipient:async()=>{throw Error('Deletion unavailable');}});
  f.page.recipientName('Jamie');f.page.account('TEST123');f.page.bankName('Example Bank');f.page.country('in');await f.page.saveRecipient();const body=f.calls.find(c=>c[0]==='recipient')[1];assert.ok(!('status' in body));assert.equal(body.country,'IN');
  const recipient={id:'r1',name:'Jamie'};f.page.recipients([recipient]);f.page.askDelete(recipient);assert.ok(!f.calls.some(c=>c[0]==='deleteRecipient'));await f.page.deleteRecipient();assert.equal(f.page.recipients().length,1);assert.match(f.page.error(),/unavailable/);f.page.cancelDelete();assert.equal(f.page.deleting(),undefined);
});
test('KYC permits initial submission and rejected re-upload but blocks pending and verified',async()=>{
  const f=fixture('kyc',{uploadKyc:async()=>({status:'PENDING'})});assert.equal(f.page.canSubmit(),true);assert.equal(f.page.canResubmit(),false);
  for(const status of ['PENDING','VERIFIED']){f.page.kyc({status});assert.equal(f.page.canSubmit(),false);await f.page.submitKyc();assert.equal(f.calls.length,0);}
  f.page.kyc({status:'REJECTED'});assert.equal(f.page.canResubmit(),true);f.page.docNumber('TEST123');f.page.documents([{fileName:'identity.pdf',file:{name:'identity.pdf'}}]);await f.page.submitKyc();assert.equal(f.page.status(),'PENDING');assert.equal(f.page.documents().length,0);assert.equal(f.calls[0][0],'uploadKyc');
});
test('Activity combines all pages and legacy top-ups with Plan 3 types and inclusive date/status filters',async()=>{
  const payment={id:'payment-123',recipientId:'r1',createdAt:'2026-09-17T12:00:00',status:'PROCESSING'};
  const f=fixture('payments-list',{wallets:async()=>[{walletId:'w1'}],recipients:async()=>[{id:'r1',name:'Jamie'}],payments:async page=>({items:[{...payment,id:'payment-'+page,status:page?'UNDER_REVIEW':'PROCESSING'}],total:2}),ledger:async()=>({entries:[{entryId:'topup',entryType:'CREDIT',journalReference:'wallet:demo:123',createdAt:'2026-09-17T23:59:59',amount:'100',currency:'USD'},{entryId:'p2p',entry_type:'WALLET_TO_WALLET',createdAt:'2026-09-18T00:00:00',amount:'10',currency:'USD'}],totalElements:2})});
  f.storage.setItem('', 'token');await f.page.load();assert.equal(f.page.filteredActivity().length,4);assert.equal(f.calls.filter(c=>c[0]==='payments').length,2);
  f.page.typeFilter('WALLET_TOPUP');assert.equal(f.page.filteredActivity().length,1);f.page.fromDate('2026-09-17');f.page.toDate('2026-09-17');assert.equal(f.page.filteredActivity().length,1);
  f.page.typeFilter('WALLET_TO_WALLET');assert.equal(f.page.filteredActivity().length,0);f.page.clearFilters();f.page.filter('ON_HOLD');assert.equal(f.page.filteredActivity().length,2);f.page.search('Jamie');assert.equal(f.page.filteredActivity().length,2);assert.equal(f.Model.shortId('1234567890'),'12345678…');
});
test('wallet on-hold includes only PROCESSING; funding reloads its ledger without technical wording',async()=>{
  const f=fixture('wallets',{fund:async()=>({}),wallets:async()=>[{walletId:'w1',currency:'USD'}],ledger:async()=>({entries:[{entryId:'credit'}],totalElements:1})});f.page.payments([{status:'PROCESSING'},{status:'UNDER_REVIEW'},{status:'COMPLETED'}]);assert.equal(f.page.onHold().length,1);
  f.page.openAddMoney();await f.page.fund();assert.equal(f.page.addMoneyOpen(),false);assert.equal(f.page.ledger().length,1);assert.equal(f.page.ledgerWallet().walletId,'w1');assert.doesNotMatch(f.page.notice(),/demo/i);
});
test('Activity initial authenticated mount loads after subclass state is initialized',async()=>{
  const f=fixture('payments-list',{payments:async()=>({items:[{id:'p1',status:'DRAFT',createdAt:'2026-09-18T10:00:00Z'}],total:1})},'token');
  await new Promise(resolve=>setImmediate(resolve));assert.equal(f.page.payments().length,1);assert.equal(f.page.filteredActivity().length,1);
});
test('401 clears session and redirects home; 403 preserves token and session',async()=>{
  for(const status of [401,403]){
    const events=new Map(),navigation=[];let token='test-token';const storage={getItem:()=>token,removeItem:()=>{token='';}};
    const window={addEventListener:(n,f)=>events.set(n,f),dispatchEvent:e=>{navigation.push(e);events.get(e.type)?.(e);}};
    const apiContext={exports:{},window,sessionStorage:storage,Event,fetch:async()=>({status,ok:false,json:async()=>({message:'Denied'})})};
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root,'services/flux-api.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,apiContext);
    class CustomEvent extends Event{constructor(name,options){super(name);this.detail=options.detail;}}
    const sessionContext={exports:{},require:n=>n==='knockout'?ko:apiContext.exports,window,sessionStorage:storage,Event,CustomEvent};
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root,'services/session.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,sessionContext);
    sessionContext.exports.session.user({role:'CUSTOMER'});await assert.rejects(()=>apiContext.exports.fluxApi.me());
    assert.equal(Boolean(token),status===403);assert.equal(Boolean(sessionContext.exports.session.user()),status===403);assert.equal(navigation.some(e=>e.type==='fluxpay:navigate'&&e.detail.path==='home'),status===401);
  }
});
test('recipient DELETE consumes 204 without parsing JSON and reports an unavailable backend honestly',async()=>{
  const calls=[];let status=204;
  const context={exports:{},window:{dispatchEvent(){}},sessionStorage:{getItem:()=> 'qa-token'},Event,fetch:async(url,options)=>{calls.push([url,options]);return {ok:status===204,status,json:async()=>{if(status===204)throw Error('No body');return {};}};}};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root,'services/recipient-actions.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText,context);
  await context.exports.deleteRecipient('recipient-1');assert.equal(calls[0][0],'/api/recipients/recipient-1');assert.equal(calls[0][1].method,'DELETE');assert.equal(calls[0][1].headers.Authorization,'Bearer qa-token');
  status=405;await assert.rejects(()=>context.exports.deleteRecipient('recipient-1'),/has not been removed/);
});

function reviewedTransfer(page){
  transferData(page);page.payment({id:'p1',status:'QUOTED',selectedQuoteId:'q1'});page.paymentId('p1');
  const quote={id:'q1',routeCode:'BANK_TRANSFER'};page.quotes([quote]);page.selectedQuote(quote);
  page.quoteFetchedAt(Date.now());page.quoteExpires(new Date(Date.now()+300000).toISOString());page.now(Date.now());page.step(3);
}
test('one-page Send confirms then submits payout, shows receipt and never navigates',async()=>{
  const f=fixture('payments-new',{confirm:async()=>({id:'p1',status:'PROCESSING'}),payout:async()=>({status:'COMPLETED'}),payment:async()=>({id:'p1',status:'COMPLETED'}),timeline:async()=>[{eventType:'payment.completed'}]});
  reviewedTransfer(f.page);await f.page.sendPayment();assert.equal(f.page.error(),'');assert.equal(f.page.step(),4);assert.equal(f.page.receiptTitle(),'Money sent.');assert.equal(f.page.timeline().length,1);
  assert.deepEqual(f.calls.map(c=>c[0]),['confirm','payout','payment','timeline']);assert.equal(f.navigation.length,0);
  assert.equal(f.calls.find(c=>c[0]==='payout')[2],'BANK_TRANSFER');
  await f.page.submitPayout();assert.equal(f.calls.filter(c=>c[0]==='payout').length,1);
});
test('compliance review and rejection never submit payout; approval can be sent on the same page',async()=>{
  for(const status of ['UNDER_REVIEW','REJECTED']){
    let current=status;
    const f=fixture('payments-new',{confirm:async()=>({id:'p1',status}),payment:async()=>({id:'p1',status:current}),timeline:async()=>[],payout:async()=>{current='COMPLETED';return {status:current};}});
    reviewedTransfer(f.page);await f.page.sendPayment();assert.equal(f.page.step(),4);assert.equal(f.page.canSubmitPayout(),false);assert.ok(!f.calls.some(c=>c[0]==='payout'));
    if(status==='UNDER_REVIEW'){current='PROCESSING';await f.page.refreshTransfer();assert.equal(f.page.canSubmitPayout(),true);await f.page.submitPayout();assert.equal(f.page.payment().status,'COMPLETED');}
    assert.equal(f.navigation.length,0);
  }
});
test('an uncertain payout response refreshes state and cannot automatically dispatch twice',async()=>{
  const f=fixture('payments-new',{confirm:async()=>({id:'p1',status:'PROCESSING'}),payout:async()=>{throw Error('Connection interrupted');},payment:async()=>({id:'p1',status:'PROCESSING'}),timeline:async()=>[]});
  reviewedTransfer(f.page);await f.page.sendPayment();assert.equal(f.page.step(),4);assert.match(f.page.error(),/Connection/);assert.equal(f.page.canSubmitPayout(),false);
  await f.page.submitPayout();assert.equal(f.calls.filter(c=>c[0]==='payout').length,1);assert.equal(f.navigation.length,0);
});
test('expired Send confirmation and double clicks cannot dispatch financial requests twice',async()=>{
  let release;
  const f=fixture('payments-new',{confirm:()=>new Promise(r=>{release=r;}),payment:async()=>({id:'p1',status:'UNDER_REVIEW'}),timeline:async()=>[]});reviewedTransfer(f.page);
  f.page.now(Date.now()+400000);await f.page.sendPayment();assert.equal(f.calls.length,0);
  f.page.now(Date.now());const pending=f.page.sendPayment();await f.page.sendPayment();assert.equal(f.calls.length,1);release({id:'p1',status:'UNDER_REVIEW'});await pending;
});
test('adding a recipient inline keeps the amount and selects the new person without navigation',async()=>{
  const f=fixture('payments-new',{recipient:async body=>({...body,id:'r2',status:'ACTIVE'})});transferData(f.page);f.page.addRecipient();f.page.recipientName('Taylor Lee');f.page.bankName('Example Bank');f.page.account('TESTACCOUNT');f.page.country('us');f.page.recipientCurrency('USD');
  await f.page.saveRecipient();assert.equal(f.page.recipientId(),'r2');assert.equal(f.page.amount(),'125.00');assert.equal(f.page.recipientFormOpen(),false);assert.equal(f.navigation.length,0);assert.ok(!('status' in f.calls[0][1]));
  f.page.recipientSearch('taylor');assert.equal(f.page.matchingRecipients().length,1);
});
test('dashboard people shortcut preselects recipient after authenticated Send loads',async()=>{
  const f=fixture('payments-new',{wallets:async()=>[{walletId:'w1',currency:'USD'}],recipients:async()=>[{id:'r1',status:'ACTIVE'},{id:'r2',status:'ACTIVE'}]},'token',{params:{recipient:'r2'}});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(f.page.recipientId(),'r2');assert.equal(f.page.walletId(),'w1');
  const dashboard=fixture('dashboard');dashboard.page.payPerson({id:'r2'});assert.equal(dashboard.navigation[0][0],'payments-new');assert.equal(dashboard.navigation[0][1].recipient,'r2');
});
test('customer bottom navigation is unchanged and admin sidebar is guarded with visible labels',()=>{
  const css=fs.readFileSync(path.join(root,'../css/mobile-workspace.css'),'utf8'),html=fs.readFileSync(path.join(root,'../index.html'),'utf8');
  assert.match(css,/@media\(min-width:900px\)/);assert.match(css,/#main \{ max-width:none; width:100%; margin:0; padding:76px 0 0;/);
  assert.match(html,/class="bottom-nav"/);assert.match(html,/foreach:bottomNav/);
  assert.match(html,/visible:!isPublic\(\)&&!isAdminWorkspace\(\)/);
  assert.match(html,/<!-- ko if:isAdminWorkspace -->[\s\S]*class="admin-sidebar"/);
  assert.match(html,/foreach:adminNavGroups/);
  assert.match(html,/<span data-bind="text:label"><\/span>/);
  assert.match(html,/class="skip-link"/);
  const adminCss=fs.readFileSync(path.join(root,'../css/admin-console.css'),'utf8');
  assert.match(adminCss,/\.admin-environment\{[^}]*position:sticky/);
  assert.doesNotMatch(adminCss,/linear-gradient|radial-gradient/);
});

test('saving from final review confirms as Processing without submitting payout',async()=>{
  const f=fixture('payments-new',{draft:async()=>({id:'p1',status:'DRAFT'}),confirm:async()=>({id:'p1',status:'PROCESSING',selectedQuoteId:'q1'}),quotes:async()=>({quotes:[{id:'q1',routeCode:'BANK_TRANSFER'}],expiresAt:new Date(Date.now()+300000).toISOString()})});transferData(f.page);
  await f.page.createDraft();f.page.chooseQuote(f.page.quotes()[0]);assert.equal(f.page.step(),3);await f.page.saveDraft();assert.equal(f.page.savedDraft(),false);assert.equal(f.page.payment().status,'PROCESSING');assert.equal(f.page.receiptStatus(),'Processing');assert.equal(f.page.step(),4);assert.equal(f.calls.filter(c=>c[0]==='draft').length,1);assert.equal(f.calls.filter(c=>c[0]==='confirm').length,1);assert.ok(!f.calls.some(c=>c[0]==='payout'));
  const view=fs.readFileSync(path.join(root,'views/payments-new.html'),'utf8');assert.match(view,/Send money ↗/);assert.match(view,/>Save draft</);assert.doesNotMatch(view,/id="confirm-title"/);
});

test('dashboard recent activity includes top-up, wallet transfer, exchange and recipient payment',async()=>{
  const f=fixture('dashboard',{wallets:async()=>[{walletId:'w1',currency:'USD'}],payments:async()=>({items:[{id:'p1',status:'COMPLETED',createdAt:'2026-09-20T10:00:00Z',recipientId:'r1'}],total:1}),ledger:async()=>({entries:[{entryId:'topup',entryType:'CREDIT',journalReference:'wallet:demo:test',amount:'500',createdAt:'2026-09-20T12:00:00Z'},{entryId:'transfer',entry_type:'WALLET_TO_WALLET',entryType:'DEBIT',amount:'25',created_at:'2026-09-20T11:00:00Z'},{entryId:'exchange',entryType:'DEBIT',journalReference:'wallet:fx:test',amount:'50',createdAt:'2026-09-20T09:00:00Z'}],totalElements:3})},'token');
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(Array.from(f.page.recentActivity(),r=>r.id),['topup','transfer','p1','exchange']);
  assert.equal(f.page.movementLabel(f.page.entries()[0]),'Money added');assert.equal(f.page.movementDirection(f.page.entries()[0]),'Money in');
  assert.equal(f.page.entries()[1].type,'WALLET_TO_WALLET');assert.equal(f.page.entries()[1].sourceCurrency,'USD');assert.equal(f.page.movementDirection(f.page.entries()[1]),'Money out');
});
test('Activity reads every wallet and ledger page, including both sides of an exchange',async()=>{
  const f=fixture('payments-list',{wallets:async()=>[{walletId:'usd',currency:'USD'},{walletId:'eur',currency:'EUR'}],ledger:async(id,page)=>({entries:[{entryId:id+'-'+page,entryType:id==='usd'?'DEBIT':'CREDIT',journalReference:'wallet:fx:'+page,amount:'50',createdAt:'2026-09-20T10:00:00Z'}],totalElements:id==='usd'?2:1})},'token');
  await new Promise(resolve=>setImmediate(resolve));assert.equal(f.page.entries().length,3);assert.equal(f.calls.filter(c=>c[0]==='ledger').length,3);f.page.typeFilter('SELF_TRANSFER');assert.equal(f.page.filteredActivity().length,3);
});
test('a failed wallet feed is reported while other available transactions remain visible',async()=>{
  const f=fixture('dashboard',{wallets:async()=>[{walletId:'w1',currency:'USD'}],payments:async()=>({items:[{id:'p1',createdAt:'2026-09-20',status:'COMPLETED'}],total:1}),ledger:async()=>{throw Error('Unavailable');}},'token');
  await new Promise(resolve=>setImmediate(resolve));assert.equal(f.page.recentActivity().length,1);assert.match(f.page.activityWarning(),/Could not load USD wallet activity/);
});

test('payment receipt shows customer details and chronologically orders every timestamped event',async()=>{
  const row={id:'p1',sourceAmount:'125',sourceCurrency:'USD',recipientId:'r1',sourceWalletId:'w1',status:'PROCESSING',selectedQuoteId:'q1',createdAt:'2026-09-20T10:00:00Z'};
  const events=[{eventId:'e2',eventType:'payout.completed',occurredAt:'2026-09-20T10:01:35Z',payload:{providerRef:'PROVIDER-123',summary:'Delivered'}},{eventId:'e1',eventType:'payment.initiated',occurredAt:'2026-09-20T10:00:01Z',payload:{amount:'125'}}];
  const f=fixture('payments-list',{payment:async()=>({...row,status:'COMPLETED'}),timeline:async()=>events,getQuotes:async()=>({quotes:[{id:'q1',feeAmount:'1.50',offeredRate:'83.8'}]})});
  f.page.payments([row]);f.page.recipients([{id:'r1',name:'Jamie',account:'TEST-ACCOUNT'}]);f.page.wallets([{walletId:'w1',currency:'USD',availableBalance:'1000'}]);
  await f.page.openDetail(row);assert.equal(f.page.detailBusy(),false);assert.equal(f.page.detailSteps().length,2);assert.equal(f.page.detailSteps()[0].timestamp,'2026-09-20T10:00:01Z');assert.equal(f.page.detailSteps()[1].title,'Payout completed');assert.equal(f.page.acceptedQuote().feeAmount,'1.50');
  assert.match(f.page.preciseTime(events[0].occurredAt),/35/);assert.equal(f.page.recipientFields().find(f=>f.label==='Account ending in').value,'•••• OUNT');assert.equal(f.page.quoteFields().find(f=>f.label==='Transfer fee').value,'$1.50');assert.equal(f.page.rawReceipt,undefined);assert.equal(f.page.detailSteps()[1].fields,undefined);assert.equal(f.navigation.length,0);
  assert.deepEqual(f.calls.map(c=>c[0]),['payment','timeline','getQuotes']);f.page.closeDetail();assert.equal(f.page.detailRow(),undefined);
});
test('wallet receipts open for all types and show only recorded ledger steps, including linked exchange legs',async()=>{
  const f=fixture('payments-list');
  const debit={id:'debit',isLedger:true,type:'SELF_TRANSFER',entryType:'DEBIT',sourceAmount:'50',sourceCurrency:'USD',journalReference:'wallet:fx:one',createdAt:'2026-09-20T10:00:00Z',status:'COMPLETED'};
  const credit={...debit,id:'credit',entryType:'CREDIT',sourceCurrency:'EUR',sourceAmount:'45'};f.page.entries([debit,credit]);
  await f.page.openDetail(debit);assert.equal(f.page.detailSteps().length,2);assert.match(f.page.detailSteps()[0].title,/Money out/);assert.match(f.page.detailSteps()[1].title,/Money in/);assert.equal(f.calls.length,0);
  for(const type of ['WALLET_TOPUP','WALLET_TO_WALLET']){await f.page.openDetail({...debit,type,journalReference:'unique-'+type});assert.equal(f.page.detailSteps().length,1);assert.equal(f.calls.length,0);}
});
test('receipt partial outages keep available payment data and never fabricate timeline steps or fees',async()=>{
  const f=fixture('payments-list',{payment:async()=>({id:'p1',status:'COMPLETED'}),timeline:async()=>{throw Error('Offline');},getQuotes:async()=>{throw Error('Offline');}});
  await f.page.openDetail({id:'p1',status:'PROCESSING',selectedQuoteId:'q1'});assert.equal(f.page.detailRecord().status,'COMPLETED');assert.equal(f.page.detailSteps().length,0);assert.equal(f.page.acceptedQuote(),undefined);assert.match(f.page.detailWarning(),/timeline is unavailable/);assert.match(f.page.detailWarning(),/fee details/);
});
test('closing or switching a receipt prevents late responses overwriting the current transaction',async()=>{
  let release;const f=fixture('payments-list',{payment:()=>new Promise(resolve=>{release=resolve;}),timeline:async()=>[],getQuotes:async()=>({quotes:[]})});
  const pending=f.page.openDetail({id:'p1'});await f.page.openDetail({id:'ledger',isLedger:true,sourceCurrency:'USD',createdAt:'2026-09-20'});release({id:'p1',status:'COMPLETED'});await pending;assert.equal(f.page.detailRow().id,'ledger');assert.equal(f.page.detailPayment(),undefined);assert.equal(f.page.detailBusy(),false);
  const next=f.page.openDetail({id:'p2'});f.page.closeDetail();release({id:'p2'});await next;assert.equal(f.page.detailRow(),undefined);
});
test('history cards and dashboard shortcuts open details for payments and ledger transactions',async()=>{
  const f=fixture('payments-list',{wallets:async()=>[{walletId:'w1',currency:'USD'}],ledger:async()=>({entries:[{entryId:'ledger-1',entryType:'CREDIT',amount:'500',createdAt:'2026-09-20T10:00:00Z'}],totalElements:1})},'token',{params:{id:'ledger-1'}});
  await new Promise(resolve=>setImmediate(resolve));assert.equal(f.page.detailRow().id,'ledger-1');
  const dashboard=fixture('dashboard');dashboard.page.openMovement({id:'ledger-1',isLedger:true});assert.equal(dashboard.navigation[0][0],'history');assert.equal(dashboard.navigation[0][1].id,'ledger-1');
  const view=fs.readFileSync(path.join(root,'views/payments-list.html'),'utf8');assert.match(view,/click:\$parents\[1\].openDetail/);assert.doesNotMatch(view,/rawReceipt|All transaction fields|API records|Event details|walletFields|detailFields|html:/);assert.match(view,/aria-modal="true"/);
});

test('processing totals are wallet-specific, exclude other statuses, and disappear after completion',()=>{
  const f=fixture('wallets'),usd={walletId:'usd',currency:'USD',heldBalance:'900'},eur={walletId:'eur',currency:'EUR'};
  f.page.payments([{id:'a',sourceWalletId:'usd',sourceCurrency:'USD',sourceAmount:'12.30',status:'PROCESSING'},{id:'b',sourceWalletId:'usd',sourceCurrency:'USD',sourceAmount:'7.70',status:'PROCESSING'},{id:'c',sourceWalletId:'eur',sourceCurrency:'EUR',sourceAmount:'8',status:'PROCESSING'},...['UNDER_REVIEW','COMPLETED','DRAFT','FAILED','REFUNDED'].map(status=>({sourceWalletId:'usd',sourceCurrency:'USD',sourceAmount:'999',status}))]);
  assert.equal(f.page.processingAmount(usd),20);assert.equal(f.page.processingAmount(eur),8);assert.equal(f.page.processingForWallet({walletId:'inr',currency:'INR'}).length,0);
  f.page.payments(f.page.payments().map(p=>({...p,status:'COMPLETED'})));assert.equal(f.page.processingForWallet(usd).length,0);assert.equal(f.page.processingAmount(usd),0);assert.equal(f.page.onHold().length,0);
});

test('wallet hold totals load beyond the first payment page and suppress funding implementation text',async()=>{
  const f=fixture('wallets',{wallets:async()=>[{walletId:'w1',currency:'USD'}],payments:async page=>({items:[{id:'p'+page,status:page?'PROCESSING':'COMPLETED',sourceWalletId:'w1',sourceCurrency:'USD',sourceAmount:'40'}],total:2})},'token');
  await new Promise(resolve=>setImmediate(resolve));assert.equal(f.page.processingAmount(f.page.wallets()[0]),40);assert.equal(f.calls.filter(c=>c[0]==='payments').length,2);
  assert.equal(f.page.movementDescription({journalReference:'wallet:demo:id',narration:'Demo funding credit'}),'Money added to your wallet');
  const view=fs.readFileSync(path.join(root,'views/wallets.html'),'utf8');assert.doesNotMatch(view,/demo/i);assert.match(view,/if:\$parent.processingForWallet\(\$data\).length/);assert.match(view,/if:onHold\(\).length/);
});

test('review save respects expired quotes and compliance states, never calling payout',async()=>{
  for(const status of ['UNDER_REVIEW','REJECTED']){
    const f=fixture('payments-new',{confirm:async()=>({id:'p1',status})});reviewedTransfer(f.page);await f.page.saveDraft();assert.equal(f.page.payment().status,status);assert.equal(f.page.canSubmitPayout(),false);assert.equal(f.page.step(),4);assert.ok(!f.calls.some(c=>c[0]==='payout'));
  }
  const f=fixture('payments-new');reviewedTransfer(f.page);f.page.now(Date.now()+400000);await f.page.saveDraft();assert.match(f.page.error(),/expired/);assert.equal(f.calls.length,0);
});

function payableReceipt(status='PROCESSING',extra={}){
  let current={id:'p1',status,selectedQuoteId:status==='PROCESSING'?'q1':null,sourceCurrency:'USD',payoutCurrency:'INR',sourceAmount:'100'};
  const quote={id:'q1',routeCode:'BANK_TRANSFER',feeAmount:'1',offeredRate:'83',recipientAmount:'8217',estimatedMinutes:30};
  const quotes=()=>({quotes:[quote],expiresAt:new Date(Date.now()+300000).toISOString()});
  const f=fixture('payments-list',{payment:async()=>({...current}),timeline:async()=>[],getQuotes:async()=>quotes(),quotes:async()=>{current.status='QUOTED';return quotes();},confirm:async()=>{current={...current,status:'PROCESSING',selectedQuoteId:'q1'};return {...current};},payout:async()=>{current.status='COMPLETED';return {};},...extra});
  return {...f,quote,current:()=>current,open:()=>f.page.openDetail({...current})};
}
test('Submit pay in an activity receipt sends a Processing payment without navigation or reconfirmation',async()=>{
  const f=payableReceipt();await f.open();assert.equal(f.page.canPayDetail(),true);await f.page.prepareDetailPay();assert.equal(f.page.detailRecord().status,'COMPLETED');assert.equal(f.calls.filter(c=>c[0]==='payout').length,1);assert.equal(f.calls.filter(c=>c[0]==='confirm').length,0);assert.equal(f.navigation.length,0);assert.equal(f.page.canPayDetail(),false);
});
test('older quoted drafts require selecting a current quote inside the receipt before submit',async()=>{
  const f=payableReceipt('QUOTED');await f.open();await f.page.prepareDetailPay();assert.equal(f.page.detailPayReview(),true);await f.page.submitDetailPay();assert.match(f.page.detailPayError(),/Choose a current/);assert.ok(!f.calls.some(c=>c[0]==='payout'));
  f.page.detailPayQuote(f.quote);await f.page.submitDetailPay();assert.equal(f.page.detailRecord().status,'COMPLETED');assert.equal(f.calls.filter(c=>c[0]==='confirm').length,1);assert.equal(f.calls.filter(c=>c[0]==='payout').length,1);
});
test('receipt blocks repeated and uncertain payout dispatches and payments already sent to provider',async()=>{
  let release;const f=payableReceipt('PROCESSING',{payout:()=>new Promise(resolve=>{release=resolve;})});await f.open();const pending=f.page.submitDetailPay();await new Promise(resolve=>setImmediate(resolve));await f.page.submitDetailPay();assert.equal(f.calls.filter(c=>c[0]==='payout').length,1);release({});await pending;await f.page.submitDetailPay();assert.equal(f.calls.filter(c=>c[0]==='payout').length,1);
  const failed=payableReceipt('PROCESSING',{payout:async()=>{throw Error('Connection interrupted');}});await failed.open();await failed.page.submitDetailPay();assert.match(failed.page.detailPayError(),/Connection interrupted/);await failed.page.submitDetailPay();assert.equal(failed.calls.filter(c=>c[0]==='payout').length,1);
  const sent=payableReceipt('PROCESSING',{timeline:async()=>[{eventType:'payout.submitted'}]});await sent.open();assert.equal(sent.page.canPayDetail(),false);await sent.page.submitDetailPay();assert.ok(!sent.calls.some(c=>c[0]==='payout'));
});
test('receipt never submits a payout for reviewed, rejected or completed payments',async()=>{
  for(const status of ['UNDER_REVIEW','REJECTED','COMPLETED','FAILED','CANCELLED']){const f=payableReceipt(status);await f.open();assert.equal(f.page.canPayDetail(),false);await f.page.submitDetailPay();assert.ok(!f.calls.some(c=>c[0]==='payout'));}
  const f=payableReceipt('QUOTED',{confirm:async()=>({id:'p1',status:'UNDER_REVIEW',selectedQuoteId:'q1'})});await f.open();await f.page.prepareDetailPay();f.page.detailPayQuote(f.quote);await f.page.submitDetailPay();assert.ok(!f.calls.some(c=>c[0]==='payout'));
});
test('Send recipient bank choices include requested banks and preserve custom bank support',async()=>{
  const view=fs.readFileSync(path.join(root,'views/payments-new.html'),'utf8');for(const bank of ['SBI','HDFC','ICICI','Axis','Citi'])assert.ok(view.includes('<option>'+bank+'</option>'));
  for(const bank of ['SBI','HDFC','ICICI','Axis','Citi','OTHER']){const f=fixture('payments-new',{recipient:async body=>({...body,id:'new',status:'ACTIVE'})});f.page.recipientName('Jamie');f.page.account('TEST123');f.page.bankName(bank);f.page.otherBankName('Example Bank');await f.page.saveRecipient();assert.equal(f.calls[0][1].bankName,bank==='OTHER'?'Example Bank':bank);assert.equal(f.page.recipientId(),'new');}
});

test('quoted payments appear in matching wallet hold totals without changing available balances',()=>{
  const f=fixture('wallets'),usd={walletId:'usd',currency:'USD',availableBalance:'1000'},eur={walletId:'eur',currency:'EUR',availableBalance:'500'};
  f.page.payments([{sourceWalletId:'usd',sourceCurrency:'USD',sourceAmount:'75.50',status:'QUOTED'},{sourceWalletId:'usd',sourceCurrency:'USD',sourceAmount:'24.50',status:'PROCESSING'},{sourceWalletId:'eur',sourceCurrency:'EUR',sourceAmount:'12',status:'QUOTED'}]);
  assert.equal(f.page.processingAmount(usd),100);assert.equal(f.page.processingAmount(eur),12);assert.equal(f.page.quotedForWallet(usd).length,1);assert.match(f.page.holdCaption(usd),/quoted/);assert.equal(usd.availableBalance,'1000');
  f.page.payments([]);assert.equal(f.page.processingForWallet(usd).length,0);
});
test('KYC requires real files and rejects empty, oversized and excessive selections',async()=>{
 const f=fixture('kyc');f.page.docNumber('TEST123');f.page.documents([{fileName:'metadata.pdf'}]);await f.page.submitKyc();assert.equal(f.calls.length,0);assert.match(f.page.error(),/actual document/);
 for(const files of [[{name:'empty.pdf',type:'application/pdf',size:0}],[{name:'large.pdf',type:'application/pdf',size:6*1024*1024}],Array(5).fill({name:'file.pdf',type:'application/pdf',size:10})]){f.page.chooseFiles(null,{target:{files,value:'files'}});assert.equal(f.page.documents().length,0);assert.match(f.page.error(),/one to four/);}
});
test('document preview cancels stale fetches and remains closed after navigation',async()=>{
 let resolve;const f=fixture('kyc',{kycDocument:()=>new Promise(r=>resolve=r)});const opening=f.page.openDocument({id:'document',available:true,fileName:'identity.png'});assert.equal(f.page.previewLoading(),true);f.page.closeDocument();resolve(new Blob(['test'],{type:'image/png'}));await opening;assert.equal(f.page.documentPreview(),undefined);assert.equal(f.page.previewLoading(),false);
 await f.page.openDocument({available:false,fileName:'legacy.pdf'});assert.match(f.page.previewError(),/not stored/);f.page.disconnected();assert.equal(f.page.documentPreview(),undefined);
});

test('bank linking sends only masked metadata and selects the returned account in add money',async()=>{
  const bank={id:'b1',bankName:'HDFC',accountLast4:'4321',currency:'INR',status:'VERIFIED'};
  const f=fixture('wallets',{linkBank:async()=>bank});f.page.openAddMoney();f.page.openLinkBank();f.page.bankFormName(' HDFC ');f.page.bankLast4('4321');f.page.bankCurrency('INR');await f.page.linkBank();
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls[0][1])),{bankName:'HDFC',accountLast4:'4321',currency:'INR'});assert.equal(f.page.bankId(),'b1');assert.equal(f.page.addMoneyOpen(),true);assert.equal(f.page.banks().length,1);
});

test('bank funding requires KYC and matching verified bank, and waits for review confirmation',async()=>{
  const f=fixture('wallets',{bankTopup:async()=>({walletId:'w1'})});f.page.banks([{id:'b1',currency:'USD',status:'VERIFIED'}]);f.page.bankId('b1');f.page.prepareFunding();assert.match(f.page.error(),/identity/);assert.equal(f.calls.length,0);
  f.session.user({role:'CUSTOMER',kycStatus:'VERIFIED'});f.page.prepareFunding();assert.equal(f.page.operationReview().kind,'topup');assert.equal(f.calls.length,0);await f.page.confirmMoney();assert.equal(f.calls[0][0],'bankTopup');assert.equal(f.calls[0][1],'b1');assert.equal(f.page.operationReview(),undefined);
  f.page.currency('EUR');f.page.prepareFunding();assert.match(f.page.error(),/same currency/);
});

test('wallet transfer uses exactly one recipient selector and SOURCE/TARGET contracts',async()=>{
  const f=fixture('wallets',{walletTransfer:async()=>({sourceWalletId:'w1',fromCurrency:'USD',toCurrency:'INR',creditedAmount:'100',sourceAmount:'2',fee:'0.2',rate:'80'})});f.page.wallets([{walletId:'w1',currency:'USD',availableBalance:'1000'}]);f.page.transferEmail(' JAMIE@example.test ');f.page.transferAmount('10');f.page.prepareMoney('transfer');assert.equal(f.calls.length,0);await f.page.confirmMoney();
  const body=f.calls[0][1];assert.equal(body.toEmail,'jamie@example.test');assert.equal(body.toUserId,undefined);assert.equal(body.amountMode,'SOURCE');assert.equal(f.page.operationResult().creditedAmount,'100');
  f.page.transferSelector('id');f.page.transferUserId('11111111-1111-4111-8111-111111111111');f.page.amountMode('TARGET');f.page.prepareMoney('transfer');assert.equal(f.page.operationReview().body.toEmail,undefined);assert.equal(f.page.operationReview().body.amountMode,'TARGET');
});

test('withdrawal rejects insufficient balance and duplicate confirmation cannot repeat a write',async()=>{
  let release;const f=fixture('wallets',{withdraw:()=>new Promise(r=>{release=r;})});f.page.wallets([{walletId:'w1',currency:'USD',availableBalance:'20'}]);f.page.banks([{id:'b1',currency:'USD',status:'VERIFIED'}]);f.page.bankId('b1');f.page.withdrawAmount('50');f.page.prepareMoney('withdraw');assert.match(f.page.error(),/Insufficient/);
  f.page.withdrawAmount('10');f.page.prepareMoney('withdraw');const first=f.page.confirmMoney();await f.page.confirmMoney();assert.equal(f.calls.length,1);release({walletId:'w1'});await first;assert.equal(f.page.operationReview(),undefined);await f.page.confirmMoney();assert.equal(f.calls.filter(c=>c[0]==='withdraw').length,1);
});

test('successful bank write with failed balance refresh remains successful, never offers resubmit',async()=>{
  const f=fixture('wallets',{bankTopup:async()=>({walletId:'w1'}),wallets:async()=>{throw Error('Offline');}});f.session.user({kycStatus:'VERIFIED'});f.page.banks([{id:'b1',currency:'USD',status:'VERIFIED'}]);f.page.bankId('b1');f.page.prepareFunding();await f.page.confirmMoney();assert.equal(f.page.operationResult().title,'Money added');assert.equal(f.page.operationReview(),undefined);assert.match(f.page.bankWarning(),/succeeded/);
});

test('new ledger reference categories appear in activity for top-ups, P2P and withdrawals',async()=>{
  const f=fixture('payments-list',{wallets:async()=>[{walletId:'w1',currency:'USD'}],ledger:async()=>({entries:['topup','p2p','withdraw'].map(prefix=>({entryId:prefix,journalReference:'wallet:'+prefix+':id',entryType:prefix==='topup'?'CREDIT':'DEBIT',amount:'10',createdAt:'2026-09-20T10:00:00Z'})),totalElements:3})},'token');await new Promise(resolve=>setImmediate(resolve));assert.deepEqual(Array.from(f.page.entries(),e=>e.type),['WALLET_TOPUP','WALLET_TO_WALLET','WITHDRAWAL']);assert.equal(f.page.movementLabel(f.page.entries()[2]),'Bank withdrawal');
});
