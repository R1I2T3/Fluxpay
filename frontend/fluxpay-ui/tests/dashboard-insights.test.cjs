const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript');
const root=path.join(__dirname,'../src/ts/services');
function load(file){const context={exports:{},require:dep=>dep==='./activity'?load('activity.ts'):{fluxApi:{}},Intl,Date};vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root,file),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);return context.exports;}
const {moneyFlow,recipientCountries,periodRecords,transferStatus,chartPath}=load('dashboard-insights.ts');
const now=new Date(2026,8,23,12).getTime(),createdAt=new Date(2026,8,23,10).toISOString();
test('money flow separates currencies, includes completed payment and ledger movements, excludes drafts and failures',()=>{
 const payments=[{sourceCurrency:'USD',sourceAmount:'25',createdAt,status:'COMPLETED'},{sourceCurrency:'USD',sourceAmount:'99',createdAt,status:'PROCESSING'},{sourceCurrency:'USD',sourceAmount:'80',createdAt,status:'FAILED'},{sourceCurrency:'EUR',sourceAmount:'1000',createdAt,status:'COMPLETED'}];
 const entries=[{isLedger:true,entryType:'CREDIT',sourceCurrency:'USD',sourceAmount:'100',createdAt,status:'COMPLETED'},{isLedger:true,entryType:'DEBIT',sourceCurrency:'USD',sourceAmount:'10',createdAt,status:'COMPLETED'},{isLedger:true,entryType:'CREDIT',sourceCurrency:'USD',sourceAmount:'invalid',createdAt,status:'COMPLETED'}];
 const flow=moneyFlow(payments,entries,'USD',7,now);assert.equal(flow.incoming,100);assert.equal(flow.outgoing,35);assert.equal(flow.count,3);assert.equal(flow.buckets.length,7);
 assert.equal(moneyFlow(payments,entries,'EUR',7,now).outgoing,1000);
});
test('periods use inclusive calendar dates, exclude future and invalid records, and generate honest empty charts',()=>{
 const rows=[{createdAt:new Date(2026,8,17).toISOString()},{createdAt:new Date(2026,8,16,23,59).toISOString()},{createdAt:new Date(now+1).toISOString()},{createdAt:'invalid'}];assert.equal(periodRecords(rows,7,now).length,1);
 const flow=moneyFlow([],[],'USD',30,now);assert.equal(flow.incoming,0);assert.equal(flow.outgoing,0);assert.equal(flow.count,0);assert.equal(flow.buckets.length,30);assert.ok(flow.buckets.every(b=>b.incoming===0&&b.outgoing===0));assert.ok(!chartPath([0,0],0).includes('NaN'));
});
test('recipient geography counts real active recipients without inventing locations for unsupported countries',()=>{
 const countries=recipientCountries([{id:'a',country:'IN',status:'ACTIVE'},{id:'b',country:'IN',status:'ACTIVE'},{id:'c',country:'DE',status:'INACTIVE'},{id:'d',country:'IS',status:'ACTIVE'}],[{recipientId:'a',status:'COMPLETED'}]);
 assert.equal(countries.length,2);assert.equal(countries[0].people,2);assert.equal(countries[0].payments,1);assert.equal(countries[0].name,'India');assert.ok(countries[0].point);assert.equal(countries.find(c=>c.code==='IS').point,undefined);assert.equal(recipientCountries([],[]).length,0);
});
test('transfer-status distribution distinguishes completed, review, drafts, failures and refunds',()=>{
 const summary=transferStatus(['COMPLETED','PROCESSING','QUOTED','UNDER_REVIEW','DRAFT','FAILED','REJECTED','REFUNDED','CANCELLED'].map(status=>({status})));
 assert.equal(summary.total,9);assert.equal(summary.completed,1);assert.equal(summary.rows.reduce((sum,row)=>sum+row.count,0),9);assert.equal(summary.rows[1].count,3);assert.match(summary.background,/conic-gradient/);assert.equal(transferStatus([]).background,'#edf1f8');
});
test('dashboard exposes chart data and controls without a sidebar or invented growth percentages',()=>{
 const html=fs.readFileSync(path.join(root,'../views/dashboard.html'),'utf8');for(const text of ['Insights currency','Insights period','View daily amounts','Country-level overview','Currencies are never combined','Transfer status'])assert.ok(html.includes(text));assert.ok(!html.includes('class="sidebar"'));assert.ok(!html.includes('account-overview'));assert.ok(html.indexOf('dashboard-money-actions')<html.indexOf('insights-hero'));
});
