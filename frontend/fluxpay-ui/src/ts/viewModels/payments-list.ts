import { Page } from '../services/page';
import * as ko from 'knockout';
import {loadActivity,movementLabel,movementDirection} from '../services/activity';
import {fluxApi} from '../services/flux-api';
import {preciseTime,customerFields,maskedAccount,historyIcon,historyTitle,statusText,statusDescription,eventSteps,ledgerSteps,dayGroups} from '../services/history-presentation';
import '../services/experience-dialog';
const shortId=(id:string)=>id?id.slice(0,8)+'…':'';
class ViewModel extends Page {
  static shortId=shortId;
  entries=ko.observableArray<any>([]);
  activityWarning=ko.observable('');
  movementLabel=movementLabel;
  movementDirection=movementDirection;
  filteredActivity=ko.pureComputed(()=>[...this.payments(),...this.entries()].filter(row=>this.matchesActivity(row)).sort((a,b)=>Date.parse(b.createdAt)-Date.parse(a.createdAt)));
  activityPage=ko.pureComputed(()=>this.filteredActivity().slice(this.page()*20,(this.page()+1)*20));
  activityPageCount=ko.pureComputed(()=>Math.max(1,Math.ceil(this.filteredActivity().length/20)));
  groupedActivity=ko.pureComputed(()=>dayGroups(this.activityPage()));
  completedActivity=ko.pureComputed(()=>this.filteredActivity().filter(row=>row.status==='COMPLETED').length);
  pendingActivity=ko.pureComputed(()=>this.filteredActivity().filter(row=>['PROCESSING','UNDER_REVIEW'].includes(row.status)).length);
  preciseTime=preciseTime;
  historyIcon=historyIcon;
  statusText=statusText;
  statusDescription=statusDescription;
  historyTitle=(row:any)=>historyTitle(row,this.recipients());
  signedAmount=(row:any)=>(row.isLedger?(movementDirection(row)==='Money in'?'+':movementDirection(row)==='Money out'?'−':''):'')+this.money(row.sourceAmount,row.sourceCurrency);
  filterKinds=[{value:'',label:'All activity'},{value:'SEND_MONEY',label:'Payments'},{value:'WALLET_TOPUP',label:'Money added'},{value:'WALLET_TO_WALLET',label:'Wallet transfers'},{value:'SELF_TRANSFER',label:'Exchanges'},{value:'WITHDRAWAL',label:'Withdrawals'}];
  chooseKind=(kind:any)=>this.typeFilter(kind.value);
  detailRow=ko.observable<any>();
  detailPayment=ko.observable<any>();
  detailEvents=ko.observableArray<any>([]);
  detailQuotes=ko.observable<any>();
  detailBusy=ko.observable(false);
  detailWarning=ko.observable('');
  detailUpdated=ko.observable('');
  detailPayBusy=ko.observable(false);
  detailPayError=ko.observable('');
  detailPayNotice=ko.observable('');
  detailPayReview=ko.observable(false);
  detailPayQuote=ko.observable<any>();
  private dispatchedPayouts=new Set<string>();
  detailPayoutStarted=ko.pureComputed(()=>this.dispatchedPayouts.has(this.detailRecord()?.id)||this.detailEvents().some(e=>['payout.submitted','payout.completed','payout.failed'].includes(e.eventType)));
  canPayDetail=ko.pureComputed(()=>!!this.detailPayment()&&!this.detailRow()?.isLedger&&!this.detailPayoutStarted()&&['DRAFT','QUOTED','PROCESSING'].includes(this.detailRecord()?.status));
  detailPayQuoteValid=ko.pureComputed(()=>!!this.detailPayQuote()&&Date.parse(this.detailQuotes()?.expiresAt)>this.now());
  detailQuoteLabel=(q:any)=>this.label(q.routeCode)+' · '+this.money(q.feeAmount,this.detailRecord()?.sourceCurrency)+' fee · receives '+this.money(q.recipientAmount,this.detailRecord()?.payoutCurrency);
  detailRecord=ko.pureComputed(()=>this.detailPayment()||this.detailRow());
  detailRecipient=ko.pureComputed(()=>this.recipients().find(r=>r.id===this.detailRecord()?.recipientId));
  detailWallet=ko.pureComputed(()=>this.wallets().find(w=>w.walletId===(this.detailRecord()?.sourceWalletId||this.detailRow()?.walletId)));
  acceptedQuote=ko.pureComputed(()=>this.detailQuotes()?.quotes?.find((q:any)=>q.id===this.detailRecord()?.selectedQuoteId));
  relatedEntries=ko.pureComputed(()=>{
    const row=this.detailRow(),ref=row?.journalReference||row?.journal_reference;
    return row?.isLedger&&ref?this.entries().filter(e=>(e.journalReference||e.journal_reference)===ref):[];
  });
  detailSteps=ko.pureComputed(()=>this.detailRow()?.isLedger?ledgerSteps(this.detailRow(),this.relatedEntries()):eventSteps(this.detailEvents()));
  recipientFields=ko.pureComputed(()=>{const r=this.detailRecipient();return r?customerFields([{label:'Name',value:r.name},{label:'Bank',value:r.bankName},{label:'Account ending in',value:maskedAccount(r.account)},{label:'Country',value:r.country}]):[];});
  quoteFields=ko.pureComputed(()=>{
    const q=this.acceptedQuote(),p=this.detailRecord();if(!q)return [];
    return customerFields([{label:'Transfer fee',value:q.feeAmount!=null?this.money(q.feeAmount,p.sourceCurrency):undefined},
      {label:'Exchange rate',value:q.offeredRate!=null&&p.payoutCurrency?'1 '+p.sourceCurrency+' = '+q.offeredRate+' '+p.payoutCurrency:undefined},
      {label:'Recipient amount',value:q.recipientAmount!=null&&p.payoutCurrency?this.money(q.recipientAmount,p.payoutCurrency):undefined},
      {label:'Delivery method',value:q.routeCode?this.label(q.routeCode):undefined}]);
  });
  private detailGeneration=0;
  private detailTimer:number;
  private requestedDetail='';
  private subscriptions:ko.Subscription[]=[];
  private generation=0;
  private stopped=false;
  constructor(params:any){
    super('home',params);this.screen='payments-list';this.requestedDetail=params?.params?.id||'';
    this.subscriptions=[this.filter,this.search,this.typeFilter,this.fromDate,this.toDate].map(value=>value.subscribe(()=>this.page(0)));
    void this.load();
    this.detailTimer=window.setInterval(()=>{if(this.detailRow()&&!this.detailRow().isLedger&&!this.detailBusy()&&!this.detailPayBusy()&&['PROCESSING','UNDER_REVIEW'].includes(this.detailRecord()?.status)&&document.visibilityState==='visible')void this.refreshDetail();},10000);
  }
  async load(){
    if(this.screen!=='payments-list')return;
    if(!sessionStorage.getItem('fluxpay.token'))return;
    await this.run(async()=>{
      const generation=++this.generation;
      const {wallets,recipients,payments,entries,limited,warning}=await loadActivity();
      if(this.stopped||generation!==this.generation||!sessionStorage.getItem('fluxpay.token'))return;
      this.wallets(wallets);this.recipients(recipients);this.payments(payments);this.entries(entries);this.total(payments.length);
      this.page(Math.min(this.page(),this.activityPageCount()-1));
      this.activityWarning([warning,limited?'Showing up to the latest 2,000 records per source. Filters apply to loaded records.':''].filter(Boolean).join(' '));
    });
    if(this.requestedDetail&&!this.stopped){
      const id=this.requestedDetail;this.requestedDetail='';const row=[...this.payments(),...this.entries()].find(r=>r.id===id);
      if(row)await this.openDetail(row);else this.activityWarning('This transaction is not in the loaded history. Refresh or broaden the date range in the originating account.');
    }
  }
  openDetail=async(row:any)=>{
    if(this.detailPayBusy())return;
    this.detailPayError('');this.detailPayNotice('');this.detailPayReview(false);this.detailPayQuote(undefined);
    this.detailGeneration++;this.detailRow(row);this.detailPayment(undefined);this.detailEvents([]);this.detailQuotes(undefined);this.detailWarning('');this.detailUpdated('');this.detailBusy(false);
    if(!row.isLedger)await this.refreshDetail();
  };
  closeDetail=()=>{if(this.detailPayBusy()&&!this.stopped&&this.session.user())return;this.detailGeneration++;this.detailRow(undefined);this.detailBusy(false);};
  refreshDetail=async()=>{
    const row=this.detailRow();if(!row||row.isLedger||this.detailBusy())return;
    const generation=++this.detailGeneration;this.detailBusy(true);this.detailWarning('');
    const results=await Promise.allSettled([fluxApi.payment(row.id),fluxApi.timeline(row.id),fluxApi.getQuotes(row.id)]);
    if(this.stopped||generation!==this.detailGeneration||!this.session.user()){if(!this.session.user())this.closeDetail();return;}
    const warnings:string[]=[];
    if(results[0].status==='fulfilled'){this.detailPayment(results[0].value);const current=this.payments().find(p=>p.id===row.id);if(current)this.payments.replace(current,{...current,...results[0].value});}
    else warnings.push('Latest transaction details could not be loaded. The list snapshot is shown.');
    if(results[1].status==='fulfilled')this.detailEvents(results[1].value);else warnings.push('The event timeline is unavailable. No missing steps or timestamps have been assumed.');
    if(results[2].status==='fulfilled')this.detailQuotes(results[2].value);else if(row.selectedQuoteId)warnings.push('Quote and fee details are currently unavailable.');
    this.detailWarning(warnings.join(' '));this.detailUpdated(new Date().toISOString());this.detailBusy(false);
  };
  manageTransfer=()=>{const row=this.detailRecord();if(row&&!this.detailRow()?.isLedger)this.openPayment(row);};
  prepareDetailPay=async()=>{
    if(this.detailPayBusy()||this.detailBusy()||!this.canPayDetail())return;
    if(this.detailRecord().status==='PROCESSING'){await this.submitDetailPay();return;}
    this.detailPayBusy(true);this.detailPayError('');
    const id=this.detailRecord().id,generation=this.detailGeneration;
    try{
      const quotes=await fluxApi.quotes(id);
      const latest=await fluxApi.payment(id);
      if(this.stopped||generation!==this.detailGeneration||!this.session.user())return;
      this.detailQuotes(quotes);this.detailPayment(latest);this.detailPayQuote(undefined);this.detailPayReview(true);this.now(Date.now());
    }catch(e:any){if(generation===this.detailGeneration)this.detailPayError(e.message||'Unable to load delivery options.');}
    finally{this.detailPayBusy(false);}
  };
  submitDetailPay=async()=>{
    if(this.detailPayBusy()||this.detailBusy()||!this.canPayDetail())return;
    const id=this.detailRecord().id,generation=this.detailGeneration;
    const active=()=>!this.stopped&&generation===this.detailGeneration&&!!this.session.user();
    this.detailPayBusy(true);this.detailPayError('');this.detailPayNotice('');
    try{
      const latest=await fluxApi.payment(id);if(!active())return;
      this.detailPayment(latest);
      if(['DRAFT','QUOTED'].includes(latest.status)){
        this.now(Date.now());
        if(!this.detailPayReview()||!this.detailPayQuoteValid())throw new Error('Choose a current delivery option before submitting. Refresh options if the quote has expired.');
        this.detailPayment(await fluxApi.confirm(id,this.detailPayQuote().id));if(!active())return;
      }
      if(this.detailRecord().status!=='PROCESSING'){
        this.detailPayNotice(this.detailRecord().status==='UNDER_REVIEW'?'This payment needs compliance approval before it can be submitted.':'This payment is not ready for payout. Its latest status is shown above.');return;
      }
      const quote=this.acceptedQuote();if(!quote)throw new Error('The confirmed delivery option is unavailable. Refresh transaction details.');
      const events=await fluxApi.timeline(id);if(!active())return;this.detailEvents(events);
      if(this.detailPayoutStarted())throw new Error('This payout has already been submitted. Refresh to see its latest status.');
      this.dispatchedPayouts.add(id);this.detailEvents.valueHasMutated();
      await fluxApi.payout(id,quote.routeCode);
      if(active()){this.detailPayReview(false);this.detailPayNotice('Payment submitted. The latest result is shown above.');}
    }catch(e:any){if(active())this.detailPayError(e.message||'Unable to submit payment. Refresh its status before taking further action.');}
    finally{
      if(active()){await this.refreshDetail();this.detailPayBusy(false);}else this.detailPayBusy(false);
    }
  };
  nextPage=()=>this.page(Math.min(this.activityPageCount()-1,this.page()+1));
  previousPage=()=>this.page(Math.max(0,this.page()-1));
  clearFilters=()=>{this.search('');this.filter('ALL');this.typeFilter('');this.fromDate('');this.toDate('');};
  disconnected(){this.stopped=true;this.generation++;this.closeDetail();clearInterval(this.detailTimer);this.subscriptions.forEach(s=>s.dispose());super.disconnected();}
}
export = ViewModel;
