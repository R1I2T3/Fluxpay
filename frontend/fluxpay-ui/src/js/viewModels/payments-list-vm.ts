import * as ko from 'knockout';
import { flux } from '../services/api-client';
export class PaymentsListViewModel {
  items = ko.observableArray<any>([]); busy = ko.observable(false); error = ko.observable(''); page = ko.observable(0); total = ko.observable(0);
  constructor() { void this.load(); }
  async load(page = this.page()) { this.busy(true); try { const response = await flux.payments(); this.items(response.items); this.page(response.page); this.total(response.total); } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
}
export default new PaymentsListViewModel();
