// Isolated browser QA: actual JET shell, router, API client and views; in-memory API only.
// Generated files are ignored build output. Never import this fixture in production.
const fs=require('node:fs'),path=require('node:path');
const stage=path.join(__dirname,'../web-dev');
const fixture=String.raw`
(()=>{
 window.require={urlArgs:'qa='+Date.now()};
 let token='isolated-qa-token';
 Object.defineProperty(window,'sessionStorage',{value:{getItem:key=>key==='fluxpay.token'?token:null,setItem:(_,value)=>{token=value;},removeItem:()=>{token='';}}});
 const createdAt='2026-09-18T09:00:00Z';
 const user={fullName:'Alex Morgan',email:'alex@example.test',role:new URLSearchParams(location.search).get('qa_role')==='admin'?'ADMIN':'CUSTOMER',kycStatus:new URLSearchParams(location.search).get('qa_kyc')||'VERIFIED'};
 const wallets=[{walletId:'w-usd',currency:'USD',availableBalance:'6012.50',heldBalance:'120'},{walletId:'w-eur',currency:'EUR',availableBalance:'2840',heldBalance:'0'},{walletId:'w-inr',currency:'INR',availableBalance:'48350',heldBalance:'0'}];
 const banks=[];
 let recipients=[{id:'r-1',name:'Jamie Taylor',bankName:'HDFC Bank',account:'TEST12345678',country:'IN',currency:'INR',status:'ACTIVE',version:0},{id:'r-2',name:'Sam Rivera',bankName:'Deutsche Bank',account:'TEST87654321',country:'DE',currency:'EUR',status:'ACTIVE',version:0}];
 let payments=['COMPLETED','PROCESSING','UNDER_REVIEW','DRAFT'].map((status,i)=>({id:'11111111-1111-4111-8111-11111111111'+i,sourceWalletId:'w-usd',recipientId:'r-1',sourceAmount:String(125+i*20),sourceCurrency:'USD',payoutCurrency:'INR',status,createdAt,selectedQuoteId:'q-1'}));
 const entries=[{entryId:'e-1',entryType:'CREDIT',currency:'USD',amount:'500',journalReference:'wallet:demo:qa',narration:'Development wallet funding',createdAt},{entryId:'e-2',entryType:'DEBIT',currency:'USD',amount:'50',journalReference:'wallet:fx:qa',narration:'Exchange to EUR',createdAt}];
 entries.unshift({entryId:'e-3',entry_type:'WALLET_TO_WALLET',entryType:'DEBIT',currency:'USD',amount:'25',narration:'Synthetic wallet transfer',createdAt:new Date().toISOString()});
 let kyc={applicationId:'kyc-qa',version:1,status:user.kycStatus,fullName:'Alex Morgan',email:'alex@example.test',docType:'PASSPORT',docNumber:'SYNTHETIC-QA-ONLY',submittedAt:createdAt,decidedAt:user.kycStatus==='PENDING'?null:createdAt,rejectReason:user.kycStatus==='REJECTED'?'Please provide a clear copy with all corners visible.':'',documents:user.kycStatus==='NONE'?[]:[{id:'qa-document',fileName:'Synthetic-preview.png',fileType:'image/png',fileSize:1024,uploadedAt:createdAt,available:true}]};
 const quotes=()=>({expiresAt:new Date(Date.now()+300000).toISOString(),quotes:[{id:'q-1',routeCode:'BANK_TRANSFER',recommended:true,feeAmount:'1.50',offeredRate:'83.8',recipientAmount:'10349.30',estimatedMinutes:30},{id:'q-2',routeCode:'EXPRESS_TRANSFER',recommended:false,feeAmount:'3.00',offeredRate:'83.6',recipientAmount:'10199.20',estimatedMinutes:10}].map(quote=>{if(new URLSearchParams(location.search).get('qa_legacy_routes')==='true'){const {routeCode,...fields}=quote;return {...fields,route:routeCode};}return quote;})});
 if(new URLSearchParams(location.search).get('qa_pdf')==='true'){
   const text='BT /F1 24 Tf 50 700 Td (SYNTHETIC DOCUMENT - QA ONLY) Tj ET';
   const objects=['<</Type /Catalog /Pages 2 0 R>>','<</Type /Pages /Kids [3 0 R] /Count 1>>','<</Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources <</Font <</F1 4 0 R>>>> /Contents 5 0 R>>','<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>','<</Length '+text.length+'>>\nstream\n'+text+'\nendstream'];
   let pdf='%PDF-1.4\n',offsets=[0];objects.forEach((object,index)=>{offsets.push(pdf.length);pdf+=(index+1)+' 0 obj\n'+object+'\nendobj\n';});const xref=pdf.length;pdf+='xref\n0 6\n0000000000 65535 f \n'+offsets.slice(1).map(offset=>String(offset).padStart(10,'0')+' 00000 n \n').join('')+'trailer\n<</Size 6 /Root 1 0 R>>\nstartxref\n'+xref+'\n%%EOF';
   kyc.documents=[{id:'qa-pdf',fileName:'Synthetic-review.pdf',fileType:'application/pdf',fileSize:pdf.length,available:true,uploadedAt:createdAt,file:new Blob([pdf],{type:'application/pdf'})}];
 }
 const nativeFetch=window.fetch.bind(window);
 window.fetch=async(url,options={})=>{
   const parsed=new URL(String(url),location.href);
   if(!parsed.pathname.startsWith('/api/'))return nativeFetch(url,options);
   const p=parsed.pathname,method=options.method||'GET',body=options.body instanceof FormData?Object.fromEntries(options.body):options.body?JSON.parse(options.body):{};
   let data,status=200;
   if(!token)return new Response(JSON.stringify({message:'QA session expired'}),{status:401});
   if(p==='/api/users/me')data=user;
   else if(p==='/api/wallets')data=wallets;
   else if(p==='/api/bank-accounts')data=banks;
   else if(p==='/api/bank-accounts/link'){data={id:crypto.randomUUID(),...body,status:'VERIFIED'};banks.push(data);}
   else if(p.startsWith('/api/bank-accounts/')&&p.endsWith('/topup')){const bank=banks.find(b=>b.id===p.split('/')[3]),wallet=wallets.find(w=>w.currency===bank.currency);wallet.availableBalance=String(Number(wallet.availableBalance)+Number(body.amount));entries.unshift({entryId:crypto.randomUUID(),currency:bank.currency,entryType:'CREDIT',amount:body.amount,journalReference:'wallet:topup:'+crypto.randomUUID(),narration:body.note,createdAt:new Date().toISOString()});data={...wallet,journalReference:entries[0].journalReference};}
   else if(p==='/api/wallets/withdraw'){const wallet=wallets.find(w=>w.currency===body.currency);wallet.availableBalance=String(Number(wallet.availableBalance)-Number(body.amount));entries.unshift({entryId:crypto.randomUUID(),currency:body.currency,entryType:'DEBIT',amount:body.amount,journalReference:'wallet:withdraw:'+crypto.randomUUID(),narration:body.note,createdAt:new Date().toISOString()});data={...wallet,journalReference:entries[0].journalReference};}
   else if(p==='/api/wallets/transfer'){const wallet=wallets.find(w=>w.currency===body.fromCurrency);const amount=body.amountMode==='SOURCE'?body.amount:'2.00';wallet.availableBalance=String(Number(wallet.availableBalance)-Number(amount));const journalReference='wallet:p2p:'+crypto.randomUUID();entries.unshift({entryId:crypto.randomUUID(),currency:body.fromCurrency,entryType:'DEBIT',amount,journalReference,narration:body.note,createdAt:new Date().toISOString()});data={sourceWalletId:wallet.walletId,fromCurrency:body.fromCurrency,toCurrency:body.toCurrency,sourceAmount:amount,creditedAmount:body.amountMode==='TARGET'?body.amount:'800.00',fee:'0.10',rate:'80',journalReference};}
   else if(p.endsWith('/ledger')){const wallet=wallets.find(w=>p.includes('/'+w.walletId+'/'));const rows=entries.filter(e=>e.currency===wallet?.currency);data={entries:rows,totalElements:rows.length};}
   else if(p==='/api/wallets/receive-demo'){const wallet=wallets.find(w=>w.currency===body.currency);wallet.availableBalance=String(Number(wallet.availableBalance)+Number(body.amount));entries.unshift({entryId:crypto.randomUUID(),entryType:'CREDIT',currency:body.currency,amount:body.amount,journalReference:'wallet:demo:'+crypto.randomUUID(),narration:'QA top-up',createdAt:new Date().toISOString()});data={...wallet,journalReference:entries[0].journalReference};}
   else if(p==='/api/wallets/convert')data={creditedAmount:'83.50',to:body.to,from:body.from,fee:'0.50',journalReference:'wallet:fx:'+crypto.randomUUID()};
   else if(p==='/api/fx/rate')data={from:parsed.searchParams.get('from'),to:parsed.searchParams.get('to'),rate:'0.835',fetchedAt:createdAt,stale:false};
   else if(p==='/api/recipients'){if(method==='POST'){data={...body,id:crypto.randomUUID(),status:'ACTIVE'};recipients.push(data);}else data=recipients;}
   else if(p.startsWith('/api/recipients/')){const id=p.split('/').pop();if(method==='DELETE'){recipients=recipients.filter(r=>r.id!==id);status=204;}else{data=Object.assign(recipients.find(r=>r.id===id),body);}}
   else if(p==='/api/payments')data={items:payments,total:payments.length};
   else if(p==='/api/payments/draft'){data={...body,id:crypto.randomUUID(),createdAt:new Date().toISOString(),status:'DRAFT'};payments.unshift(data);}
   else if(p.startsWith('/api/payments/')){
     const parts=p.split('/'),payment=payments.find(p=>p.id===parts[3]);
     if(parts[4]==='quotes'){if(method==='POST'&&payment)payment.status='QUOTED';data=quotes();}
     else if(parts[4]==='timeline'){
       const steps=[['payment.initiated',0,'Payment request recorded'],['payment.screening.completed',8,'Screening result recorded'],['payment.route.selected',11,'Bank transfer route selected']];
       if(payment?.status==='UNDER_REVIEW')steps.push(['payment.review.requested',14,'A compliance review is required']);
       if(payment?.status==='COMPLETED')steps.push(['payout.submitted',18,'Submitted to the payout provider']);
       if(payment?.status==='COMPLETED')steps.push(['payout.completed',95,'Provider confirmed delivery']);
       data=steps.map(([eventType,seconds,summary],index)=>({eventId:'qa-event-'+index,paymentId:payment.id,eventType,occurredAt:new Date(Date.parse(createdAt)+seconds*1000).toISOString(),correlationId:'qa-correlation',kafkaTopic:eventType,payload:{summary,providerRef:eventType==='payout.completed'?'QA-RECEIPT-ONLY':null}}));
     }
     else if(parts[4]==='confirm'){payment.status=new URLSearchParams(location.search).get('qa_review')==='true'?'UNDER_REVIEW':'PROCESSING';payment.selectedQuoteId=body.quoteId;data=payment;}
     else if(parts[4]==='submit-payout'){if(typeof body.routeCode!=='string'||!body.routeCode.trim())return new Response(JSON.stringify({message:'routeCode must not be blank'}),{status:400});payment.status='COMPLETED';data={status:'COMPLETED',routeCode:body.routeCode,providerRef:'SYNTHETIC-QA-ONLY',allowed:[]};}
     else if(parts[4]==='cancel'){payment.status='CANCELLED';data=payment;}
     else if(parts[4]==='recommend-route')data={recommendationReason:'Synthetic QA route comparison',quotes:[]};
     else if(!parts[4])data=payment;
     else return new Response(JSON.stringify({message:'This action is disabled in isolated QA.'}),{status:503});
   }
   else if(p==='/api/kyc/my-status')data=kyc;
   else if(p==='/api/kyc/applications'){kyc={...kyc,status:'PENDING',decidedAt:null,submittedAt:new Date().toISOString(),documents:options.body.getAll('files').map((file,i)=>({id:'qa-file-'+i,fileName:file.name,fileType:file.type,fileSize:file.size,available:true,uploadedAt:new Date().toISOString(),file}))};user.kycStatus='PENDING';data=kyc;}
   else if(p.startsWith('/api/kyc/documents/')){const id=p.split('/')[4],doc=kyc.documents.find(d=>d.id===id);if(doc?.file)return new Response(doc.file,{headers:{'Content-Type':doc.file.type}});return nativeFetch('css/images/fluxpay-hero.png');}
   else if(p==='/api/admin/kyc/applications')data=parsed.searchParams.get('status')==='ALL'||parsed.searchParams.get('status')===kyc.status?[kyc]:[];
   else if(p.startsWith('/api/admin/kyc/applications/')){kyc={...kyc,status:p.endsWith('/approve')?'VERIFIED':'REJECTED',rejectReason:p.endsWith('/reject')?body.reason:'',decidedAt:new Date().toISOString(),version:kyc.version+1};user.kycStatus=kyc.status;data=kyc;}
   else if(p==='/api/routes')data={routes:[]};
   else if(p.startsWith('/api/admin/')||p==='/api/policies'||p.startsWith('/api/compliance/'))data=[];
   else return new Response(JSON.stringify({message:'QA blocked unexpected API request.'}),{status:503});
   return new Response(status===204?null:JSON.stringify({data}),{status,headers:{'Content-Type':'application/json'}});
 };
 document.addEventListener('DOMContentLoaded',()=>{const badge=document.createElement('span');badge.textContent='ISOLATED QA · Synthetic data';badge.style.cssText='position:fixed;right:6px;bottom:88px;z-index:200;font:9px Arial;background:#eaf3ff;color:#1d5689;border-radius:8px;padding:5px;pointer-events:none';document.body.appendChild(badge);});
})();
`;
const index=fs.readFileSync(path.join(stage,'index.html'),'utf8');
const html=index.replace('<title>FluxPay — Money without borders</title>','<title>FluxPay · isolated experience QA</title>').replace('<!-- injector:scripts -->','<script>'+fixture+'</script>\n<!-- injector:scripts -->');
for(const name of ['experience-preview.html','design-preview.html'])fs.writeFileSync(path.join(stage,name),html);
console.log('Generated isolated experience-preview.html and design-preview.html. API calls stay in memory.');
