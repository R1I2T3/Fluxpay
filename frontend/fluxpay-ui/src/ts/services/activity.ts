import {fluxApi} from './flux-api';

const types=['SEND_MONEY','WALLET_TOPUP','SELF_TRANSFER','WALLET_TO_WALLET','WITHDRAWAL'];
export function activityType(row:any):string {
  const reference=row.journalReference||row.journal_reference||'';
  if(reference.startsWith('wallet:withdraw:'))return 'WITHDRAWAL';
  const declared=[row.type,row.category,row.entry_type,row.entryType].find(value=>types.includes(value));
  if(declared)return declared;
  if(row.type==='FUNDING'||row.entryType==='FUNDING'||reference.startsWith('wallet:demo:')||reference.startsWith('wallet:topup:'))return 'WALLET_TOPUP';
  if(reference.startsWith('wallet:p2p:'))return 'WALLET_TO_WALLET';
  if(row.type==='CONVERSION'||row.entryType==='CONVERSION'||reference.startsWith('wallet:fx:'))return 'SELF_TRANSFER';
  return row.type||row.entry_type||row.entryType||(row.isLedger?'CREDIT':'SEND_MONEY');
}
export function movementLabel(row:any):string {
  return ({WALLET_TOPUP:'Money added',SELF_TRANSFER:'Currency exchange',WALLET_TO_WALLET:'Wallet-to-wallet transfer',WITHDRAWAL:'Bank withdrawal',SEND_MONEY:'Send money',CREDIT:'Money received',DEBIT:'Money out'} as Record<string,string>)[activityType(row)]||'Wallet activity';
}
export function movementDirection(row:any):string {
  const direction=row.direction||(['DEBIT','CREDIT'].includes(row.entryType)?row.entryType:row.entry_type);
  return direction==='CREDIT'?'Money in':direction==='DEBIT'?'Money out':'';
}
export function movementDescription(row:any):string {
  if(activityType(row)==='WALLET_TOPUP')return 'Money added to your wallet';
  return String(row.narration||movementLabel(row)).replace(/\bdemo\b\s*/gi,'').trim();
}
export function normalizeLedger(entry:any,wallet:any):any {
  const row={...entry,id:entry.entryId||entry.id,createdAt:entry.createdAt||entry.created_at,currency:entry.currency||wallet.currency,isLedger:true};
  return {...row,type:activityType(row),status:entry.status||'COMPLETED',sourceAmount:entry.amount,sourceCurrency:row.currency,walletId:wallet.walletId,apiRecord:{...entry}};
}

// Both dashboard and Activity consume the same payment + per-wallet ledger feed.
// Dashboard needs only the latest page of each source; Activity paginates all sources.
export async function loadPayments(maxPages=100){
  const payments:any[]=[];let limited=false;
  for(let page=0;page<maxPages;page++){
    const result=await fluxApi.payments(page);payments.push(...result.items);
    if(!result.items.length||payments.length>=result.total)break;
    if(page===maxPages-1)limited=true;
  }
  return {payments,limited};
}
export async function loadActivity(maxPages=100,paymentMaxPages=maxPages){
  const [wallets,recipients,paymentResult]=await Promise.all([fluxApi.wallets(),fluxApi.recipients(),loadPayments(paymentMaxPages)]);
  const payments=paymentResult.payments;let limited=paymentResult.limited;
  const entries:any[]=[],warnings:string[]=[];
  await Promise.all(wallets.map(async wallet=>{
    let count=0;
    try {
      for(let page=0;page<maxPages;page++){
        const result=await fluxApi.ledger(wallet.walletId,page);count+=result.entries.length;
        for(const entry of result.entries){
          const row=normalizeLedger(entry,wallet);
          // Only suppress a duplicate when the API explicitly links it to a loaded payment.
          if(row.type==='SEND_MONEY'&&entry.paymentId&&payments.some(p=>p.id===entry.paymentId))continue;
          entries.push(row);
        }
        if(!result.entries.length||count>=result.totalElements)break;
        if(page===maxPages-1)limited=true;
      }
    } catch {warnings.push('Could not load '+(wallet.currency||'one')+' wallet activity. Refresh to try again.');}
  }));
  return {wallets,recipients,payments,entries,limited,warning:warnings.join(' ')};
}
