const fs=require('fs-extra'),path=require('path');
module.exports=function(config){
  const root=path.resolve(__dirname,'../..'),stage=path.resolve(root,config.paths?.staging?.web||'web-dev');
  const dependency=path.join(root,'node_modules/pdfjs-dist'),destination=path.join(stage,'js/libs/kyc-pdf');
  for(const name of ['pdf.mjs','pdf.worker.mjs'])fs.copySync(path.join(dependency,'legacy/build',name),path.join(destination,'build',name));
  for(const directory of ['cmaps','standard_fonts','wasm'])fs.copySync(path.join(dependency,directory),path.join(destination,directory));
  fs.copySync(path.join(dependency,'LICENSE'),path.join(destination,'LICENSE'));
  fs.copySync(path.join(root,'src/js/kyc-pdf-preview.mjs'),path.join(stage,'js/kyc-pdf-preview.mjs'));
};
