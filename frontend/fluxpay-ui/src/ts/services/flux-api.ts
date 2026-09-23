const base = (window as any).FLUXPAY_API_URL || '';
const pending = new Map<string,string>();
const token = () => sessionStorage.getItem('fluxpay.token') || '';
// Only celebrate writes confirmed by the server. Ordinary history reads never trigger a popup.
let feedbackOwner = '';
const awaitedPayments = new Map<string,string>();
const celebrated = new Set<string>();
function transactionFeedback(path:string,method:string,body:any,data:any,key:string){
  if(!data||Array.isArray(data))return;
  let title='',description='',amount:any,currency='',identity=key;
  const payment=path.match(/^\/api\/payments\/([^/]+)(?:\/(submit-payout|retry-payout|switch-route|refund))?$/);
  if(payment){
    const id=payment[1],action=awaitedPayments.get(id);
    if(!action)return;
    if(['FAILED','REJECTED','CANCELLED'].includes(data.status)){
      awaitedPayments.delete(id);
      window.dispatchEvent(new CustomEvent('fluxpay:toast',{detail:{kind:'error',message:data.status==='FAILED'?'Your payment could not be completed. Check its activity receipt for the latest status and available next steps.':data.status==='REJECTED'?'Your payment was rejected. Open its activity receipt for details.':'This payment was cancelled. No further payout will be submitted.'}}));
      return;
    }
    if(data.status!==(action==='refund'?'REFUNDED':'COMPLETED'))return;
    awaitedPayments.delete(id);identity=id+':'+data.status;
    title=action==='refund'?'Money refunded':'Money sent';
    description=action==='refund'?'Your refund has been completed.':'Your transfer has been completed successfully.';
    amount=data.sourceAmount;currency=data.sourceCurrency;
  }else if(method==='POST'&&data.journalReference){
    identity=data.journalReference;
    if(path==='/api/wallets/convert'){
      title='Currency exchanged';description='Your exchanged money is ready in your wallet.';amount=data.creditedAmount;currency=data.to;
    }else if(path==='/api/wallets/transfer'){
      title='Wallet transfer complete';description='The money has been credited to their FluxPay wallet.';amount=data.creditedAmount;currency=data.toCurrency;
    }else if(path==='/api/wallets/withdraw'){
      title='Withdrawal recorded';description='Your withdrawal to your linked bank account has been recorded successfully.';amount=body.amount;currency=data.currency;
    }else if(path==='/api/wallets/receive-demo'||/^\/api\/bank-accounts\/[^/]+\/topup$/.test(path)){
      title='Money added';description='Your balance is topped up and ready to use.';amount=body.amount;currency=data.currency;
    }
  }
  if(!title||celebrated.has(identity))return;
  celebrated.add(identity);if(celebrated.size>200)celebrated.delete(celebrated.values().next().value!);
  const formatted=amount!=null&&Number.isFinite(Number(amount))&&/^[A-Z]{3}$/.test(currency||'')?new Intl.NumberFormat('en',{style:'currency',currency}).format(Number(amount)):'';
  window.dispatchEvent(new CustomEvent('fluxpay:transaction-success',{detail:{title,description,amount:formatted}}));
}
async function request<T>(path: string, method = 'GET', body?: unknown, idem = false): Promise<T> {
  const headers: Record<string,string> = { Accept: 'application/json' };
  const requestOwner=token();
  if(feedbackOwner!==requestOwner){feedbackOwner=requestOwner;awaitedPayments.clear();celebrated.clear();}
  const paymentAction=method==='POST'&&path.match(/^\/api\/payments\/([^/]+)\/(submit-payout|retry-payout|switch-route|refund)$/);
  if(paymentAction){awaitedPayments.set(paymentAction[1],paymentAction[2]);if(awaitedPayments.size>200)awaitedPayments.delete(awaitedPayments.keys().next().value!);}
  if (token()) headers.Authorization = `Bearer ${token()}`;
  const multipart = typeof FormData !== 'undefined' && body instanceof FormData;
  if (body !== undefined && !multipart) headers['Content-Type'] = 'application/json';
  const operation = method + path + JSON.stringify(body);
  if (idem) {
    if (!pending.has(operation)) pending.set(operation, crypto.randomUUID());
    headers['Idempotency-Key'] = pending.get(operation)!;
  }
  let response: Response;
  try { response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : multipart ? body as FormData : JSON.stringify(body) }); }
  catch { throw new Error('Unable to reach the payment service. Check your connection and try again.'); }
  const json = response.status === 204 ? {} : await response.json().catch(() => ({}));
  if (response.ok || response.status < 500) pending.delete(operation);
  if (!response.ok) {
    if (response.status===401 && !path.startsWith('/api/auth/')) {
      sessionStorage.removeItem('fluxpay.token');
      window.dispatchEvent(new Event('fluxpay:expired'));
    }
    const fields = Object.entries(json.fieldErrors||{}).map(([key,value])=>key+': '+value).join('; ');
    const message=(json.message || json.code || 'Request failed ('+response.status+')') + (fields ? ' — '+fields : '');
    throw new Error(path==='/api/wallets/receive-demo'?message.replace(/demo[_ ]?funding/gi,'Adding money').replace(/\bdemo\b\s*/gi,''):message);
  }
  // A presentation failure must never turn a completed payment into a retryable error.
  if(requestOwner&&requestOwner===token()&&response.status!==202){
    try{transactionFeedback(path,method,body,json.data,headers['Idempotency-Key']||operation);}catch{/* The receipt remains available in activity. */}
  }
  return json.data as T;
}
export async function streamCopilot(question:string,paymentId:string|undefined,onDelta:(text:string)=>void,signal:AbortSignal):Promise<void>{
  const response=await fetch(base+'/api/copilot/ask/stream',{method:'POST',headers:{Accept:'text/event-stream','Content-Type':'application/json',Authorization:'Bearer '+token()},body:JSON.stringify({question,...(paymentId?{paymentId}:{})}),signal});
  if(response.status===401){sessionStorage.removeItem('fluxpay.token');window.dispatchEvent(new Event('fluxpay:expired'));}
  if(!response.ok){const error=await response.json().catch(()=>({}));throw new Error(error.message||'The live response is unavailable. Try a cited answer.');}
  if(!response.body)throw new Error('Live responses are not supported by this connection.');
  const reader=response.body.getReader(),decoder=new TextDecoder();let buffer='',done=false;
  const consume=(frame:string)=>{
    const lines=frame.split('\n'),event=lines.find(l=>l.startsWith('event:'))?.slice(6).trim();
    const data=lines.filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n');if(!data)return;
    const value=JSON.parse(data);if(event==='done'||value.done){done=true;return;}if(typeof value.delta==='string')onDelta(value.delta);
  };
  try{
    while(!done){const chunk=await reader.read();if(signal.aborted)throw new DOMException('Cancelled','AbortError');if(chunk.done)break;buffer+=decoder.decode(chunk.value,{stream:true});buffer=buffer.replace(/\r\n/g,'\n');let index;while((index=buffer.indexOf('\n\n'))>=0){consume(buffer.slice(0,index));buffer=buffer.slice(index+2);}}
    if(!done)throw new Error('The live response was interrupted. The text shown may be incomplete.');
  }finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
export const fluxApi = {
  uploadKyc:(docType:string,docNumber:string,files:File[])=>{const body=new FormData();body.append('docType',docType);body.append('docNumber',docNumber);files.forEach(file=>body.append('files',file));return request<any>('/api/kyc/applications','POST',body);},
  kycDocument:async(id:string,signal?:AbortSignal)=>{
    const response=await fetch(base+`/api/kyc/documents/${encodeURIComponent(id)}/content`,{headers:{Authorization:'Bearer '+token()},cache:'no-store',signal});
    if(response.status===401){sessionStorage.removeItem('fluxpay.token');window.dispatchEvent(new Event('fluxpay:expired'));}
    if(!response.ok){const error=await response.json().catch(()=>({}));throw new Error(error.message||'This document is unavailable. Please contact support.');}
    const blob=await response.blob();if(!['application/pdf','image/jpeg','image/png'].includes(blob.type))throw new Error('This document format cannot be previewed.');return blob;
  },
  streamCopilot,
  get: <T>(p:string) => request<T>(p), post:<T>(p:string,b?:unknown,i=true)=>request<T>(p,'POST',b,i), put:<T>(p:string,b:unknown)=>request<T>(p,'PUT',b),
  login:(b:any)=>request<any>('/api/auth/login','POST',b), register:(b:any)=>request<any>('/api/auth/register','POST',b), me:()=>request<any>('/api/users/me'), updateMe:(b:any)=>request<any>('/api/users/me','PUT',b),
  kyc:()=>request<any>('/api/kyc/my-status'), submitKyc:(b:any)=>request<any>('/api/kyc/applications','POST',b),
  wallets:()=>request<any[]>('/api/wallets'), ledger:(id:string,page=0)=>request<any>(`/api/wallets/${id}/ledger?page=${page}&size=20`), fund:(b:any)=>request<any>('/api/wallets/receive-demo','POST',b,true), convert:(b:any)=>request<any>('/api/wallets/convert','POST',b,true), rate:(f:string,t:string)=>request<any>(`/api/fx/rate?from=${f}&to=${t}`),
  bankAccounts:()=>request<any[]>('/api/bank-accounts'),
  linkBank:(body:{bankName:string;accountLast4:string;currency:string})=>request<any>('/api/bank-accounts/link','POST',body,true),
  bankTopup:(id:string,body:{amount:string;note:string})=>request<any>(`/api/bank-accounts/${encodeURIComponent(id)}/topup`,'POST',body,true),
  withdraw:(body:{bankAccountId:string;currency:string;amount:string;note:string})=>request<any>('/api/wallets/withdraw','POST',body,true),
  walletTransfer:(body:{toEmail?:string;toUserId?:string;fromCurrency:string;toCurrency:string;amount:string;amountMode:string;note:string})=>request<any>('/api/wallets/transfer','POST',body,true),
  recipients:()=>request<any[]>('/api/recipients'), recipient:(b:any)=>request<any>('/api/recipients','POST',b,true), updateRecipient:(id:string,b:any)=>request<any>(`/api/recipients/${id}`,'PUT',b),
  payments:(page=0)=>request<any>(`/api/payments?page=${page}&size=20`), payment:(id:string)=>request<any>(`/api/payments/${id}`), draft:(b:any)=>request<any>('/api/payments/draft','POST',b,true), quotes:(id:string)=>request<any>(`/api/payments/${id}/quotes`,'POST',undefined,true), getQuotes:(id:string)=>request<any>(`/api/payments/${id}/quotes`), confirm:(id:string,q:string)=>request<any>(`/api/payments/${id}/confirm`,'POST',{quoteId:q},true), cancel:(id:string)=>request<any>(`/api/payments/${id}/cancel`,'POST',undefined,true), timeline:(id:string)=>request<any[]>(`/api/payments/${id}/timeline`),
  routes:()=>request<any>('/api/routes'), recommend:(id:string,p:string)=>request<any>(`/api/payments/${id}/recommend-route`,'POST',{preference:p}), payout:(id:string,r:string)=>request<any>(`/api/payments/${id}/submit-payout`,'POST',{routeCode:r},true), retry:(id:string,q:string)=>request<any>(`/api/payments/${id}/retry-payout`,'POST',{quoteId:q},true), switchRoute:(id:string,r:string,q:string)=>request<any>(`/api/payments/${id}/switch-route`,'POST',{routeCode:r,quoteId:q},true), refund:(id:string)=>request<any>(`/api/payments/${id}/refund`,'POST',undefined,true),
  adminKyc:(status='PENDING',page=0)=>request<any[]>(`/api/admin/kyc/applications?status=${status}&page=${page}&size=20`), approve:(id:string,b:any)=>request<any>(`/api/admin/kyc/applications/${id}/approve`,'PUT',b), reject:(id:string,b:any)=>request<any>(`/api/admin/kyc/applications/${id}/reject`,'PUT',b), updateRoute:(id:string,b:any)=>request<any>(`/api/admin/routes/${id}`,'PUT',b),
  policies:()=>request<PolicyDocument[]>('/api/policies'),
  policy:(id:string)=>request<PolicyDocument>(`/api/policies/${encodeURIComponent(id)}`),
  createPolicy:(body:{title:string;category:string;content:string})=>request<PolicyDocument>('/api/policies','POST',body),
  updatePolicy:(id:string,body:{title:string;category:string;content:string;clearExistingChunks:boolean})=>request<PolicyDocument>(`/api/policies/${encodeURIComponent(id)}`,'PUT',body),
  policyChunks:(id:string)=>request<PolicyChunk[]>(`/api/policies/${encodeURIComponent(id)}/chunks`),
  addPolicyChunk:(id:string,content:string)=>request<PolicyChunk>(`/api/policies/${encodeURIComponent(id)}/chunks`,'POST',{content}),
  updatePolicyChunk:(policyId:string,chunkId:string,content:string)=>request<PolicyChunk>(`/api/policies/${encodeURIComponent(policyId)}/chunks/${encodeURIComponent(chunkId)}`,'PUT',{content}),
  deletePolicyChunk:(policyId:string,chunkId:string)=>request<void>(`/api/policies/${encodeURIComponent(policyId)}/chunks/${encodeURIComponent(chunkId)}`,'DELETE'),
  policyGuidance:(id:string)=>request<PolicyGuidance[]>(`/api/policies/${encodeURIComponent(id)}/guidance`),
  addPolicyGuidance:(id:string,body:{complianceCaseId:string;content:string})=>request<PolicyGuidance>(`/api/policies/${encodeURIComponent(id)}/guidance`,'POST',body),
  updatePolicyGuidance:(policyId:string,guidanceId:string,content:string)=>request<PolicyGuidance>(`/api/policies/${encodeURIComponent(policyId)}/guidance/${encodeURIComponent(guidanceId)}`,'PUT',{content}),
  deletePolicyGuidance:(policyId:string,guidanceId:string)=>request<void>(`/api/policies/${encodeURIComponent(policyId)}/guidance/${encodeURIComponent(guidanceId)}`,'DELETE'),
  indexPolicy:(id:string)=>request<{policyDocumentId:string;chunkCount:number}>(`/api/policies/${encodeURIComponent(id)}/index`,'POST'),
  deletePolicy:(id:string)=>request<void>(`/api/policies/${encodeURIComponent(id)}`,'DELETE'),
  complianceCases:(status='ALL')=>request<ComplianceCase[]>('/api/compliance/cases'+(status==='ALL'?'':'?status='+encodeURIComponent(status))),
  complianceCase:(id:string)=>request<ComplianceCase>(`/api/compliance/cases/${encodeURIComponent(id)}`),
  createComplianceCase:(body:{paymentId:string;risk:string;riskReasons:string[];suggestedAction:string})=>request<ComplianceCase>('/api/compliance/cases','POST',body),
  decideComplianceCase:(id:string,decision:'approve'|'reject',decisionReason:string)=>request<ComplianceCase>(`/api/compliance/cases/${encodeURIComponent(id)}/${decision}`,'PUT',{decisionReason}),
  deleteComplianceCase:(id:string)=>request<void>(`/api/compliance/cases/${encodeURIComponent(id)}`,'DELETE'),
  askCopilot:(question:string,paymentId?:string)=>request<CopilotAnswer>('/api/copilot/ask','POST',{question,...(paymentId?{paymentId}:{})})
};

export interface PolicyChunk { id:string; policyDocumentId:string; chunkNumber:number; content:string; manual:boolean; createdAt:string; }
export interface PolicyGuidance { id:string; policyDocumentId:string; complianceCaseId:string; content:string; createdAt:string; updatedAt:string; }
export interface PolicyDocument { id:string; title:string; category:string; content:string; documentHash:string; createdAt:string; chunks:PolicyChunk[]; }
export interface ComplianceCase { id:string; paymentId:string; reviewReference:string|null; reviewExpiresAt:string|null; requoteRequired:boolean; risk:string; status:string; riskReasons:string[]; suggestedAction:string; decidedBy:string|null; decidedAt:string|null; decisionReason:string|null; createdAt:string; }
export interface CopilotAnswer { answer:string; sources:{policyDocumentId:string;title:string;chunkNumber:number;excerpt:string}[]; }
