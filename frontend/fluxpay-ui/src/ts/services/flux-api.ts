const base = (window as any).FLUXPAY_API_URL || '';
const pending = new Map<string,string>();
const token = () => sessionStorage.getItem('fluxpay.token') || '';
async function request<T>(path: string, method = 'GET', body?: unknown, idem = false): Promise<T> {
  const headers: Record<string,string> = { Accept: 'application/json' };
  if (token()) headers.Authorization = `Bearer ${token()}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const operation = method + path + JSON.stringify(body);
  if (idem) {
    if (!pending.has(operation)) pending.set(operation, crypto.randomUUID());
    headers['Idempotency-Key'] = pending.get(operation)!;
  }
  let response: Response;
  try { response = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }); }
  catch { throw new Error('Unable to reach the payment service. Check your connection and try again.'); }
  const json = response.status === 204 ? {} : await response.json().catch(() => ({}));
  if (response.ok || response.status < 500) pending.delete(operation);
  if (!response.ok) {
    if (response.status===401 && !path.startsWith('/api/auth/')) {
      sessionStorage.removeItem('fluxpay.token');
      window.dispatchEvent(new Event('fluxpay:expired'));
    }
    const fields = Object.entries(json.fieldErrors||{}).map(([key,value])=>key+': '+value).join('; ');
    throw new Error((json.message || json.code || 'Request failed ('+response.status+')') + (fields ? ' — '+fields : ''));
  }
  return json.data as T;
}
export const fluxApi = {
  get: <T>(p:string) => request<T>(p), post:<T>(p:string,b?:unknown,i=true)=>request<T>(p,'POST',b,i), put:<T>(p:string,b:unknown)=>request<T>(p,'PUT',b),
  login:(b:any)=>request<any>('/api/auth/login','POST',b), register:(b:any)=>request<any>('/api/auth/register','POST',b), me:()=>request<any>('/api/users/me'), updateMe:(b:any)=>request<any>('/api/users/me','PUT',b),
  kyc:()=>request<any>('/api/kyc/my-status'), submitKyc:(b:any)=>request<any>('/api/kyc/applications','POST',b),
  wallets:()=>request<any[]>('/api/wallets'), ledger:(id:string,page=0)=>request<any>(`/api/wallets/${id}/ledger?page=${page}&size=20`), fund:(b:any)=>request<any>('/api/wallets/receive-demo','POST',b,true), convert:(b:any)=>request<any>('/api/wallets/convert','POST',b,true), rate:(f:string,t:string)=>request<any>(`/api/fx/rate?from=${f}&to=${t}`),
  recipients:()=>request<any[]>('/api/recipients'), recipient:(b:any)=>request<any>('/api/recipients','POST',b,true), updateRecipient:(id:string,b:any)=>request<any>(`/api/recipients/${id}`,'PUT',b),
  payments:(page=0)=>request<any>(`/api/payments?page=${page}&size=20`), payment:(id:string)=>request<any>(`/api/payments/${id}`), draft:(b:any)=>request<any>('/api/payments/draft','POST',b,true), quotes:(id:string)=>request<any>(`/api/payments/${id}/quotes`,'POST',undefined,true), getQuotes:(id:string)=>request<any>(`/api/payments/${id}/quotes`), confirm:(id:string,q:string)=>request<any>(`/api/payments/${id}/confirm`,'POST',{quoteId:q},true), cancel:(id:string)=>request<any>(`/api/payments/${id}/cancel`,'POST',undefined,true), timeline:(id:string)=>request<any[]>(`/api/payments/${id}/timeline`),
  routes:()=>request<any>('/api/routes'), recommend:(id:string,p:string)=>request<any>(`/api/payments/${id}/recommend-route`,'POST',{preference:p}), payout:(id:string,r:string)=>request<any>(`/api/payments/${id}/submit-payout`,'POST',{routeCode:r},true), retry:(id:string,q:string)=>request<any>(`/api/payments/${id}/retry-payout`,'POST',{quoteId:q},true), switchRoute:(id:string,r:string,q:string)=>request<any>(`/api/payments/${id}/switch-route`,'POST',{routeCode:r,quoteId:q},true), refund:(id:string)=>request<any>(`/api/payments/${id}/refund`,'POST',undefined,true),
  adminKyc:(status='PENDING',page=0)=>request<any[]>(`/api/admin/kyc/applications?status=${status}&page=${page}&size=20`), approve:(id:string,b:any)=>request<any>(`/api/admin/kyc/applications/${id}/approve`,'PUT',b), reject:(id:string,b:any)=>request<any>(`/api/admin/kyc/applications/${id}/reject`,'PUT',b), updateRoute:(id:string,b:any)=>request<any>(`/api/admin/routes/${id}`,'PUT',b),
  policies:()=>request<PolicyDocument[]>('/api/policies'),
  policy:(id:string)=>request<PolicyDocument>(`/api/policies/${encodeURIComponent(id)}`),
  createPolicy:(body:{title:string;category:string;content:string})=>request<PolicyDocument>('/api/policies','POST',body),
  policyChunks:(id:string)=>request<PolicyChunk[]>(`/api/policies/${encodeURIComponent(id)}/chunks`),
  addPolicyChunk:(id:string,content:string)=>request<PolicyChunk>(`/api/policies/${encodeURIComponent(id)}/chunks`,'POST',{content}),
  indexPolicy:(id:string)=>request<{policyDocumentId:string;chunkCount:number}>(`/api/policies/${encodeURIComponent(id)}/index`,'POST'),
  deletePolicy:(id:string)=>request<void>(`/api/policies/${encodeURIComponent(id)}`,'DELETE'),
  complianceCases:(status='ALL')=>request<ComplianceCase[]>('/api/compliance/cases'+(status==='ALL'?'':'?status='+encodeURIComponent(status))),
  complianceCase:(id:string)=>request<ComplianceCase>(`/api/compliance/cases/${encodeURIComponent(id)}`),
  createComplianceCase:(body:{paymentId:string;risk:string;riskReasons:string[];suggestedAction:string})=>request<ComplianceCase>('/api/compliance/cases','POST',body),
  decideComplianceCase:(id:string,decision:'approve'|'reject',decisionReason:string)=>request<ComplianceCase>(`/api/compliance/cases/${encodeURIComponent(id)}/${decision}`,'PUT',{decisionReason}),
  deleteComplianceCase:(id:string)=>request<void>(`/api/compliance/cases/${encodeURIComponent(id)}`,'DELETE'),
  askCopilot:(question:string,paymentId?:string)=>request<CopilotAnswer>('/api/copilot/ask','POST',{question,...(paymentId?{paymentId}:{})})
};

export interface PolicyChunk { id:string; policyDocumentId:string; chunkNumber:number; content:string; createdAt:string; }
export interface PolicyDocument { id:string; title:string; category:string; content:string; documentHash:string; createdAt:string; chunks:PolicyChunk[]; }
export interface ComplianceCase { id:string; paymentId:string; reviewReference:string|null; risk:string; status:string; riskReasons:string[]; suggestedAction:string; decidedBy:string|null; decidedAt:string|null; decisionReason:string|null; createdAt:string; }
export interface CopilotAnswer { answer:string; sources:{policyDocumentId:string;title:string;chunkNumber:number;excerpt:string}[]; }

// PLAN1-ANCHOR-TICKETS-EOF: do not move; Plan 5 never edits below
export const ticketApi = {
  list: (page = 0, size = 20) => fluxApi.get<any>(`/api/tickets?page=${page}&size=${size}`),
  create: (body:{paymentId?:string;subject:string;body:string}) => fluxApi.post<any>('/api/tickets',body,true),
  listForAdmin: (status = 'ALL', page = 0, size = 20) => fluxApi.get<any>(`/api/admin/tickets?status=${encodeURIComponent(status)}&page=${page}&size=${size}`),
  updateForAdmin: (id:string, body:{status:string;assigneeAdminId?:string}) => fluxApi.put<any>(`/api/admin/tickets/${encodeURIComponent(id)}`,body)
};
