import axios from 'axios';
export const api = axios.create({ baseURL: '/api' });
api.interceptors.request.use((c) => {
  c.headers['X-Correlation-ID'] = crypto.randomUUID();
  const t = localStorage.getItem('jwt');
  if (t) c.headers.Authorization = `Bearer ${t}`;
  return c;
});
api.interceptors.response.use(
  (r) => r,
  (e) => {
    const err = e.response?.data;
    throw { code: err?.code ?? 'UNKNOWN', message: err?.message ?? e.message };
  },
);
