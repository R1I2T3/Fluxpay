import { Page } from '../services/page';
import * as ko from 'knockout';
import {fluxApi} from '../services/flux-api';
import '../services/experience-dialog';
class ViewModel extends Page {
  quoteFetchedAt=ko.observable(0);
  savedDraft=ko.observable(false);
  purposeReason=ko.observable('');
  reasonLabel=ko.pureComputed(()=>this.purpose()==='OTHERS'?this.purposeReason().trim():this.label(this.purpose()));
  paymentReason=ko.pureComputed(()=>this.payment()?.purpose==='OTHERS'?this.payment()?.purposeReason:this.payment()?.purpose?this.label(this.payment().purpose):this.reasonLabel());
  recipientSearch=ko.observable('');
  otherBankName=ko.observable('');
  matchingRecipients=ko.pureComputed(()=>this.activeRecipients().filter(r=>(r.name+' '+r.currency+' '+r.bankName).toLowerCase().includes(this.recipientSearch().trim().toLowerCase())));
  payoutSubmitted=ko.observable(false);
  private requestedRecipient='';
  private openNewRecipient=false;
  private statusTimer:number;
  private closed=false;
  canSubmitPayout=ko.pureComputed(()=>this.payment()?.status==='PROCESSING'&&!this.payoutSubmitted());
  receiptStatus=ko.pureComputed(()=>this.payment()?.status==='PROCESSING'?'Processing':this.payment()?.status==='UNDER_REVIEW'?'Review in progress':this.label(this.payment()?.status));
  receiptTitle=ko.pureComputed(()=>({COMPLETED:'Money sent.',PROCESSING:this.payoutSubmitted()?'Your money is on its way.':'Ready to send.',UNDER_REVIEW:'A quick review before we move.',REJECTED:'This transfer could not proceed.',FAILED:'Your transfer needs attention.',REFUNDED:'Your payment was refunded.',CANCELLED:'Transfer cancelled.'}[this.payment()?.status as string]||'Your transfer'));
  receiptMessage=ko.pureComputed(()=>({COMPLETED:'Your transfer is complete. Keep this receipt for your records.',PROCESSING:this.payoutSubmitted()?'We’re checking the final result. Please do not create another payment while this transfer is processing.':'Your payment is confirmed. Send it below without leaving this page.',UNDER_REVIEW:'Your transfer is awaiting compliance review. The status updates here automatically; you can send it here once approved.',REJECTED:'Compliance rejected this transfer. No payout will be submitted.',FAILED:'The payout was not completed. Open transfer details for available retry or refund options.',REFUNDED:'Check your wallet for the returned funds.',CANCELLED:'No further payout will be submitted.'}[this.payment()?.status as string]||'The latest payment status is shown below.'));
  private draftFingerprint='';
  quoteCountdown=ko.pureComputed(()=>Math.min(this.remaining(),Math.max(0,300-Math.floor((this.now()-this.quoteFetchedAt())/1000))));
  countdownLabel=ko.pureComputed(()=>Math.floor(this.quoteCountdown()/60)+':'+String(this.quoteCountdown()%60).padStart(2,'0'));
  quoteValid=ko.pureComputed(()=>this.quotes().length>0&&this.quoteCountdown()>0);
  constructor(params:any) {
    super('home',params);
    this.screen='payments-new';this.requestedRecipient=params?.params?.recipient||'';this.openNewRecipient=this.requestedRecipient==='new'||params?.params?.action==='add-recipient';
    void this.load();
    this.statusTimer=window.setInterval(()=>{
      if(!this.closed&&!this.busy()&&this.step()===4&&!this.savedDraft()&&['PROCESSING','UNDER_REVIEW'].includes(this.payment()?.status)&&document.visibilityState==='visible')void this.refreshTransfer();
    },10000);
  }
  async load(){
    if(this.screen!=='payments-new')return;
    await super.load();
    if(this.activeRecipients().some(r=>r.id===this.requestedRecipient))this.recipientId(this.requestedRecipient);
    if(this.openNewRecipient){this.addRecipient();this.openNewRecipient=false;}
  }
  disconnected(){this.closed=true;clearInterval(this.statusTimer);super.disconnected();}
  selectRecipient=(recipient:any)=>this.recipientId(recipient.id);
  chooseQuote=(quote:any)=>{if(this.busy()||!this.quoteValid())return;this.selectedQuote(quote);this.step(3);};
  backToQuotes=()=>{if(!this.busy()){this.step(2);this.error('');}};
  dismissAction=()=>{if(!this.busy())this.confirmAction('');};
  closeRecipient=()=>{if(!this.busy())this.recipientFormOpen(false);};
  saveRecipient=()=>this.run(async()=>{
    const body={name:this.recipientName().trim(),account:this.account().trim(),bankName:(this.bankName()==='OTHER'?this.otherBankName():this.bankName()).trim(),country:this.country().trim().toUpperCase(),currency:this.recipientCurrency()};
    if(!body.name||!body.account||!body.bankName||!/^[A-Z]{2}$/.test(body.country))throw new Error('Complete the recipient details and use a two-letter country code.');
    const recipient=await fluxApi.recipient(body);
    this.recipients.push(recipient);this.recipientId(recipient.id);this.recipientSearch('');this.recipientFormOpen(false);this.otherBankName('');
  },'Recipient added and selected. Continue with your payment below.');
  private async readTransfer(){
    const id=this.paymentId();
    const [payment,timeline]=await Promise.all([fluxApi.payment(id),fluxApi.timeline(id)]);
    if(!this.closed&&this.paymentId()===id){this.payment(payment);this.timeline(timeline);}
  }
  refreshTransfer=()=>this.run(()=>this.readTransfer());
  private async submitTransfer(){
    const quote=this.selectedQuote()||this.quotes().find(q=>q.id===this.payment()?.selectedQuoteId);
    if(!this.canSubmitPayout()||!quote)throw new Error('This transfer is not ready to submit. Refresh its status first.');
    // Once dispatched, an uncertain response must never cause an automatic second payout.
    this.payoutSubmitted(true);
    try {
      this.outcome(await fluxApi.payout(this.paymentId(),quote.route));
      await this.readTransfer();
    } catch(error) {
      await this.readTransfer().catch(()=>undefined);
      throw error;
    }
  }
  sendPayment=()=>this.run(async()=>{
    if(this.step()!==3||!this.selectedQuote()||!this.quoteValid())throw new Error('This quote has expired. Go back to options and refresh your quotes.');
    try {await this.acceptQuote();}
    catch(error){
      if(this.payment()&&!['DRAFT','QUOTED'].includes(this.payment().status)){this.step(4);this.confirmAction('');}
      throw error;
    }
    this.step(4);this.notice('');
    if(this.canSubmitPayout())await this.submitTransfer();else await this.readTransfer();
  });
  submitPayout=()=>this.run(()=>this.submitTransfer());
  startAnother=()=>{
    if(this.busy())return;
    this.payment(undefined);this.paymentId('');this.quotes([]);this.selectedQuote(undefined);this.timeline([]);this.outcome(undefined);
    this.payoutSubmitted(false);this.savedDraft(false);this.draftFingerprint='';this.amount('');this.purposeReason('');this.purpose('FAMILY_SUPPORT');this.step(1);this.error('');this.notice('');
  };
  async loadQuotes(generate=false){await super.loadQuotes(generate);this.quoteFetchedAt(Date.now());this.now(Date.now());}
  private async persistDraft(){
    const wallet=this.currentWallet(),recipient=this.currentRecipient();
    if(!wallet||!recipient||recipient.status!=='ACTIVE')throw new Error('Choose a wallet and an active recipient.');
    const custom=this.purpose()==='OTHERS',reason=this.purposeReason().trim();
    if(custom&&(!reason||reason.length>250))throw new Error('Please enter a reason for sending money (up to 250 characters).');
    const body={sourceWalletId:wallet.walletId,recipientId:recipient.id,sourceAmount:this.validAmount(this.amount()),sourceCurrency:wallet.currency,payoutCurrency:recipient.currency,purpose:this.purpose(),preference:this.preference(),...(custom?{purposeReason:reason}:{})};
    const fingerprint=JSON.stringify(body);
    if(fingerprint!==this.draftFingerprint||!['DRAFT','QUOTED'].includes(this.payment()?.status)){
      const payment=await fluxApi.draft(body);this.payment(payment);this.paymentId(payment.id);this.draftFingerprint=fingerprint;
    }
  }
  createDraft=()=>this.run(async()=>{await this.persistDraft();this.savedDraft(false);this.step(2);await this.loadQuotes(true);});
  saveDraft=()=>this.run(async()=>{
    if(this.step()===3){
      if(!this.selectedQuote()||!this.quoteValid())throw new Error('This quote has expired. Go back to delivery options and refresh it before saving.');
      try{await this.acceptQuote();}
      catch(error){if(this.payment()&&!['DRAFT','QUOTED'].includes(this.payment().status)){this.savedDraft(false);this.step(4);}throw error;}
      this.savedDraft(false);this.step(4);
      if(this.payment()?.status==='PROCESSING')this.notice('Saved as Processing. Funds are committed to this transfer; submit the payout here or from its activity receipt.');
    }else if(this.step()===1){
      await this.persistDraft();this.savedDraft(true);this.step(4);this.notice('Draft saved. No funds have been sent.');
    }
  });
  backToDetails=()=>{if(this.busy()||this.payment()&&!['DRAFT','QUOTED'].includes(this.payment().status))return;this.confirmAction('');this.step(1);this.notice('');this.error('');};
}
export = ViewModel;
