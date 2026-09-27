import * as ko from 'knockout';
ko.bindingHandlers.kycPdfPreview={init(element:HTMLElement,valueAccessor:()=>string){
  const render=(window as any).FluxPayPdfPreview;
  if(!render){element.textContent='The document viewer could not be loaded. Refresh this page or download the document.';return;}
  const dispose=render(element,ko.unwrap(valueAccessor()));ko.utils.domNodeDisposal.addDisposeCallback(element,dispose);
}};
ko.bindingHandlers.experienceFocus={init(element:HTMLElement){const timer=window.setTimeout(()=>element.focus(),0);ko.utils.domNodeDisposal.addDisposeCallback(element,()=>clearTimeout(timer));}};
// Announce the next step and bring its heading into view, without stealing initial focus.
ko.bindingHandlers.experienceStepFocus={init(element:HTMLElement,valueAccessor:()=>ko.Observable<number>){
  let timer:number|undefined;
  const subscription=valueAccessor().subscribe(()=>{
    clearTimeout(timer);
    timer=window.setTimeout(()=>{
      if(!element.isConnected||element.closest('[inert]')||document.querySelector('[aria-modal="true"]'))return;
      element.focus({preventScroll:true});element.scrollIntoView({block:'start',behavior:'auto'});
    },0);
  });
  ko.utils.domNodeDisposal.addDisposeCallback(element,()=>{clearTimeout(timer);subscription.dispose();});
}};
// Shared focus management for customer dialogs; no dependency on the admin module.
ko.bindingHandlers.experienceDialog={init(element:HTMLElement,valueAccessor:()=>()=>void){
  const previous=document.activeElement as HTMLElement|null;
  const controls=()=>Array.from(element.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href],[tabindex="0"]'));
  const timer=window.setTimeout(()=>controls()[0]?.focus(),0);
  const key=(event:KeyboardEvent)=>{
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();valueAccessor()();}
    if(event.key!=='Tab')return;
    const list=controls(),first=list[0],last=list[list.length-1];
    if(!first){event.preventDefault();return;}
    if(event.shiftKey&&(document.activeElement===first||!element.contains(document.activeElement))){event.preventDefault();last.focus();}
    else if(!event.shiftKey&&(document.activeElement===last||!element.contains(document.activeElement))){event.preventDefault();first.focus();}
  };
  element.addEventListener('keydown',key);
  ko.utils.domNodeDisposal.addDisposeCallback(element,()=>{clearTimeout(timer);element.removeEventListener('keydown',key);const target=previous?.isConnected?previous:previous?.id?document.getElementById(previous.id):null;target?.focus();});
}};
