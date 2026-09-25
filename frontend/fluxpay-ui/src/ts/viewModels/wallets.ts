import { Page } from '../services/page';
import * as ko from 'knockout';
import {fluxApi} from '../services/flux-api';
import {loadPayments} from '../services/activity';
import '../services/experience-dialog';
class ViewModel extends Page {
  addMoneyOpen=ko.observable(false);
  holdWarning=this.feedback('warning');
  banks=ko.observableArray<any>([]);
  bankWarning=this.feedback('warning');
  bankId=ko.observable('');
  fundingMode=ko.observable('bank');
  bankFormName=ko.observable('');
  bankOtherName=ko.observable('');
  bankLast4=ko.observable('');
  bankCurrency=ko.observable('USD');
  moneyAction=ko.observable('');
  operationReview=ko.observable<any>();
  operationResult=ko.observable<any>();
  operationNote=ko.observable('');
  transferEmail=ko.observable('');
  transferSelector=ko.observable('email');
  transferUserId=ko.observable('');
  transferFrom=ko.observable('USD');
  transferTo=ko.observable('INR');
  transferAmount=ko.observable('100');
  amountMode=ko.observable('TARGET');
  withdrawAmount=ko.observable('100');
  eligibleBanks=ko.pureComputed(()=>this.banks().filter(b=>b.status==='VERIFIED'&&b.currency===this.currency()));
  bankLabel=(bank:any)=>bank.bankName+' · •••• '+bank.accountLast4+' · '+bank.currency;
  private returnToFunding=false;
  private stopped=false;
  private holdTimer:number;
  constructor(params:any) {
    super('home',params);this.screen='wallets';
    const action=params.params?.action||new URLSearchParams(window.location?.search||'').get('action');
    this.addMoneyOpen(params.routerState?.path==='add-money'||action==='add-money');
    if(['transfer','withdraw'].includes(action))this.moneyAction(action);
    void this.load();this.holdTimer=window.setInterval(()=>{if(this.onHold().length&&!this.busy()&&document.visibilityState==='visible')void this.load();},10000);
  }
  async load(){
    if(this.screen!=='wallets')return;
    if(!sessionStorage.getItem('fluxpay.token'))return;
    await this.run(async()=>{const [wallets,result,recipients]=await Promise.all([fluxApi.wallets(),loadPayments(),fluxApi.recipients()]);if(this.stopped||!sessionStorage.getItem('fluxpay.token'))return;this.wallets(wallets);this.payments(result.payments);this.recipients(recipients);this.holdWarning(result.limited?'Processing totals include the latest 2,000 payments only.':'');await this.readBanks();if(this.ledgerWallet())await this.loadLedger();});
  }
  readBanks=async()=>{try{const banks=await fluxApi.bankAccounts();if(!this.stopped&&this.session.user()){this.banks(banks);this.bankWarning('');}}catch{this.bankWarning('Linked bank accounts could not be loaded. Refresh to try again.');}};
  openAddMoney=()=>{this.error('');this.operationReview(undefined);this.addMoneyOpen(true);};
  closeAddMoney=()=>{if(!this.busy())this.addMoneyOpen(false);};
  openMoneyAction=(action:string)=>{if(this.busy())return;this.error('');this.operationReview(undefined);this.operationNote('');this.moneyAction(action);};
  openLinkBank=()=>{this.returnToFunding=this.addMoneyOpen();this.addMoneyOpen(false);this.openMoneyAction('bank');this.bankCurrency(this.currency());};
  closeMoneyAction=()=>{if(this.busy())return;this.moneyAction('');this.operationReview(undefined);if(this.returnToFunding){this.returnToFunding=false;this.addMoneyOpen(true);}};
  editOperation=()=>{if(!this.busy())this.operationReview(undefined);};
  linkBank=()=>this.run(async()=>{
    const bankName=(this.bankFormName()==='OTHER'?this.bankOtherName():this.bankFormName()).trim(),accountLast4=this.bankLast4().trim();
    if(!bankName||bankName.length>80||!/^\d{4}$/.test(accountLast4))throw new Error('Enter a bank name and only the last four digits of the account.');
    const bank=await fluxApi.linkBank({bankName,accountLast4,currency:this.bankCurrency()});
    if(this.stopped||!this.session.user())return;
    this.banks([...this.banks().filter(b=>b.id!==bank.id),bank]);this.currency(bank.currency);this.bankId(bank.id);this.bankFormName('');this.bankOtherName('');this.bankLast4('');this.moneyAction('');
    if(this.returnToFunding){this.returnToFunding=false;this.addMoneyOpen(true);}
  },'Bank account linked.');
  prepareMoney=(kind:string)=>{
    if(this.busy())return;this.error('');
    try{
      const note=this.operationNote().trim();if(note.length>255)throw new Error('Keep the note to 255 characters or fewer.');
      let body:any,summary:string,title:string;
      if(kind==='transfer'){
        const amount=this.validAmount(this.transferAmount()),fromCurrency=this.transferFrom(),toCurrency=this.transferTo();
        let recipient:any;
        if(this.transferSelector()==='id'){const toUserId=this.transferUserId().trim();if(!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(toUserId))throw new Error('Enter a valid FluxPay user ID.');recipient={toUserId};}
        else{const toEmail=this.transferEmail().trim().toLowerCase();if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(toEmail))throw new Error('Enter the recipient’s registered email address.');recipient={toEmail};}
        body={...recipient,fromCurrency,toCurrency,amount,amountMode:'TARGET',note};title='Send to a FluxPay wallet?';
        summary='They receive '+this.money(amount,toCurrency)+' · '+fromCurrency+' → '+toCurrency+' · '+(recipient.toEmail||recipient.toUserId);
      }else{
        const amount=this.validAmount(kind==='withdraw'?this.withdrawAmount():this.fundAmount()),bank=this.eligibleBanks().find(b=>b.id===this.bankId());
        if(kind==='test'){body={currency:this.currency(),amount};title='Add test balance?';summary=this.money(amount,this.currency())+' to your '+this.currency()+' wallet';}
        else{
          if(!bank)throw new Error('Choose a verified bank account in the same currency.');
          if(kind==='withdraw')this.requireBalance(this.currency(),amount);
          if(kind==='topup'&&this.session.user()?.kycStatus!=='VERIFIED')throw new Error('Verify your identity before adding money from a bank account.');
          body=kind==='withdraw'?{bankAccountId:bank.id,currency:this.currency(),amount,note}:{bankAccountId:bank.id,amount,note};
          title=kind==='withdraw'?'Withdraw to your bank?':'Add money from your bank?';summary=this.money(amount,this.currency())+' · '+this.bankLabel(bank);
        }
      }
      this.operationReview({kind,body,title,summary});
    }catch(e:any){this.error(e.message);}
  };
  prepareFunding=()=>this.prepareMoney('topup');
  requireBalance=(currency:string,amount:string)=>{const wallet=this.wallets().find(w=>w.currency===currency);if(!wallet||Number(amount)>Number(wallet.availableBalance))throw new Error('Insufficient available balance in your '+currency+' wallet.');};
  confirmMoney=()=>this.run(async()=>{
    const review=this.operationReview();if(!review)throw new Error('Review the transaction first.');
    const {kind,body}=review;
    const result=kind==='transfer'?await fluxApi.walletTransfer(body):kind==='withdraw'?await fluxApi.withdraw(body):kind==='topup'?await fluxApi.bankTopup(body.bankAccountId,{amount:body.amount,note:body.note}):await fluxApi.fund(body);
    if(this.stopped||!this.session.user())return;
    this.operationResult({...result,title:kind==='transfer'?'Wallet transfer complete':kind==='withdraw'?'Withdrawal recorded':'Money added',summary:review.summary});this.operationReview(undefined);this.addMoneyOpen(false);this.moneyAction('');
    // A read failure after a successful write must not invite a duplicate payment.
    try{this.wallets(await fluxApi.wallets());this.ledgerWallet(this.wallets().find(w=>w.walletId===(result.sourceWalletId||result.walletId)));this.ledgerPage(0);if(this.ledgerWallet())await this.loadLedger();}
    catch{this.bankWarning('Your transaction succeeded. Refresh to update balances and activity.');}
  },'Transaction recorded.',false);
  fund=()=>this.run(async()=>{
    await fluxApi.fund({currency:this.currency(),amount:this.validAmount(this.fundAmount())});
    this.wallets(await fluxApi.wallets());this.ledgerWallet(this.wallets().find(w=>w.currency===this.currency()));this.ledgerPage(0);this.addMoneyOpen(false);
    if(this.ledgerWallet())await this.loadLedger();
  },'Money added. Your updated balance and wallet history are ready.',false);
  convert=()=>this.run(async()=>{
    if(this.from()===this.to())throw new Error('Choose two different currencies.');
    this.conversion(await fluxApi.convert({from:this.from(),to:this.to(),amount:this.validAmount(this.amount())}));
    this.wallets(await fluxApi.wallets());if(this.ledgerWallet())await this.loadLedger();
  },'Currency exchanged successfully.',false);
  disconnected(){this.stopped=true;clearInterval(this.holdTimer);super.disconnected();}
}
export = ViewModel;
