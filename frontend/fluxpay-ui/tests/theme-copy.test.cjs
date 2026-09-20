const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {isIdenticalThemeFile}=require('../scripts/hooks/theme-copy-guard.cjs');
test('Windows theme guard skips only identical files inside generated theme directories',()=>{
  const root=path.resolve('web-dev/css/redwood');
  const target=path.join(root,'16.1.8/web/images/avatar.png');
  const io={statSync:()=>({isFile:()=>true,size:3}),readFileSync:()=>Buffer.from('abc')};
  assert.equal(isIdenticalThemeFile('source',target,[root],io),true);
  assert.equal(isIdenticalThemeFile('source',path.resolve('src/css/redwood/avatar.png'),[root],io),false);
  assert.equal(isIdenticalThemeFile('source',path.resolve('web-dev/css/redwood-neighbor/avatar.png'),[root],io),false);
  assert.equal(isIdenticalThemeFile('source',target,[root],{...io,readFileSync:file=>Buffer.from(file==='source'?'abc':'xyz')}),false);
  assert.equal(isIdenticalThemeFile('source',target,[root],{...io,statSync:()=>{throw Object.assign(Error('missing'),{code:'ENOENT'});}}),false);
  assert.throws(()=>isIdenticalThemeFile('source',target,[root],{...io,readFileSync:()=>{throw Object.assign(Error('permission denied'),{code:'EPERM'});}}),/permission denied/);
});
test('theme copy retries transient EBUSY only inside generated theme output and restores the copier',{skip:process.platform!=='win32'},()=>{
  const fs=require('fs-extra'),guard=require('../scripts/hooks/theme-copy-guard.cjs');
  const original=fs.copySync;let calls=0;
  const target=path.resolve(__dirname,'../web-dev/css/redwood/16.1.8/web/images/avatar.png');
  const fake=()=>{calls++;if(calls===1)throw Object.assign(Error('busy'),{code:'EBUSY',path:target});return 'copied';};
  try{
    fs.copySync=fake;guard.install();assert.equal(fs.copySync('source',target),'copied');assert.equal(calls,2);guard.uninstall();assert.equal(fs.copySync,fake);
    calls=0;fs.copySync=()=>{calls++;throw Object.assign(Error('outside scope'),{code:'EBUSY',path:path.resolve('src/logo.png')});};guard.install();assert.throws(()=>fs.copySync('source','src/logo.png'),/outside scope/);assert.equal(calls,1);
  }finally{guard.uninstall();fs.copySync=original;}
});
