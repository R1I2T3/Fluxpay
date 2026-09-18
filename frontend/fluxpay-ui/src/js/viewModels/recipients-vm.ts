import * as ko from 'knockout';
import { flux } from '../services/api-client';
type Recipient = any; type RecipientInput = any;
export class RecipientsViewModel {
  recipients = ko.observableArray<Recipient>([]); busy = ko.observable(false); error = ko.observable(''); editing = ko.observable<Recipient | null>(null);
  form = { name: ko.observable(''), account: ko.observable(''), bankName: ko.observable(''), country: ko.observable('IN'), currency: ko.observable('INR'), status: ko.observable<'ACTIVE' | 'BLOCKED'>('ACTIVE') };
  constructor() { void this.load(); }
  async load() { this.busy(true); try { this.recipients(await flux.recipients()); } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
  openAdd = () => { this.editing(null); this.form.name(''); this.form.account(''); this.form.bankName(''); this.form.country('IN'); this.form.currency('INR'); this.form.status('ACTIVE'); };
  openEdit = (r: Recipient) => { this.editing(r); this.form.name(r.name); this.form.account(r.account); this.form.bankName(r.bankName); this.form.country(r.country); this.form.currency(r.currency); this.form.status(r.status); };
  async save() { const input: RecipientInput = { name: this.form.name(), account: this.form.account(), bankName: this.form.bankName(), country: this.form.country(), currency: this.form.currency(), status: this.form.status(), expectedVersion: this.editing()?.version }; if (!input.name || !input.account || !input.bankName) { this.error('Name, account and bank name are required.'); return; } this.busy(true); this.error(''); try { const current = this.editing(); if (current) await flux.updateRecipient(current.id, input); else await flux.createRecipient(input); await this.load(); } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
}
export default new RecipientsViewModel();
