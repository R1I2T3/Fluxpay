const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),ts=require('typescript');
const source=fs.readFileSync(path.join(__dirname,'../src/ts/services/transaction-celebration.ts'),'utf8');
function fixture({reduced=false,hidden=false}={}){
 const frames=new Map(),events=new Map();let id=0,draws=0,clears=0;
 const add=(name,fn)=>events.set(name,fn),remove=name=>events.delete(name);
 const context2d={setTransform(){},clearRect(){clears++;},save(){},restore(){},translate(){},rotate(){},scale(){},fillRect(){draws++;}};
 const context={exports:{},require:()=>({bindingHandlers:{},utils:{domNodeDisposal:{addDisposeCallback(){}}}}),document:{hidden,addEventListener:add,removeEventListener:remove},window:{innerWidth:1440,innerHeight:900,devicePixelRatio:3,matchMedia:()=>({matches:reduced,addEventListener:add,removeEventListener:remove}),addEventListener:add,removeEventListener:remove,requestAnimationFrame:fn=>{frames.set(++id,fn);return id;},cancelAnimationFrame:id=>frames.delete(id)}};
 vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,context);
 return {...context.exports,canvas:{getContext:()=>context2d},frames,events,counts:()=>({draws,clears}),tick(time){const [key,fn]=frames.entries().next().value;frames.delete(key);fn(time);}};
}
test('paper launches inward from both corners, rises, then falls under gravity',()=>{
 const f=fixture(),papers=f.createCelebration(1000,800,()=>0.5);
 assert.equal(papers.length,120);assert.equal(f.createCelebration(390,844).length,72);
 assert.ok(papers[0].vx>0&&papers[1].vx<0);assert.equal(papers[0].x,-8);assert.equal(papers[1].x,1008);
 const rise=f.paperPosition(papers[0],0.7),peak=f.paperPosition(papers[0],1.6),fall=f.paperPosition(papers[0],3);
 assert.ok(rise.y<papers[0].y);assert.ok(fall.y>peak.y);assert.ok(rise.x>papers[0].x);
 assert.equal(f.paperPosition(papers[0],4.2).opacity,0);
});
test('animation is bounded, caps pixel density, and removes frames and listeners when complete',()=>{
 const f=fixture();f.animateCelebration(f.canvas);assert.equal(f.canvas.width,2880);
 f.tick(100);f.tick(800);assert.ok(f.counts().draws>0);f.tick(4300);
 assert.equal(f.frames.size,0);assert.equal(f.events.size,0);
});
test('dismissal, viewport changes and backgrounding stop and clear the celebration',()=>{
 for(const reason of ['dispose','resize','visibilitychange','change']){
  const f=fixture(),dispose=f.animateCelebration(f.canvas);if(reason==='dispose')dispose();else f.events.get(reason)();
  assert.equal(f.frames.size,0);assert.equal(f.events.size,0);assert.equal(f.counts().clears,1);dispose();assert.equal(f.counts().clears,1);
 }
});
test('reduced-motion and initially hidden pages never start confetti',()=>{
 for(const options of [{reduced:true},{hidden:true}]){const f=fixture(options);f.animateCelebration(f.canvas)();assert.equal(f.frames.size,0);assert.equal(f.events.size,0);}
 const css=fs.readFileSync(path.join(__dirname,'../src/css/transaction-success.css'),'utf8');
 assert.ok(css.includes('prefers-reduced-motion:reduce'));assert.ok(css.includes('stroke-dashoffset:0'));assert.ok(css.includes('pointer-events:none'));
});
