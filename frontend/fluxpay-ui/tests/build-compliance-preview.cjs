// Isolated browser QA. Real AMD models/templates/API client; in-memory HTTP fixture only.
// No real authentication, database writes or provider calls. Output goes in ignored staging.
const fs=require('node:fs');
const path=require('node:path');
const stage=path.join(__dirname,'../web-dev');
const source=path.join(__dirname,'../src');
const index=fs.readFileSync(path.join(source,'index.html'),'utf8');
const styles=[...index.matchAll(/<link[^>]+rel="stylesheet"[^>]*>/g)].map(m=>m[0]).join('\n');
const html=fs.readFileSync(path.join(source,'ts/views/admin.html'),'utf8');
const fixture=String.raw`
const pid='11111111-1111-4111-8111-111111111111',cid='22222222-2222-4222-8222-222222222222';
const createdAt='2026-09-17T09:00:00Z';
let policies=[{id:pid,title:'Cross-border payment review',category:'PAYMENT_REVIEW',content:'Payments that require review must be assessed against the applicable source-of-funds and destination rules. Record the evidence and decision before releasing a payment.',documentHash:'qa-fixture-not-a-real-hash',createdAt,chunks:[{id:'chunk-qa',policyDocumentId:pid,chunkNumber:1,content:'Record the evidence and decision before releasing a payment.',createdAt}]}];
let cases=[{id:cid,paymentId:pid,reviewReference:'qa-review',risk:'HIGH',status:'OPEN',riskReasons:['High-value transfer requires review','Confirm supporting source-of-funds evidence'],suggestedAction:'Review the policy and supporting records before a decision.',decidedBy:null,decidedAt:null,decisionReason:null,createdAt},{id:'33333333-3333-4333-8333-333333333333',paymentId:pid,reviewReference:null,risk:'MEDIUM',status:'OPEN',riskReasons:['QA manual review'],suggestedAction:'Record the review outcome.',decidedBy:null,decidedAt:null,decisionReason:null,createdAt}];
const nativeFetch=window.fetch.bind(window);
window.fetch=async function(url,options={}){
 if(!String(url).startsWith('/api/'))return nativeFetch(url,options);
 const parsed=new URL(url,location.origin),parts=parsed.pathname.split('/').filter(Boolean),method=options.method||'GET',body=options.body?JSON.parse(options.body):{};
 let data,status=200;
 if(parts[1]==='policies'){
  const p=policies.find(x=>x.id===parts[2]);
  if(method==='GET')data=parts[3]==='chunks'?p?.chunks:parts[2]?p:policies;
  else if(method==='POST'&&!parts[2]){data={...body,id:crypto.randomUUID(),createdAt,documentHash:'qa-new-hash',chunks:[]};policies.unshift(data);status=201;}
  else if(method==='POST'&&parts[3]==='chunks'){data={id:crypto.randomUUID(),policyDocumentId:p.id,chunkNumber:p.chunks.length+1,content:body.content,createdAt};p.chunks.push(data);status=201;}
  else if(method==='POST'&&parts[3]==='index'){p.chunks=[{id:crypto.randomUUID(),policyDocumentId:p.id,chunkNumber:1,content:p.content,createdAt}];data={policyDocumentId:p.id,chunkCount:p.chunks.length};}
  else if(method==='DELETE'){policies=policies.filter(x=>x.id!==parts[2]);status=204;}
 }else if(parts[1]==='compliance'){
  const c=cases.find(x=>x.id===parts[3]);
  if(method==='GET')data=parts[3]?c:cases.filter(x=>!parsed.searchParams.get('status')||x.status===parsed.searchParams.get('status'));
  else if(method==='POST'){data={...body,id:crypto.randomUUID(),reviewReference:null,status:'OPEN',createdAt,decidedBy:null,decidedAt:null,decisionReason:null};cases.unshift(data);status=201;}
  else if(method==='PUT'){Object.assign(c,{status:parts[4]==='approve'?'APPROVED':'REJECTED',decidedBy:'qa-admin@example.test',decidedAt:createdAt,decisionReason:body.decisionReason});data=c;}
  else if(method==='DELETE'){cases=cases.filter(x=>x.id!==parts[3]);status=204;}
 }else if(parts[1]==='copilot'){
  if(body.question.toLowerCase().includes('unavailable'))return new Response(JSON.stringify({message:'Compliance Copilot is temporarily unavailable.'}),{status:503});
  data={answer:'Review the source-of-funds evidence and record a decision before releasing the payment. This is synthetic QA content, not a real policy determination.',sources:body.question.toLowerCase().includes('no sources')?[]:[{policyDocumentId:pid,title:'Cross-border payment review',chunkNumber:1,excerpt:'Record the evidence and decision before releasing a payment.'}]};
 }else if(parts[1]==='routes')data={routes:[]};else if(parts[1]==='admin')data=[];else throw Error('QA blocked unexpected API request: '+parsed.pathname);
 return new Response(status===204?null:JSON.stringify({data}),{status,headers:{'Content-Type':'application/json'}});
};
require.config({baseUrl:'js',paths:{knockout:'libs/knockout/knockout-3.5.1.debug'}});
define('services/session',['knockout'],function(ko){const user=ko.observable({fullName:'QA Administrator',role:'ADMIN'});return {session:{user,isAdmin:ko.pureComputed(()=>user()?.role==='ADMIN'),restore:async()=>{}},navigate:()=>{}};});
require(['knockout','viewModels/admin'],function(ko,Admin){const page=new Admin({});ko.applyBindings(page,document.querySelector('main'));page.selectTab({id:'compliance'});window.addEventListener('pagehide',()=>page.disconnected());});
`;
fs.writeFileSync(path.join(stage,'compliance-preview.html'),`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FluxPay compliance · isolated QA</title>${styles}<style>body{margin:0;background:#f3f6fc}.qa-banner{padding:15px 24px;background:#18375b;color:#fff;font:12px Arial}.workspace-shell main{padding:30px;max-width:1400px;margin:auto}@media(max-width:600px){.workspace-shell main{padding:16px}}</style></head><body><div class="qa-banner">FluxPay / ISOLATED QA · Synthetic data · No real API writes</div><div class="workspace-shell admin-shell"><main>${html}</main></div><script src="js/libs/require/require.js"></script><script>${fixture}</script></body></html>`);
console.log('Generated web-dev/compliance-preview.html; all API requests are isolated in memory.');
