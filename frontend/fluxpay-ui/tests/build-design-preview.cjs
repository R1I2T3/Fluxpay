// Isolated visual QA only. Run after a dev build, then open /design-preview.html.
// Uses real templates and Page observables, with ALL API calls replaced by rejecting stubs.
// Never imports the real session or API module and never writes a token or real user data.
const fs = require('node:fs');
const path = require('node:path');
const stage = path.join(__dirname, '../web-dev');
const index = fs.readFileSync(path.join(stage, 'index.html'), 'utf8');
const styles = [...index.matchAll(/<link[^>]+rel="stylesheet"[^>]*>/g)].map(m => m[0]).join('\n');
const header = index.match(/<header class="site-header">[\s\S]*?<\/header>/)[0];
const sidebar = index.match(/<aside class="sidebar"[\s\S]*?<\/aside>/)[0];
const mobileNav = index.match(/<nav id="mobile-navigation"[\s\S]*?<\/nav>/)[0];
const output = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FluxPay · isolated design preview</title>${styles}
<style>.qa-label{position:fixed;right:14px;bottom:10px;z-index:110;background:#fff;border:1px solid #d5e3f5;border-radius:999px;color:#40658f;padding:7px 12px;font:10px Arial;pointer-events:none}</style></head><body>
<div class="qa-label">DESIGN PREVIEW · SYNTHETIC DATA · API DISABLED</div>
<div class="workspace-shell" data-bind="css:{'admin-shell':isAdminWorkspace}">${header}${mobileNav}${sidebar}<main id="main" data-bind="with:page"></main></div>
<script src="js/libs/require/require.js"></script><script>
require.config({baseUrl:'js',paths:{knockout:'libs/knockout/knockout-3.5.1.debug'}});
define('services/flux-api',[],function(){return {fluxApi:new Proxy({}, {get:function(){return async function(){throw new Error('Design preview: service actions are disabled.');};}})};});
define('services/session',['knockout'],function(ko){return {session:{user:ko.observable({fullName:'Alex Morgan',email:'alex@example.test',role:'ADMIN',kycStatus:'VERIFIED'}),isAdmin:ko.observable(true),restore:async function(){},set:function(){throw new Error('Authentication is disabled in design preview');}},navigate:function(route){location.href='design-preview.html?screen='+encodeURIComponent(route);}};});
require(['knockout','services/page','services/session','viewModels/admin'],function(ko,module,sessionModule,Admin){
 (async function(){
  const screens=['dashboard','wallets','payments-new','payments-list','recipients','tracking','kyc','account','admin'];
  const screen=new URLSearchParams(location.search).get('screen')||'dashboard';
  if(!screens.includes(screen))throw new Error('Unknown preview screen');
  const page=screen==='admin'?new Admin({}):new module.Page('home'); // All API calls remain rejecting stubs.
  page.screen=screen;
  sessionModule.session.user().role=screen==='admin'?'ADMIN':'CUSTOMER';
  sessionModule.session.isAdmin(screen==='admin');
  const now='2026-09-16T10:00:00Z';
  page.wallets([{walletId:'wallet-usd',currency:'USD',availableBalance:6012.50,heldBalance:120},{walletId:'wallet-eur',currency:'EUR',availableBalance:2840,heldBalance:0},{walletId:'wallet-inr',currency:'INR',availableBalance:48350,heldBalance:0}]);
  page.recipients([{id:'r-1',name:'Jamie Taylor',currency:'INR',country:'IN',bankName:'Example Bank',account:'TEST12345678',status:'ACTIVE'},{id:'r-2',name:'Sam Rivera',currency:'EUR',country:'DE',bankName:'Demo Bank',account:'TEST87654321',status:'ACTIVE'},{id:'r-3',name:'Casey Lee',currency:'USD',country:'US',bankName:'Sample Bank',account:'TEST10203040',status:'BLOCKED'}]);
  page.payments(['COMPLETED','PROCESSING','FAILED','DRAFT'].map(function(status,i){return {id:'preview-payment-'+i,recipientId:'r-'+(i%2+1),sourceAmount:[245,120,350,80][i],sourceCurrency:'USD',payoutCurrency:i%2?'EUR':'INR',status:status,createdAt:now};}));
  page.total(4);page.walletId('wallet-usd');page.recipientId('r-1');
  page.fullName('Alex Morgan');page.email('alex@example.test');
  page.kyc({status:'VERIFIED',submittedAt:now,decidedAt:now,rejectReason:''});
  page.cases([{id:'case-1',fullName:'Jordan Park',email:'jordan@example.test',docType:'PASSPORT',docNumber:'PREVIEW-ONLY',submittedAt:now,status:'PENDING',documents:[]}]);
  page.routes([{routeName:'Bank transfer',providerName:'Example provider',baseFee:'1.50',fxSpreadPercentage:'0.3',estimatedMinutes:30,successRate:98,active:true},{routeName:'Express transfer',providerName:'Demo provider',baseFee:'3.00',fxSpreadPercentage:'0.5',estimatedMinutes:10,successRate:99,active:true}]);
  if(screen==='tracking'){page.payment(page.payments()[0]);page.paymentId(page.payment().id);page.timeline([{eventType:'payment.created',occurredAt:now},{eventType:'payment.confirmed',occurredAt:now},{eventType:'payment.completed',occurredAt:now}]);}
  const state=new URLSearchParams(location.search).get('state');
  if(state==='empty'){page.payments([]);page.total(0);page.recipients([]);page.cases([]);}
  if(state==='quotes'){page.step(2);page.payment(page.payments()[0]);page.quotes([{id:'q-1',recommended:true,route:'BANK_TRANSFER',recipientAmount:20540,feeAmount:1.5,offeredRate:83.8,estimatedMinutes:30},{id:'q-2',recommended:false,route:'EXPRESS_TRANSFER',recipientAmount:20415,feeAmount:3,offeredRate:83.6,estimatedMinutes:10}]);page.quoteExpires(new Date(Date.now()+300000).toISOString());}
  if(state==='recipient')page.addRecipient();
  if(state==='review'){page.review(page.cases()[0]);}
  const nav=[['dashboard','Overview','◫'],['wallets','Wallets & exchange','◉'],['payments-new','Send money','↗'],['payments-list','Transactions','⇄'],['recipients','Recipients','◎'],['tracking','Track a transfer','⌁'],['kyc','Verification','◇'],['account','My account','○'],['admin','Administration','⊞']].map(function(n){return {path:n[0],label:n[1],icon:n[2]};});
  const root={page:page,session:sessionModule.session,selection:{path:ko.observable(screen)},isPublic:ko.observable(false),isHome:ko.observable(false),isAdminWorkspace:ko.observable(screen==='admin'),accountPath:ko.observable(screen==='admin'?'admin':'dashboard'),menuOpen:ko.observable(false),visibleNav:ko.observableArray(nav.filter(function(n){return screen==='admin'?n.path==='admin':n.path!=='admin';})),logout:function(){},toggleMenu:function(){this.menuOpen(!this.menuOpen());}};
  const html=await fetch('js/views/'+screen+'.html').then(function(r){if(!r.ok)throw new Error('Template missing');return r.text();});
  document.querySelector('main').innerHTML=html;
  ko.applyBindings(root,document.querySelector('.workspace-shell'));
  document.addEventListener('click',function(event){const a=event.target.closest('a[data-route]');if(a){event.preventDefault();if(screens.includes(a.dataset.route))location.href='design-preview.html?screen='+a.dataset.route;}});
  window.addEventListener('pagehide',function(){page.disconnected();});
 })().catch(function(error){console.error(error);});
});
</script></body></html>`;
fs.writeFileSync(path.join(stage, 'design-preview.html'), output);
console.log('Generated isolated design-preview.html in ignored web-dev output. API access is disabled.');
