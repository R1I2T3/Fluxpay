/** Progressive enhancement: content stays visible if motion is unavailable. */
export function initializeMotion() {
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-revealed');
        observer.unobserve(entry.target);
      }
    });
  }, {threshold: 0.08, rootMargin: '0px 0px -25px 0px'});
  const attach = () => {
    document.querySelectorAll('.landing-intro,.feature-panel,.security-section,.final-cta').forEach(element => {
      if (element.classList.contains('reveal-target')) return;
      element.classList.add('reveal-target');
      if (!reduced.matches) {
        element.classList.add('motion-ready');
        observer.observe(element);
      }
    });
  };
  const root = document.getElementById('main');
  if (root) new MutationObserver(attach).observe(root, {childList: true, subtree: true});
  attach();
  let scheduled = false;
  window.addEventListener('scroll', () => {
    if (scheduled || reduced.matches) return;
    scheduled = true;
    requestAnimationFrame(() => {
      const hero = document.querySelector<HTMLElement>('.landing-hero');
      if (hero && window.scrollY < hero.offsetHeight) {
        hero.style.setProperty('--hero-scroll', Math.min(window.scrollY * 0.13, 90) + 'px');
      }
      scheduled = false;
    });
  }, {passive: true});
}
