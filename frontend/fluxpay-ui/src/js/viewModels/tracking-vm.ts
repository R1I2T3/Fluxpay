import * as ko from 'knockout';
import { flux } from '../services/api-client';
export class TrackingViewModel {
  paymentId = ko.observable('');
  payment = ko.observable<any>();
  timeline = ko.observableArray<any>([]);
  quotes = ko.observableArray<any>([]);
  routes = ko.observableArray<any>([]);
  routeCode = ko.observable('HDFC_INR_STANDARD');
  quoteId = ko.observable('');
  preference = ko.observable('BALANCED');
  busy = ko.observable(false);
  error = ko.observable('');
  notice = ko.observable('');
  constructor() {
    void this.loadRoutes();
  }
  async loadRoutes() {
    try {
      this.routes((await flux.routes()).routes || []);
    } catch (e: any) {
      this.error(e.message);
    }
  }
  async inspect() {
    this.busy(true);
    try {
      const [p, t, q] = await Promise.all([
        flux.payment(this.paymentId()),
        flux.timeline(this.paymentId()),
        flux.getQuotes(this.paymentId()).catch(() => ({ quotes: [] })),
      ]);
      this.payment(p);
      this.timeline(t);
      this.quotes(q.quotes || []);
      if (q.quotes?.[0]) this.quoteId(q.quotes[0].id);
    } catch (e: any) {
      this.error(e.message);
    } finally {
      this.busy(false);
    }
  }
  async act(action: string) {
    try {
      let r: any;
      const id = this.paymentId();
      if (action === 'recommend') r = await flux.recommend(id, this.preference());
      else if (action === 'payout') r = await flux.payout(id, this.routeCode());
      else if (action === 'retry') r = await flux.retry(id, this.quoteId());
      else if (action === 'switch')
        r = await flux.switchRoute(id, this.routeCode(), this.quoteId());
      else if (action === 'refund') r = await flux.refund(id);
      else r = await flux.cancel(id);
      this.notice(`Operation successful: ${r.status || r.paymentId || 'updated'}`);
      await this.inspect();
    } catch (e: any) {
      this.error(e.message);
    }
  }
}
export default new TrackingViewModel();
