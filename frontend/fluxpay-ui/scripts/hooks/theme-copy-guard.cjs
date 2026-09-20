// JET 16 copies default theme assets twice during a build. On Windows the second
// unlink can fail with EBUSY. Skip only byte-identical generated theme files;
// changed assets and all other destinations keep the normal copy/error behavior.
const path = require('node:path');
const fs = require('fs-extra');
let restore;

function retryBusy(operation, eligible = () => true) {
  for(let attempt=0;;attempt++){
    try{return operation();}catch(error){
      if(error.code!=='EBUSY'||!eligible(error)||attempt===20)throw error;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)),0,0,100);
    }
  }
}

function isIdenticalThemeFile(source, destination, roots, io = fs) {
  const target = path.resolve(destination);
  if (!roots.some(root => {
    const relative = path.relative(root, target);
    return relative !== '' && !relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative);
  })) return false;
  try {
    const src = io.statSync(source), dest = io.statSync(destination);
    return src.isFile() && dest.isFile() && src.size === dest.size && retryBusy(()=>io.readFileSync(source).equals(io.readFileSync(destination)));
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

function install() {
  if (process.platform !== 'win32' || restore) return;
  const root = path.resolve(__dirname, '../..');
  const roots = ['redwood', 'stable'].map(theme => path.join(root, 'web-dev', 'css', theme));
  const original = fs.copySync;
  fs.copySync = (source, destination, options = {}) => {
    const normalized = typeof options === 'function' ? {filter: options} : options;
    return retryBusy(()=>original(source, destination, {...normalized, filter: (src, dest) => {
      const same=isIdenticalThemeFile(src,dest,roots);
      return (!normalized.filter || normalized.filter(src, dest)) && !same;
    }}),error=>typeof error.path==='string'&&roots.some(root=>{
      const relative=path.relative(root,path.resolve(error.path));
      return relative!==''&&!relative.startsWith('..'+path.sep)&&relative!=='..'&&!path.isAbsolute(relative);
    }));
  };
  restore = () => {fs.copySync = original; restore = undefined;};
}
function uninstall() { if (restore) restore(); }
module.exports = {install, uninstall, isIdenticalThemeFile};
