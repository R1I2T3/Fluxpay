const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const ko=require('knockout');
const read=file=>fs.readFileSync(path.join(__dirname,'../src',file),'utf8');
const compile=file=>ts.transpileModule(read(file),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText;
const providerId='11111111-1111-4111-8111-111111111111';
const routeId='22222222-2222-4222-8222-222222222222';

function fixture(){
  const calls=[];
  const dataFor=url=>{
    if(url==='/api/admin/rail-types')return {railTypes:[{railType:'BANK_NETWORK',supportedDestinations:['EXTERNAL_ACCOUNT']}]};
    if(url==='/api/admin/providers')return {providers:[]};
    if(url==='/api/admin/routes')return {routes:[]};
    if(url.endsWith('?version=3'))return {disposition:'ARCHIVED',id:routeId};
    return {};
  };
  const context={exports:{},window:{},sessionStorage:{getItem:()=>'test-token'},fetch:async(url,options)=>{calls.push([url,options]);return {ok:true,status:200,json:async()=>({data:dataFor(url)})};}};
  vm.runInNewContext(compile('ts/services/flux-api.ts'),context);
  return {api:context.exports.fluxApi,calls};
}

test('routing CRUD calls admin endpoints', async () => {
  const {api,calls}=fixture();
  await api.railTypes();
  await api.providers();
  await api.createProvider({
    providerCode:'HDFC_BANK',providerName:'HDFC Bank',
    railType:'BANK_NETWORK',active:true
  });
  await api.routesAdmin();
  await api.deleteRoute(routeId,3);
  assert.deepEqual(calls.map(([url,o])=>[o.method,url]),[
    ['GET','/api/admin/rail-types'],
    ['GET','/api/admin/providers'],
    ['POST','/api/admin/providers'],
    ['GET','/api/admin/routes'],
    ['DELETE','/api/admin/routes/'+routeId+'?version=3']
  ]);
});

test('provider and route updates pass versions in the body with auth and encoded ids',async()=>{
  const {api,calls}=fixture();
  await api.updateProvider(providerId,{providerName:'HDFC Bank Ltd',railType:'BANK_NETWORK',active:true,version:2});
  await api.deleteProvider(providerId,2);
  await api.createRoute({providerId,routeCode:'HDFC_INR_STANDARD',name:'HDFC INR Standard',destinationType:'EXTERNAL_ACCOUNT',destinationCountry:'IN',payoutCurrency:'INR',baseFee:'5.0000',fxSpreadPercentage:'0.500000',estimatedMinutes:120,configuredSuccessRate:'99.00',minimumRecipientAmount:null,maximumRecipientAmount:null,active:true});
  await api.updateRoute(routeId,{providerId,name:'HDFC INR Standard',destinationType:'EXTERNAL_ACCOUNT',destinationCountry:'IN',payoutCurrency:'INR',baseFee:'6.0000',fxSpreadPercentage:'1.000000',estimatedMinutes:120,configuredSuccessRate:'99.00',minimumRecipientAmount:null,maximumRecipientAmount:null,active:true,version:3});
  assert.deepEqual(calls.map(([url,o])=>[o.method,url]),[
    ['PUT','/api/admin/providers/'+providerId],
    ['DELETE','/api/admin/providers/'+providerId+'?version=2'],
    ['POST','/api/admin/routes'],
    ['PUT','/api/admin/routes/'+routeId]
  ]);
  assert.deepEqual(JSON.parse(calls[0][1].body),{providerName:'HDFC Bank Ltd',railType:'BANK_NETWORK',active:true,version:2});
  assert.deepEqual(JSON.parse(calls[2][1].body).routeCode,'HDFC_INR_STANDARD');
  assert.equal(JSON.parse(calls[3][1].body).version,3);
  for(const [,options] of calls)assert.equal(options.headers.Authorization,'Bearer test-token');
  await api.deleteRoute('a/b',1);
  assert.equal(calls.at(-1)[0],'/api/admin/routes/a%2Fb?version=1');
});

function workspace(overrides={},admin=true,runtime={}){
  const calls=[];
  const provider={id:providerId,providerCode:'HDFC_BANK',providerName:'HDFC Bank',railType:'BANK_NETWORK',active:true,version:0};
  const route={id:routeId,providerId,routeCode:'HDFC_INR_STANDARD',name:'HDFC INR Standard',destinationType:'EXTERNAL_ACCOUNT',destinationCountry:'IN',payoutCurrency:'INR',baseFee:'5.0000',fxSpreadPercentage:'0.500000',estimatedMinutes:120,configuredSuccessRate:'99.00',effectiveSuccessRate:'99.00',completedCount:0,failedCount:0,minimumRecipientAmount:null,maximumRecipientAmount:null,active:true,version:0};
  const api=new Proxy(overrides,{get:(obj,name)=>async(...args)=>{calls.push([name,...args]);if(name in obj)return obj[name](...args);if(name==='railTypes')return [{railType:'BANK_NETWORK',supportedDestinations:['EXTERNAL_ACCOUNT']}];if(name==='providers')return [provider];if(name==='routesAdmin')return [route];if(name==='createProvider'||name==='updateProvider')return {...provider};if(name==='createRoute'||name==='updateRoute')return {...route};if(name==='deleteProvider')return {disposition:'DELETED',id:providerId};if(name==='deleteRoute')return {disposition:'ARCHIVED',id:routeId};}});
  const session={user:ko.observable({role:admin?'ADMIN':'USER'})};session.isAdmin=ko.pureComputed(()=>session.user()?.role==='ADMIN');
  const context={exports:{},require:name=>name==='knockout'?ko:name==='./session'?{session}:{fluxApi:api},...runtime};
  vm.runInNewContext(compile('ts/services/routing-workspace.ts'),context);
  return {page:new context.exports.RoutingWorkspace(),calls,session,provider,route};
}

test('routing workspace loads rails, providers and routes for admins only',async()=>{
  const {page,calls}=workspace();
  await page.loadAll();
  assert.equal(page.railTypes().length,1);
  assert.equal(page.providers().length,1);
  assert.equal(page.routes().length,1);
  assert.deepEqual(calls.map(c=>c[0]),['railTypes','providers','routesAdmin']);
  page.dispose();
  const blocked=workspace({},false);
  await blocked.page.loadAll();
  assert.equal(blocked.calls.length,0);
  assert.match(blocked.page.error(),/administrator/);
  blocked.page.dispose();
});

test('provider validation rejects malformed codes without API calls',async()=>{
  const {page,calls}=workspace();
  page.newProvider();
  page.providerCode('AB');page.providerName('HDFC Bank');page.providerRail('BANK_NETWORK');
  await page.saveProvider();
  assert.equal(calls.length,0);
  assert.equal(page.providerForm(),true);
  assert.equal(page.providerCode(),'AB');
  assert.match(page.providerError(),/code/i);
  page.dispose();
});

test('route validation rejects invalid corridor, money and limit values',async()=>{
  const {page,calls}=workspace();
  page.newRoute();
  const valid={provider:providerId,code:'HDFC_INR_STANDARD',name:'HDFC INR Standard',destination:'EXTERNAL_ACCOUNT',country:'IN',currency:'INR',fee:'5',spread:'0.5',eta:'120',reliability:'99',min:'',max:''};
  const fill=v=>{page.routeProviderId(v.provider);page.routeCode(v.code);page.routeName(v.name);page.routeDestination(v.destination);page.routeCountry(v.country);page.routeCurrency(v.currency);page.routeFee(v.fee);page.routeSpread(v.spread);page.routeEta(v.eta);page.routeReliability(v.reliability);page.routeMin(v.min);page.routeMax(v.max);};
  for(const mutate of [v=>v.country='KENYA',v=>v.currency='IN',v=>v.fee='-1',v=>v.eta='0',v=>v.reliability='101',v=>{v.min='500';v.max='100';}]){
    const attempt={...valid};mutate(attempt);fill(attempt);
    await page.saveRoute();
    assert.equal(calls.length,0);
    assert.equal(page.routeForm(),true);
    assert.ok(page.routeError());
  }
  assert.equal(page.routeCode(),'HDFC_INR_STANDARD');
  page.dispose();
});

test('route filters narrow the catalogue by provider, corridor and status',async()=>{
  const {page}=workspace();
  await page.loadAll();
  page.routes.push({id:'33333333-3333-4333-8333-333333333333',providerId,routeCode:'HDFC_INR_EXPRESS',name:'HDFC INR Express',destinationType:'EXTERNAL_ACCOUNT',destinationCountry:'KE',payoutCurrency:'KES',baseFee:'6.0000',fxSpreadPercentage:'1.000000',estimatedMinutes:30,configuredSuccessRate:'98.00',effectiveSuccessRate:'98.00',completedCount:1,failedCount:0,minimumRecipientAmount:null,maximumRecipientAmount:null,active:false,version:0});
  assert.equal(page.filteredRoutes().length,2);
  page.countryFilter('KE');
  assert.deepEqual(page.filteredRoutes().map(r=>r.routeCode),['HDFC_INR_EXPRESS']);
  page.countryFilter('');page.currencyFilter('KES');
  assert.deepEqual(page.filteredRoutes().map(r=>r.routeCode),['HDFC_INR_EXPRESS']);
  page.currencyFilter('');page.statusFilter('INACTIVE');
  assert.deepEqual(page.filteredRoutes().map(r=>r.routeCode),['HDFC_INR_EXPRESS']);
  page.statusFilter('ALL');page.search('express');
  assert.deepEqual(page.filteredRoutes().map(r=>r.routeCode),['HDFC_INR_EXPRESS']);
  page.search('');page.destinationFilter('INTERNAL_WALLET');
  assert.equal(page.filteredRoutes().length,0);
  page.destinationFilter('ALL');page.providerFilter('no-such-provider');
  assert.equal(page.filteredRoutes().length,0);
  page.dispose();
});

test('system-protected badges follow server flags, not code conventions',()=>{
  const {page}=workspace();
  const internal={id:providerId,providerCode:'FLUXPAY',providerName:'FluxPay',railType:'INTERNAL_LEDGER',active:true,systemProtected:true,archivedAt:null,version:0};
  assert.equal(page.providerProtected(internal),true);
  assert.equal(page.providerProtected({providerCode:'HDFC_BANK',systemProtected:false}),false);
  assert.equal(page.providerProtected({providerCode:'FLUXPAY'}),false);
  assert.equal(page.providerProtected({providerCode:'HDFC_BANK',systemProtected:true}),true);
  assert.equal(page.routeProtected({routeCode:'FLUXPAY_INR_INTERNAL',providerId,systemProtected:true}),true);
  assert.equal(page.routeProtected({routeCode:'FLUXPAY_INR_INTERNAL',providerId}),false);
  assert.equal(page.routeProtected({routeCode:'HDFC_INR_STANDARD',providerId:'unknown-provider',systemProtected:false}),false);
  assert.equal(page.routeProtected({routeCode:'HDFC_INR_STANDARD',providerId:'unknown-provider',systemProtected:true}),true);
  page.dispose();
});

test('delete uses archive confirmation and reports the server disposition',async()=>{
  const {page,calls}=workspace();
  await page.loadAll();
  page.requestDeleteRoute(page.routes()[0]);
  assert.equal(page.confirmation(),'delete-route');
  assert.equal(calls.filter(c=>c[0]==='deleteRoute').length,0);
  await page.confirm();
  assert.match(page.notice(),/archived/i);
  assert.equal(page.confirmation(),'');
  page.requestDeleteProvider(page.providers()[0]);
  page.cancelConfirmation();
  assert.equal(calls.filter(c=>c[0]==='deleteProvider').length,0);
  assert.equal(page.confirmation(),'');
  page.requestDeleteProvider(page.providers()[0]);
  await page.confirm();
  assert.match(page.notice(),/deleted/i);
  page.dispose();
});

test('provider stale conflict rebases the edit target and retries retained entries with the refreshed version',async()=>{
  const initial={id:providerId,providerCode:'HDFC_BANK',providerName:'HDFC Bank',railType:'BANK_NETWORK',active:true,version:0};
  const latest={...initial,providerName:'Server renamed provider',version:4};
  let providerReads=0;
  const updates=[];
  const result=workspace({
    providers:async()=>providerReads++===0?[initial]:[latest],
    updateProvider:async(id,body)=>{
      updates.push([id,body]);
      if(updates.length===1)throw new Error('STALE_PROVIDER: version 0 is stale');
      return {...latest,...body,version:5};
    }
  });
  await result.page.loadAll();
  result.page.editProvider(result.page.providers()[0]);
  result.page.providerName('Operator proposed provider');
  result.page.providerActive(false);

  await result.page.saveProvider();

  assert.match(result.page.providerError(),/stale/i);
  assert.equal(result.page.providerForm(),true);
  assert.equal(result.page.providerName(),'Operator proposed provider');
  assert.equal(result.page.providerRail(),'BANK_NETWORK');
  assert.equal(result.page.providerActive(),false);
  assert.equal(result.page.providerEditTarget().version,4);

  await result.page.saveProvider();

  assert.equal(updates.length,2);
  assert.equal(updates[1][0],providerId);
  assert.equal(updates[1][1].providerName,'Operator proposed provider');
  assert.equal(updates[1][1].railType,'BANK_NETWORK');
  assert.equal(updates[1][1].active,false);
  assert.equal(updates[1][1].version,4);
  result.page.dispose();
});

test('route stale conflict rebases the edit target and retries retained entries with the refreshed version',async()=>{
  const initial={id:routeId,providerId,routeCode:'HDFC_INR_STANDARD',name:'HDFC INR Standard',destinationType:'EXTERNAL_ACCOUNT',destinationCountry:'IN',payoutCurrency:'INR',baseFee:'5.0000',fxSpreadPercentage:'0.500000',estimatedMinutes:120,configuredSuccessRate:'99.00',effectiveSuccessRate:'99.00',completedCount:0,failedCount:0,minimumRecipientAmount:null,maximumRecipientAmount:null,active:true,version:0};
  const latest={...initial,name:'Server renamed route',baseFee:'6.0000',version:7};
  let routeReads=0;
  const updates=[];
  const result=workspace({
    routesAdmin:async()=>routeReads++===0?[initial]:[latest],
    updateRoute:async(id,body)=>{
      updates.push([id,body]);
      if(updates.length===1)throw new Error('STALE_ROUTE: version 0 is stale');
      return {...latest,...body,version:8};
    }
  });
  await result.page.loadAll();
  result.page.editRoute(result.page.routes()[0]);
  result.page.routeName('Operator proposed route');
  result.page.routeFee('7.5000');
  result.page.routeSpread('0.750000');
  result.page.routeEta('90');
  result.page.routeReliability('98.50');
  result.page.routeMin('10');
  result.page.routeMax('5000');
  result.page.routeActive(false);

  await result.page.saveRoute();

  assert.match(result.page.routeError(),/stale/i);
  assert.equal(result.page.routeForm(),true);
  assert.equal(result.page.routeName(),'Operator proposed route');
  assert.equal(result.page.routeFee(),'7.5000');
  assert.equal(result.page.routeSpread(),'0.750000');
  assert.equal(result.page.routeEta(),'90');
  assert.equal(result.page.routeReliability(),'98.50');
  assert.equal(result.page.routeMin(),'10');
  assert.equal(result.page.routeMax(),'5000');
  assert.equal(result.page.routeActive(),false);
  assert.equal(result.page.routeEditTarget().version,7);

  await result.page.saveRoute();

  assert.equal(updates.length,2);
  assert.equal(updates[1][0],routeId);
  assert.equal(updates[1][1].version,7);
  assert.equal(updates[1][1].name,'Operator proposed route');
  assert.equal(updates[1][1].baseFee,7.5);
  assert.equal(updates[1][1].active,false);
  result.page.dispose();
});

test('stale editors retain entries and block another update when the record was removed',async()=>{
  let providerUpdates=0;
  let providerReads=0;
  const providerResult=workspace({
    providers:async()=>providerReads++===0?[{id:providerId,providerCode:'HDFC_BANK',providerName:'HDFC Bank',railType:'BANK_NETWORK',active:true,version:0}]:[],
    updateProvider:async()=>{providerUpdates++;throw new Error('STALE_PROVIDER: version 0 is stale');}
  });
  await providerResult.page.loadAll();
  providerResult.page.editProvider(providerResult.page.providers()[0]);
  providerResult.page.providerName('Retained provider');
  await providerResult.page.saveProvider();
  assert.match(providerResult.page.providerError(),/removed/i);
  assert.equal(providerResult.page.providerName(),'Retained provider');
  await providerResult.page.saveProvider();
  assert.equal(providerUpdates,1);
  providerResult.page.dispose();

  let routeUpdates=0;
  let routeReads=0;
  const routeResult=workspace({
    routesAdmin:async()=>routeReads++===0?[{id:routeId,providerId,routeCode:'HDFC_INR_STANDARD',name:'HDFC INR Standard',destinationType:'EXTERNAL_ACCOUNT',destinationCountry:'IN',payoutCurrency:'INR',baseFee:'5.0000',fxSpreadPercentage:'0.500000',estimatedMinutes:120,configuredSuccessRate:'99.00',effectiveSuccessRate:'99.00',completedCount:0,failedCount:0,minimumRecipientAmount:null,maximumRecipientAmount:null,active:true,version:0}]:[],
    updateRoute:async()=>{routeUpdates++;throw new Error('STALE_ROUTE: version 0 is stale');}
  });
  await routeResult.page.loadAll();
  routeResult.page.editRoute(routeResult.page.routes()[0]);
  routeResult.page.routeName('Retained route');
  await routeResult.page.saveRoute();
  assert.match(routeResult.page.routeError(),/removed/i);
  assert.equal(routeResult.page.routeName(),'Retained route');
  await routeResult.page.saveRoute();
  assert.equal(routeUpdates,1);
  routeResult.page.dispose();
});

test('rapid duplicate actions are blocked and logout clears late responses',async()=>{
  let finish;
  const {page,calls,session}=workspace({railTypes:()=>new Promise(resolve=>finish=resolve)});
  const first=page.loadAll();
  await page.loadAll();
  assert.equal(calls.filter(c=>c[0]==='railTypes').length,1);
  session.user(null);
  finish([]);
  await first;
  assert.equal(page.railTypes().length,0);
  assert.equal(page.providers().length,0);
  assert.equal(page.routes().length,0);
  page.dispose();
});

test('routing tab renders providers and routes with text bindings and alertdialog confirmations',()=>{
  const html=read('ts/views/admin.html');
  assert.ok(html.includes("adminTab()==='routing'"));
  assert.ok(html.includes('with:routing'));
  for(const binding of ['filteredProviders','filteredRoutes','newProvider','saveProvider','newRoute','saveRoute','requestDeleteProvider','requestDeleteRoute','providerFilter','destinationFilter','countryFilter','currencyFilter','statusFilter','providerProtected','routeProtected'])assert.ok(html.includes(binding),`missing ${binding}`);
  assert.ok(html.includes('role="alertdialog"'));
  assert.ok(html.includes('System protected'));
  assert.ok(!/data-bind="[^"]*\bhtml\s*:/.test(html));
});

test('admin view model exposes the transfer-routing tab and workspace',()=>{
  const source=read('ts/viewModels/admin.ts');
  assert.match(source,/\{\s*id:\s*'routing',\s*label:\s*'Transfer routing'\s*\}/);
  assert.ok(source.includes('RoutingWorkspace'));
  assert.ok(source.includes('routing'));
});

test('page no longer owns the legacy payout route editor',()=>{
  const source=read('ts/services/page.ts');
  for(const token of ['routeEditing','editRoute','saveRoute','closeRoute','routeFee','routeSpread','routeMinutes','routeSuccess','routeActive'])assert.ok(!source.includes(token),`legacy ${token} remains`);
  const html=read('ts/views/admin.html');
  assert.ok(!html.includes('routeEditing'));
  assert.ok(!html.includes('Edit payout route'));
  assert.ok(!html.includes('Save route controls'));
});

test('routing styles add only grid and filter rules',()=>{
  const css=read('css/workspace.css');
  assert.match(css,/\.routing-filters\s*\{/);
  assert.match(css,/\.routing-grid\s*\{/);
  assert.match(css,/\.protected-badge\s*\{/);
});
