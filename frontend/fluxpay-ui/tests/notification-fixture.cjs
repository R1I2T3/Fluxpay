const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript'),ko=require('knockout');
module.exports=function notifications(){
 const events=[];
 const context={exports:{},require:()=>ko,window:{dispatchEvent:event=>events.push(event),setTimeout:()=>1,clearTimeout(){}},CustomEvent:class{constructor(type,{detail}){this.type=type;this.detail=detail;}}};
 vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/ts/services/notifications.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);
 return {...context.exports,events};
};
