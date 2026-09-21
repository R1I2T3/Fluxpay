// Lazy, local-only PDF rendering. No document is sent to an external viewer.
window.FluxPayPdfPreview = (element, url) => {
  let destroyed=false, loadingTask, pdf, renderTask, pageNumber=1, rendering=false;
  const status=document.createElement('p'),toolbar=document.createElement('div'),previous=document.createElement('button'),next=document.createElement('button'),pageLabel=document.createElement('span'),canvas=document.createElement('canvas');
  toolbar.className='kyc-pdf-toolbar';status.className='form-help';status.setAttribute('role','status');status.textContent='Preparing document preview…';
  previous.type=next.type='button';previous.className=next.className='pill soft';previous.textContent='← Previous';next.textContent='Next →';previous.setAttribute('aria-label','Previous document page');next.setAttribute('aria-label','Next document page');
  canvas.setAttribute('role','img');canvas.setAttribute('aria-label','Rendered identity document');canvas.style.maxWidth='100%';canvas.style.height='auto';toolbar.append(previous,pageLabel,next);element.append(status,toolbar,canvas);
  const update=()=>{previous.disabled=rendering||pageNumber<=1;next.disabled=rendering||!pdf||pageNumber>=pdf.numPages;pageLabel.textContent=pdf?'Page '+pageNumber+' of '+pdf.numPages:'';};
  async function render(){
    if(destroyed||rendering||!pdf)return;rendering=true;update();status.textContent='Rendering page…';
    try{
      const page=await pdf.getPage(pageNumber);if(destroyed)return;
      const original=page.getViewport({scale:1}),width=Math.max(250,Math.min(element.clientWidth||900,1000));
      if(!Number.isFinite(original.width)||!Number.isFinite(original.height)||original.width<=0||original.height<=0)throw new Error('Invalid page dimensions');
      const scale=Math.min(2,(width/original.width)*Math.min(window.devicePixelRatio||1,2),8192/original.width,8192/original.height,Math.sqrt(8_000_000/(original.width*original.height)));
      const viewport=page.getViewport({scale});canvas.width=Math.ceil(viewport.width);canvas.height=Math.ceil(viewport.height);
      renderTask=page.render({canvasContext:canvas.getContext('2d'),viewport});await renderTask.promise;
      if(!destroyed){status.textContent='';canvas.setAttribute('aria-label','Identity document, page '+pageNumber+' of '+pdf.numPages);}
    }catch(error){if(!destroyed)status.textContent='This page could not be displayed. Download the document to view it.';}
    finally{rendering=false;if(!destroyed)update();}
  }
  previous.onclick=()=>{if(!rendering&&pageNumber>1){pageNumber--;void render();}};
  next.onclick=()=>{if(!rendering&&pdf&&pageNumber<pdf.numPages){pageNumber++;void render();}};
  update();
  import('./libs/kyc-pdf/build/pdf.mjs').then(async engine=>{
    if(destroyed)return;
    const assets=new URL('./libs/kyc-pdf/',import.meta.url);
    engine.GlobalWorkerOptions.workerSrc=new URL('build/pdf.worker.mjs',assets).href;
    loadingTask=engine.getDocument({url,isEvalSupported:false,cMapUrl:new URL('cmaps/',assets).href,cMapPacked:true,standardFontDataUrl:new URL('standard_fonts/',assets).href,wasmUrl:new URL('wasm/',assets).href});
    pdf=await loadingTask.promise;if(destroyed){await pdf.destroy();return;}await render();
  }).catch(()=>{if(!destroyed){status.textContent='This PDF is protected or could not be read. Download it to view it with your PDF reader.';toolbar.hidden=true;}});
  return ()=>{destroyed=true;renderTask?.cancel();loadingTask?.destroy().catch(()=>{});element.replaceChildren();};
};
