const API_BASE = (window as any).FLUXPAY_API_URL || 'http://localhost:8080';

type Envelope<T> = { data: T; message?: string; correlationId?: string };

class ApiClient {
  private token(): string { return sessionStorage.getItem('fluxpay.token') || ''; }
  async request<T>(path: string, options: RequestInit = {}, idempotent = false): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json', ...(options.headers as Record<string, string> || {}) };
    if (options.body) headers['Content-Type'] = 'application/json';
    if (this.token()) headers.Authorization = `Bearer ${this.token()}`;
    if (idempotent) headers['Idempotency-Key'] = crypto.randomUUID();
    const response = await fetch(`${API_BASE}${path}`, { ...options, headers });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.code || `Request failed (${response.status})`);
    return (body as Envelope<T>).data;
  }
  get<T>(path: string) { return this.request<T>(path); }
  post<T>(path: string, body?: unknown, idempotent = true) { return this.request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }, idempotent); }
  put<T>(path: string, body: unknown) { return this.request<T>(path, { method: 'PUT', body: JSON.stringify(body) }); }
}
export const api = new ApiClient();

export const flux = {
  login: (body: any) => api.post<any>('/api/auth/login', body, false),
  register: (body: any) => api.post<any>('/api/auth/register', body, false),
  me: () => api.get<any>('/api/users/me'), updateMe: (body: any) => api.put<any>('/api/users/me', body),
  kycStatus: () => api.get<any>('/api/kyc/my-status'), submitKyc: (body: any) => api.post<any>('/api/kyc/applications', body),
  wallets: () => api.get<any[]>('/api/wallets'), ledger: (id: string) => api.get<any>(`/api/wallets/${id}/ledger?page=0&size=20`),
  fund: (body: any) => api.post<any>('/api/wallets/receive-demo', body), convert: (body: any) => api.post<any>('/api/wallets/convert', body),
  rate: (from: string, to: string) => api.get<any>(`/api/fx/rate?from=${from}&to=${to}`),
  recipients: () => api.get<any[]>('/api/recipients'), createRecipient: (body: any) => api.post<any>('/api/recipients', body), updateRecipient: (id: string, body: any) => api.put<any>(`/api/recipients/${id}`, body),
  draft: (body: any) => api.post<any>('/api/payments/draft', body), quotes: (id: string) => api.post<any>(`/api/payments/${id}/quotes`), getQuotes: (id: string) => api.get<any>(`/api/payments/${id}/quotes`),
  confirm: (id: string, quoteId: string) => api.post<any>(`/api/payments/${id}/confirm`, { quoteId }), cancel: (id: string) => api.post<any>(`/api/payments/${id}/cancel`),
  payments: () => api.get<any>('/api/payments?page=0&size=50'), payment: (id: string) => api.get<any>(`/api/payments/${id}`), timeline: (id: string) => api.get<any[]>(`/api/payments/${id}/timeline`),
  routes: () => api.get<any>('/api/routes'), recommend: (id: string, preference: string) => api.post<any>(`/api/payments/${id}/recommend-route`, { preference }, false),
  payout: (id: string, routeCode: string) => api.post<any>(`/api/payments/${id}/submit-payout`, { routeCode }), retry: (id: string, quoteId: string) => api.post<any>(`/api/payments/${id}/retry-payout`, { quoteId }),
  switchRoute: (id: string, routeCode: string, quoteId: string) => api.post<any>(`/api/payments/${id}/switch-route`, { routeCode, quoteId }), refund: (id: string) => api.post<any>(`/api/payments/${id}/refund`),
  adminKyc: (status = 'PENDING') => api.get<any[]>(`/api/admin/kyc/applications?status=${status}&page=0&size=50`), approveKyc: (id: string, body: any) => api.put<any>(`/api/admin/kyc/applications/${id}/approve`, body), rejectKyc: (id: string, body: any) => api.put<any>(`/api/admin/kyc/applications/${id}/reject`, body),
  updateRoute: (id: string, body: any) => api.put<any>(`/api/admin/routes/${id}`, body)
};
