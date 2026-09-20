import {activityType,movementLabel,movementDirection,movementDescription} from './activity';

export const preciseTime=(value:any)=>{
  const date=new Date(value);
  return value&&Number.isFinite(date.getTime())?new Intl.DateTimeFormat(undefined,{year:'numeric',month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',timeZoneName:'short'}).format(date):'Timestamp not supplied';
};
export const maskedAccount=(value:any)=>value?'•••• '+String(value).replace(/\s/g,'').slice(-4):'';
export const customerFields=(fields:Array<{label:string;value:any}>)=>fields.filter(field=>field.value!==undefined&&field.value!==null&&field.value!=='');
export const historyIcon=(row:any)=>row.isLedger?(movementDirection(row)==='Money in'?'+':activityType(row)==='SELF_TRANSFER'?'⇄':'↔'):'↗';
export const historyTitle=(row:any,recipients:any[])=>row.isLedger?movementLabel(row):recipients.find(r=>r.id===row.recipientId)?.name||'International payment';
export const statusText=(status:string)=>({DRAFT:'Saved draft',QUOTED:'Ready to send',PROCESSING:'In progress',UNDER_REVIEW:'Under review',COMPLETED:'Completed',FAILED:'Unsuccessful',REJECTED:'Declined',REFUNDED:'Refunded',CANCELLED:'Cancelled'} as Record<string,string>)[status]||status||'Status unavailable';
export const statusDescription=(status:string)=>({DRAFT:'Saved for later. No payout has been sent.',QUOTED:'A quote is available. This payment has not been confirmed yet.',PROCESSING:'Your payment is in progress. Recorded updates appear below as the service reports them.',UNDER_REVIEW:'This payment is awaiting a compliance decision. A payout cannot proceed until approved.',COMPLETED:'The service has marked this transaction as completed.',FAILED:'The payment did not complete. Review the recorded reason and available recovery options.',REJECTED:'This payment was declined. Review the recorded events for the reason.',REFUNDED:'The service has marked this payment as refunded.',CANCELLED:'This payment was cancelled.'} as Record<string,string>)[status]||'The latest status supplied by the service is shown here.';
const eventLabels:Record<string,string>={
  'payment.created':'Payment created','payment.initiated':'Payment initiated','payment.route.selected':'Delivery route selected',
  'payment.screening.completed':'Screening completed','payment.review.requested':'Compliance review requested',
  'payout.submitted':'Sent to the payout provider','payout.failed':'Payout unsuccessful','payout.completed':'Payout completed',
  'payment.refunded':'Payment refunded','payment.cancelled':'Payment cancelled','payment.completed':'Payment completed'
};
const eventDescriptions:Record<string,string>={
  'payment.created':'Your payment was created.','payment.initiated':'Your payment request was received.',
  'payment.route.selected':'A delivery method was selected for your payment.','payment.screening.completed':'The payment checks were completed.',
  'payment.review.requested':'Your payment needs an additional review before it can continue.',
  'payout.submitted':'Your payment was submitted for delivery.','payout.failed':'This delivery attempt did not complete. Open Manage this payment for available next steps.',
  'payout.completed':'Your payment was marked as delivered.','payment.completed':'Your payment was completed.',
  'payment.refunded':'A refund was recorded for this payment.','payment.cancelled':'Your payment was cancelled.'
};
export function eventSteps(events:any[]){
  return events.map((event,index)=>({event,index})).sort((a,b)=>{
    const left=Date.parse(a.event.occurredAt),right=Date.parse(b.event.occurredAt);
    return (Number.isFinite(left)?left:Infinity)-(Number.isFinite(right)?right:Infinity)||a.index-b.index;
  }).map(({event})=>({
    title:eventLabels[event.eventType]||String(event.eventType||'Recorded event').replace(/[._]/g,' ').replace(/^./,s=>s.toUpperCase()),
    timestamp:event.occurredAt,description:eventDescriptions[event.eventType]||'A payment update was recorded.',
    tone:/failed|rejected/.test(event.eventType||'')?'negative':/review/.test(event.eventType||'')?'pending':'recorded',
  }));
}
export function ledgerSteps(row:any,related:any[]){
  return (related.length?related:[row]).slice().sort((a,b)=>Date.parse(a.createdAt)-Date.parse(b.createdAt)).map(entry=>({
    title:(movementDirection(entry)||'Movement recorded')+' · '+entry.sourceCurrency+' wallet',timestamp:entry.createdAt,
    description:movementDescription(entry)+' · '+new Intl.NumberFormat('en-US',{style:'currency',currency:entry.sourceCurrency}).format(Number(entry.sourceAmount||0)),tone:'recorded'
  }));
}
export function dayGroups(rows:any[]){
  const groups:Array<{key:string;label:string;rows:any[]}>=[];
  for(const row of rows){
    const date=new Date(row.createdAt),valid=Number.isFinite(date.getTime());
    const key=valid?[date.getFullYear(),date.getMonth(),date.getDate()].join('-'):'unknown';
    let group=groups.find(g=>g.key===key);
    if(!group){group={key,label:valid?new Intl.DateTimeFormat(undefined,{weekday:'long',day:'numeric',month:'long',year:'numeric'}).format(date):'Date not supplied',rows:[]};groups.push(group);}
    group.rows.push(row);
  }
  return groups;
}
