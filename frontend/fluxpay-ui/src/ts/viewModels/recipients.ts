import { Page } from '../services/page';
import * as ko from 'knockout';
import {fluxApi} from '../services/flux-api';
import {deleteRecipient} from '../services/recipient-actions';
import '../services/experience-dialog';
class ViewModel extends Page {
  deleting=ko.observable<any>();
  countryOptions='IN US DE FR IT ES NL IE PT AT BE FI GR LU MT CY EE LV LT SK SI GB CA AU SG AE JP'.split(' ').map(code=>({code,name:new Intl.DisplayNames(['en'],{type:'region'}).of(code)||code}));
  bankOptions=['State Bank of India','HDFC Bank','ICICI Bank','Axis Bank','Bank of Baroda','JPMorgan Chase','Bank of America','Citibank','Wells Fargo','Deutsche Bank','Commerzbank','BNP Paribas','HSBC','Barclays','DBS Bank'];
  constructor(params:any) { super('recipients',params); }
  saveRecipient=()=>this.run(async()=>{
    const body={name:this.recipientName().trim(),account:this.account().trim(),bankName:this.bankName().trim(),country:this.country().trim().toUpperCase(),currency:this.recipientCurrency(),expectedVersion:this.recipientEditing()?.version};
    if(!body.name||!body.account||!body.bankName||!/^[A-Z]{2}$/.test(body.country))throw new Error('Complete the recipient details and choose a two-letter country code.');
    if(this.recipientEditing())await fluxApi.updateRecipient(this.recipientEditing().id,body);else await fluxApi.recipient(body);
    this.recipients(await fluxApi.recipients());this.recipientFormOpen(false);
  },'Recipient saved.');
  askDelete=(recipient:any)=>{this.error('');this.deleting(recipient);};
  cancelDelete=()=>{if(!this.busy())this.deleting(undefined);};
  deleteRecipient=()=>this.run(async()=>{
    const recipient=this.deleting();if(!recipient)return;
    await deleteRecipient(recipient.id);this.recipients.remove((r:any)=>r.id===recipient.id);this.deleting(undefined);
  },'Recipient deleted. Existing transfer history is unchanged.');
}
export = ViewModel;
