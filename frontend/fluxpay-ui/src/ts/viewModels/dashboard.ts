import { Page } from '../services/page';
import { navigate } from '../services/session';
import * as ko from 'knockout';
import {loadActivity,movementLabel,movementDirection} from '../services/activity';
class ViewModel extends Page {
  entries=ko.observableArray<any>([]);
  activityWarning=ko.observable('');
  recentActivity=ko.pureComputed(()=>[...this.payments(),...this.entries()].sort((a,b)=>Date.parse(b.createdAt)-Date.parse(a.createdAt)).slice(0,6));
  movementLabel=movementLabel;
  movementDirection=movementDirection;
  private stopped=false;
  constructor(params:any) { super('home',params);this.screen='dashboard';void this.load(); }
  async load(){
    if(this.screen!=='dashboard'||!sessionStorage.getItem('fluxpay.token'))return;
    await this.run(async()=>{
      const result=await loadActivity(1,100);
      if(this.stopped||!sessionStorage.getItem('fluxpay.token'))return;
      this.wallets(result.wallets);this.recipients(result.recipients);this.payments(result.payments);this.entries(result.entries);this.activityWarning(result.warning);
    });
  }
  openMovement=(row:any)=>navigate('history',{id:row.id});
  disconnected(){this.stopped=true;super.disconnected();}
  payPerson=(recipient:any)=>navigate('payments-new',{recipient:recipient.id});
  paySomeoneNew=()=>navigate('payments-new',{action:'add-recipient'});
}
export = ViewModel;
