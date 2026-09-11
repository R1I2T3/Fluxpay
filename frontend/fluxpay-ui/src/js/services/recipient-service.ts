import { api } from './api-client';

export type Recipient = { id: string; name: string; account: string; bankName: string; country: string; currency: string; status: 'ACTIVE' | 'BLOCKED'; version: number };
export type RecipientInput = Omit<Recipient, 'id' | 'version'> & { expectedVersion?: number };
export const recipientService = {
  async list(): Promise<Recipient[]> { return (await api.get('/recipients')).data.data; },
  async create(input: RecipientInput): Promise<Recipient> { return (await api.post('/recipients', input)).data.data; },
  async update(id: string, input: RecipientInput): Promise<Recipient> { return (await api.put(`/recipients/${id}`, input)).data.data; },
};
