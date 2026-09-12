import * as ko from 'knockout';
import { paymentService } from '../services/payment-service';
export class PaymentsListViewModel {
  items = ko.observableArray<any>([]); busy = ko.observable(false); error = ko.observable(''); page = ko.observable(0); total = ko.observable(0);
  constructor() { void this.load(); }
  async load(page = this.page()) { this.busy(true); try { const response = await paymentService.list(page); this.items(response.items); this.page(response.page); this.total(response.total); } catch (e: any) { this.error(e.message); } finally { this.busy(false); } }
}
