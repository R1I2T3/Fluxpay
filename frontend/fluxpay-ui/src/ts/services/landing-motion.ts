/** Homepage motion is scoped to its route and fully cleaned up on navigation. */
export class LandingMotion {
  private revealObserver?: IntersectionObserver;
  private videoObserver?: IntersectionObserver;
  private videos: HTMLVideoElement[];
  private visibleVideos = new Set<HTMLVideoElement>();
  private hero: HTMLElement | null;
  private frame = 0;
  private disposed = false;
  private onVisibility = () => this.syncVideos();
  private onScroll = () => {
    if (this.frame || this.disposed) return;
    this.frame = requestAnimationFrame(() => {
      this.frame = 0;
      this.syncHero();
    });
  };

  constructor(
    private root: HTMLElement,
    private scene: () => number,
    private paused: () => boolean,
  ) {
    this.hero = root.querySelector('.lp-hero-scroll');
    this.videos = Array.from(root.querySelectorAll<HTMLVideoElement>('video[data-src]'));
    this.videos.forEach((video) => {
      if (video.dataset.mobilePoster && window.matchMedia('(max-width: 600px)').matches) {
        video.poster = video.dataset.mobilePoster;
      }
    });
    if ('IntersectionObserver' in window) {
      this.revealObserver = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (entry.isIntersecting) {
              entry.target.classList.add('lp-revealed');
              this.revealObserver?.unobserve(entry.target);
            }
          });
        },
        { threshold: 0.06, rootMargin: '0px 0px -25px 0px' },
      );
      root.querySelectorAll<HTMLElement>('[data-reveal]').forEach((element) => {
        if (!this.paused()) element.classList.add('lp-reveal-pending');
        this.revealObserver?.observe(element);
      });
      this.videoObserver = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            const video = entry.target as HTMLVideoElement;
            if (entry.isIntersecting) this.visibleVideos.add(video);
            else this.visibleVideos.delete(video);
          });
          this.syncVideos();
        },
        { threshold: 0.05 },
      );
      this.videos.forEach((video) => this.videoObserver?.observe(video));
    } else {
      this.videos.forEach((video) => this.visibleVideos.add(video));
    }
    window.addEventListener('scroll', this.onScroll, { passive: true });
    window.addEventListener('resize', this.onScroll, { passive: true });
    document.addEventListener('visibilitychange', this.onVisibility);
    this.refresh();
  }

  refresh() {
    if (this.disposed) return;
    this.syncHero();
    this.syncVideos();
    if (this.paused()) {
      this.root
        .querySelectorAll('[data-reveal]')
        .forEach((element) => element.classList.add('lp-revealed'));
    }
  }

  private syncHero() {
    if (!this.hero) return;
    const rect = this.hero.getBoundingClientRect();
    const stage = this.hero.querySelector<HTMLElement>('.lp-hero-stage');
    const distance = Math.max(1, rect.height - (stage?.offsetHeight || window.innerHeight));
    const progress = this.paused() ? 0 : Math.max(0, Math.min(1, -rect.top / distance));
    const next = Math.max(0, Math.min(1, (progress - 0.27) / 0.38));
    const first = Math.max(0, 1 - progress / 0.37);
    this.hero.style.setProperty('--hero-progress', progress.toFixed(4));
    this.hero.style.setProperty('--hero-first', first.toFixed(4));
    this.hero.style.setProperty('--hero-next', next.toFixed(4));
    this.hero.style.setProperty('--hero-shift', (progress * -15).toFixed(2) + 'px');
    this.hero.dataset.phase = next > 0.5 ? 'next' : 'first';
    const firstCopy = this.hero.querySelector<HTMLElement>('.lp-hero-copy');
    const nextCopy = this.hero.querySelector<HTMLElement>('.lp-hero-next');
    // Hidden copy must not leave invisible links in the keyboard focus order.
    firstCopy?.toggleAttribute('inert', next > 0.5);
    nextCopy?.toggleAttribute('inert', next <= 0.5);
  }

  private syncVideos() {
    const stop = this.paused() || document.hidden;
    this.videos.forEach((video) => {
      const selected =
        video.dataset.scene === undefined || Number(video.dataset.scene) === this.scene();
      if (stop || !selected || !this.visibleVideos.has(video)) {
        video.pause();
        return;
      }
      if (!video.getAttribute('src') && video.dataset.src) {
        video.muted = true;
        video.src =
          video.dataset.mobileSrc && window.matchMedia('(max-width: 600px)').matches
            ? video.dataset.mobileSrc
            : video.dataset.src;
        video.load();
      }
      // Autoplay may be refused by the browser. The local poster remains visible.
      void video.play().catch(() => {});
    });
  }

  dispose() {
    this.disposed = true;
    cancelAnimationFrame(this.frame);
    this.revealObserver?.disconnect();
    this.videoObserver?.disconnect();
    this.videos.forEach((video) => {
      video.pause();
      video.removeAttribute('src');
      video.load();
    });
    this.visibleVideos.clear();
    window.removeEventListener('scroll', this.onScroll);
    window.removeEventListener('resize', this.onScroll);
    document.removeEventListener('visibilitychange', this.onVisibility);
  }
}
