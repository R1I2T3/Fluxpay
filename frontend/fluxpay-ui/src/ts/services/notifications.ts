import * as ko from 'knockout';

export type ToastKind='success'|'error'|'warning'|'info';
export interface Toast {id:number;kind:ToastKind;message:string;title:string;duration:number;remaining:number;started:number;timer?:number;hovered:boolean;focused:boolean;}
export function notify(kind:ToastKind,message:string){
  if(!message?.trim())return;
  // Notifications must never change whether an API operation succeeds or fails.
  try{window.dispatchEvent(new CustomEvent('fluxpay:toast',{detail:{kind,message}}));}catch{/* Inline feedback remains available. */}
}
export function feedbackObservable(kind:ToastKind,enabled:()=>boolean=()=>true){
  const value=ko.observable('');
  value.subscribe(message=>{if(message&&enabled())notify(kind,message);});
  return value;
}

/** One bounded, non-modal notification stack shared by customer and admin screens. */
export class ToastCenter {
  items=ko.observableArray<Toast>([]);
  private sequence=0;
  constructor(private clock={now:()=>Date.now(),set:(fn:()=>void,ms:number)=>window.setTimeout(fn,ms),clear:(id:number)=>window.clearTimeout(id)}){}
  show=(kind:ToastKind,message:string)=>{
    if(!['success','error','warning','info'].includes(kind)||typeof message!=='string'||!message.trim())return;
    const text=message.trim();if(this.items().some(item=>item.kind===kind&&item.message===text))return;
    if(this.items().length>=3)this.dismiss(this.items()[0]);
    const duration=kind==='error'||kind==='warning'?10000:6000;
    const toast:Toast={id:++this.sequence,kind,message:text,title:{success:'All set',error:'Something needs attention',warning:'Please note',info:'Good to know'}[kind],duration,remaining:duration,started:this.clock.now(),hovered:false,focused:false};
    this.items.push(toast);this.schedule(toast);
  };
  private schedule(item:Toast){item.started=this.clock.now();item.timer=this.clock.set(()=>this.dismiss(item),item.remaining);}
  private pause(item:Toast){if(item.timer===undefined)return;this.clock.clear(item.timer);item.timer=undefined;item.remaining=Math.max(0,item.remaining-(this.clock.now()-item.started));}
  private resume(item:Toast){if(!item.hovered&&!item.focused&&item.timer===undefined&&this.items().includes(item))this.schedule(item);}
  hover=(item:Toast)=>{item.hovered=true;this.pause(item);};
  unhover=(item:Toast)=>{item.hovered=false;this.resume(item);};
  focus=(item:Toast)=>{item.focused=true;this.pause(item);};
  blur=(item:Toast)=>{item.focused=false;this.resume(item);};
  dismiss=(item:Toast)=>{if(item.timer!==undefined)this.clock.clear(item.timer);this.items.remove(item);};
  clear=()=>{this.items().slice().forEach(this.dismiss);};
}
