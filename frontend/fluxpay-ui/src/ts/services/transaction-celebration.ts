import * as ko from 'knockout';

const palette = ['#4b91f1', '#55c7aa', '#90b8f3', '#edc15f', '#f4f8ff'];
export interface PaperParticle {
  x:number; y:number; vx:number; vy:number; gravity:number;
  delay:number; width:number; height:number; rotation:number; spin:number; colour:string;
}

export function createCelebration(width:number, height:number, random= Math.random):PaperParticle[] {
  const count = width < 600 ? 72 : 120;
  return Array.from({length:count}, (_, index) => {
    const left = index % 2 === 0;
    return {
      x:left ? -8 : width + 8, y:height * 0.96,
      vx:(left ? 1 : -1) * width * (0.3 + random() * 0.65),
      vy:-height * (1.08 + random() * 0.48), gravity:height * 0.91,
      delay:random() * 0.23, width:7 + random() * 5, height:4 + random() * 5,
      rotation:random() * Math.PI, spin:(random() - 0.5) * 9,
      colour:palette[index % palette.length]
    };
  });
}

export function paperPosition(paper:PaperParticle, elapsed:number) {
  const t = Math.max(0, elapsed - paper.delay);
  return {
    // Horizontal drag slows the blast; gravity brings the paper back down.
    x:paper.x + paper.vx * (1 - Math.exp(-0.8 * t)) / 0.8,
    y:paper.y + paper.vy * t + 0.5 * paper.gravity * t * t,
    rotation:paper.rotation + paper.spin * t,
    tumble:0.25 + 0.75 * Math.abs(Math.cos(t * 7 + paper.rotation)),
    opacity:elapsed < paper.delay ? 0 : Math.max(0, Math.min(1, (4.2 - elapsed) / 0.65))
  };
}

export function animateCelebration(canvas:HTMLCanvasElement):()=>void {
  const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  if (motion.matches || document.hidden) return () => {};
  const context = canvas.getContext('2d');
  if (!context) return () => {};
  let frame = 0;
  let stopped = false;
  let started:number|undefined;
  const width = window.innerWidth, height = window.innerHeight;
  const ratio = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.round(width * ratio);
  canvas.height = Math.round(height * ratio);
  const papers = createCelebration(width, height);
  const stop = () => {
    if (stopped) return;
    stopped = true;
    window.cancelAnimationFrame(frame);
    context.setTransform(1, 0, 0, 1, 0, 0);
    context.clearRect(0, 0, canvas.width, canvas.height);
    window.removeEventListener('resize', stop);
    document.removeEventListener('visibilitychange', stop);
    motion.removeEventListener('change', stop);
  };
  const draw = (timestamp:number) => {
    if (stopped) return;
    if (started === undefined) started = timestamp;
    const elapsed = (timestamp - started) / 1000;
    if (elapsed >= 4.2) { stop(); return; }
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);
    for (const paper of papers) {
      const position = paperPosition(paper, elapsed);
      if (!position.opacity || position.y > height + 20) continue;
      context.save();
      context.globalAlpha = position.opacity;
      context.translate(position.x, position.y);
      context.rotate(position.rotation);
      context.scale(1, position.tumble);
      context.fillStyle = paper.colour;
      context.fillRect(-paper.width / 2, -paper.height / 2, paper.width, paper.height);
      context.restore();
    }
    frame = window.requestAnimationFrame(draw);
  };
  window.addEventListener('resize', stop, {passive:true});
  document.addEventListener('visibilitychange', stop);
  motion.addEventListener('change', stop);
  frame = window.requestAnimationFrame(draw);
  return stop;
}

ko.bindingHandlers.transactionCelebration = {
  init(element:HTMLCanvasElement) {
    try {
      const dispose = animateCelebration(element);
      ko.utils.domNodeDisposal.addDisposeCallback(element, dispose);
    } catch (_) { /* Decorative effects must never interrupt the success receipt. */ }
  }
};
