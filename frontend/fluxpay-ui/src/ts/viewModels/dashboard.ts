import { Page } from '../services/page';
import { navigate } from '../services/session';
import * as ko from 'knockout';
import {loadActivity,movementLabel,movementDirection} from '../services/activity';
import {worldShapes,recipientCountries,periodRecords,moneyFlow,chartPath,transferStatus} from '../services/dashboard-insights';
class ViewModel extends Page {
  entries=ko.observableArray<any>([]);
  activityWarning=this.feedback('warning');
  dashboardWallets=ko.pureComputed(()=>['USD','EUR','INR'].map(currency=>this.wallets().find(w=>w.currency===currency)).filter(Boolean));
  recentActivity=ko.pureComputed(()=>[...this.payments(),...this.entries()].sort((a,b)=>Date.parse(b.createdAt)-Date.parse(a.createdAt)).slice(0,6));
  movementLabel=movementLabel;
  movementDirection=movementDirection;
  worldShapes=worldShapes;
  period=ko.observable('30');
  loadedAt=ko.observable(Date.now());
  selectedCountry=ko.observable('');
  periodDays=ko.pureComputed(()=>[7,30,90].includes(Number(this.period()))?Number(this.period()):30);
  periodPayments=ko.pureComputed(()=>periodRecords(this.payments(),this.periodDays(),this.loadedAt()));
  countries=ko.pureComputed(()=>recipientCountries(this.recipients(),this.periodPayments()));
  mapCountries=ko.pureComputed(()=>this.countries().filter(country=>country.point));
  highlightedCountry=ko.pureComputed(()=>this.countries().find(country=>country.code===this.selectedCountry())||this.countries()[0]);
  selectCountry=(country:any)=>this.selectedCountry(country.code);
  flow=ko.pureComputed(()=>moneyFlow(this.payments(),this.entries(),this.overviewCurrency(),this.periodDays(),this.loadedAt()));
  flowMaximum=ko.pureComputed(()=>Math.max(1,...this.flow().buckets.flatMap(day=>[day.incoming,day.outgoing])));
  incomingPath=ko.pureComputed(()=>chartPath(this.flow().buckets.map(day=>day.incoming),this.flowMaximum()));
  outgoingPath=ko.pureComputed(()=>chartPath(this.flow().buckets.map(day=>day.outgoing),this.flowMaximum()));
  incomingArea=ko.pureComputed(()=>this.incomingPath()+' L592,142 L8,142 Z');
  outgoingArea=ko.pureComputed(()=>this.outgoingPath()+' L592,142 L8,142 Z');
  flowAxis=ko.pureComputed(()=>[this.flow().buckets[0].date,this.flow().buckets[Math.floor(this.periodDays()/2)].date,this.flow().buckets[this.periodDays()-1].date]);
  transferSummary=ko.pureComputed(()=>transferStatus(this.periodPayments()));
  flowChartLabel=ko.pureComputed(()=>`Completed ${this.overviewCurrency()} movements in the last ${this.periodDays()} days: ${this.money(this.flow().incoming,this.overviewCurrency())} in and ${this.money(this.flow().outgoing,this.overviewCurrency())} out.`);
  private stopped=false;
  constructor(params:any) { super('home',params);this.screen='dashboard';void this.load(); }
  async load(){
    if(this.screen!=='dashboard'||!sessionStorage.getItem('fluxpay.token'))return;
    await this.run(async()=>{
      const result=await loadActivity(100,100);
      if(this.stopped||!sessionStorage.getItem('fluxpay.token'))return;
      this.loadedAt(Date.now());this.wallets(result.wallets);this.recipients(result.recipients);this.payments(result.payments);this.entries(result.entries);this.activityWarning([result.warning,result.limited?'Insights cover the latest 2,000 records per source. Older activity may not be included.':''].filter(Boolean).join(' '));
    });
  }
  openMovement=(row:any)=>navigate('history',{id:row.id});
  disconnected(){this.stopped=true;super.disconnected();}
  payPerson=(recipient:any)=>navigate('payments-new',{recipient:recipient.id});
  paySomeoneNew=()=>navigate('payments-new',{action:'add-recipient'});
}
export = ViewModel;
