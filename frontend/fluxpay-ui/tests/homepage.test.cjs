const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const source = fs.readFileSync(path.join(__dirname, '../src/ts/services/landing-motion.ts'), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020}}).outputText;

function fixture(paused = false, mobile = false) {
  let selected = 0;
  const observers = [];
  const events = new Map();
  const styles = new Map();
  const attributes = [new Map(), new Map()];
  const reveal = {classList: {add() {}}};
  const films = [0, 1, 2].map(index => ({
    dataset: {scene: String(index), src: 'scene-' + index + '.mp4'},
    src: '', plays: 0, pauses: 0, loads: 0,
    getAttribute(name) { return this[name] || null; },
    removeAttribute(name) { this[name] = ''; },
    load() { this.loads++; },
    play() { this.plays++; return Promise.resolve(); },
    pause() { this.pauses++; }
  }));
  if(mobile) Object.assign(films[0].dataset,{mobileSrc:'portrait.mp4',mobilePoster:'portrait.jpg'});
  const hero = {
    dataset: {}, top: 0,
    style: {setProperty: (key, value) => styles.set(key, value)},
    getBoundingClientRect() {return {top:this.top, height:1440};},
    querySelector(selector) {
      if (selector === '.lp-hero-stage') return {offsetHeight:800};
      const map = attributes[selector === '.lp-hero-copy' ? 0 : 1];
      return {toggleAttribute:(key,value) => map.set(key,value)};
    }
  };
  const document = {
    hidden:false,
    addEventListener:(name,fn) => events.set(name,fn),
    removeEventListener:(name) => events.delete(name)
  };
  const window = {
    innerHeight:800, IntersectionObserver:true,
    matchMedia:()=>({matches:mobile}),
    addEventListener:(name,fn) => events.set(name,fn),
    removeEventListener:(name) => events.delete(name)
  };
  class Observer {
    constructor(callback) {this.callback=callback;this.disconnected=false;observers.push(this);}
    observe() {}
    unobserve() {}
    disconnect() {this.disconnected=true;}
  }
  const root = {
    querySelector:() => hero,
    querySelectorAll:selector => selector === 'video[data-src]' ? films : [reveal]
  };
  const context = {exports:{},window,document,IntersectionObserver:Observer,requestAnimationFrame:()=>1,cancelAnimationFrame(){}};
  vm.runInNewContext(compiled,context);
  const motion = new context.exports.LandingMotion(root,()=>selected,()=>paused);
  return {motion,films,observers,events,document,hero,styles,attributes,
    visible() {observers[1].callback(films.map(target=>({target,isIntersecting:true})));},
    select(index) {selected=index;motion.refresh();},
    pause(value) {paused=value;motion.refresh();}
  };
}

test('videos do not download before entering the viewport',()=>{
  const f=fixture();
  assert.ok(f.films.every(film=>film.src==='' && film.plays===0));
  f.motion.dispose();
});
test('only the selected scene loads and plays',()=>{
  const f=fixture();f.visible();
  assert.equal(f.films[0].src,'scene-0.mp4');
  assert.equal(f.films[1].src,'');
  f.select(2);
  assert.equal(f.films[2].src,'scene-2.mp4');
  assert.ok(f.films[0].pauses>0);
  assert.equal(f.films[1].plays,0);
  f.motion.dispose();
});
test('reduced motion prevents video loads',()=>{
  const f=fixture(true);f.visible();
  assert.ok(f.films.every(film=>film.src==='' && film.plays===0));
  assert.equal(f.styles.get('--hero-progress'),'0.0000');
  f.motion.dispose();
});
test('standalone wallet film plays independently of the landscape tabs',()=>{
  const f=fixture();
  delete f.films[1].dataset.scene;
  f.visible();
  assert.ok(f.films[1].plays>0);
  const before=f.films[1].plays;
  f.select(2);
  assert.ok(f.films[1].plays>before);
  f.pause(true);
  assert.ok(f.films[1].pauses>0);
  f.motion.dispose();
});
test('phone view selects the portrait film and poster',()=>{
  const f=fixture(false,true);
  assert.equal(f.films[0].poster,'portrait.jpg');
  f.visible();
  assert.equal(f.films[0].src,'portrait.mp4');
  f.motion.dispose();
});
test('pause and hidden-page states stop playback',()=>{
  const f=fixture();f.visible();const before=f.films[0].plays;
  f.pause(true);assert.equal(f.films[0].plays,before);
  f.pause(false);assert.ok(f.films[0].plays>before);
  const after=f.films[0].plays;
  f.document.hidden=true;f.events.get('visibilitychange')();
  assert.equal(f.films[0].plays,after);f.motion.dispose();
});
test('off-screen video is paused',()=>{
  const f=fixture();f.visible();const before=f.films[0].plays;
  f.observers[1].callback(f.films.map(target=>({target,isIntersecting:false})));
  assert.equal(f.films[0].plays,before);assert.ok(f.films[0].pauses>0);
  f.motion.dispose();
});
test('scroll progress switches copy and removes hidden links from focus order',()=>{
  const f=fixture();f.hero.top=-500;f.motion.refresh();
  assert.equal(f.hero.dataset.phase,'next');
  assert.equal(f.attributes[0].get('inert'),true);
  assert.equal(f.attributes[1].get('inert'),false);
  f.hero.top=0;f.motion.refresh();
  assert.equal(f.hero.dataset.phase,'first');f.motion.dispose();
});
test('route teardown removes listeners, observers, and media sources',()=>{
  const f=fixture();f.visible();f.motion.dispose();
  assert.equal(f.events.size,0);
  assert.ok(f.observers.every(observer=>observer.disconnected));
  assert.ok(f.films.every(film=>film.src===''));
  const count=f.films[0].plays;f.motion.refresh();
  assert.equal(f.films[0].plays,count);
});
test('all homepage route links are actual FluxPay routes, and media is local',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../src/ts/views/home.html'),'utf8');
  const routes=new Set(['home','register','login','dashboard','wallets','payments-new','payments-list','recipients','tracking','kyc','account']);
  for(const match of html.matchAll(/data-route="([^"]+)"/g)) assert.ok(routes.has(match[1]),match[1]);
  for(const match of html.matchAll(/(?:data-src|poster)="([^"]+)"/g)) assert.ok(fs.existsSync(path.join(__dirname,'../src',match[1])),match[1]);
  assert.ok(html.includes('aria-controls="scene-description"'));
});

test('both lifestyle sections use small, independently controlled local wallet loops',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../src/ts/views/home.html'),'utf8');
  for(const [section,name] of [['lp-life','wallet-possibility-loop'],['lp-ending','wallet-journey-loop']]) {
    const sectionHtml=html.match(new RegExp('<section class="'+section+'[\\s\\S]*?</section>'))?.[0];
    assert.ok(sectionHtml,section);
    const videos=sectionHtml.match(/<video\b[\s\S]*?<\/video>/g);
    assert.equal(videos?.length,1);
    const video=videos[0];
    assert.ok(video.includes('data-src="css/media/'+name+'.mp4"'));
    assert.ok(video.includes('poster="css/media/'+name+'.jpg"'));
    assert.ok(!video.includes('data-scene='));
    for(const attribute of ['muted','loop','playsinline','preload="none"']) assert.ok(video.includes(attribute));
    const bytes=fs.statSync(path.join(__dirname,'../src/css/media/'+name+'.mp4')).size;
    assert.ok(bytes>10000 && bytes<3*1024*1024,name+' stays under 3 MiB');
  }
});
