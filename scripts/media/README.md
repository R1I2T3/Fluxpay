# Original FluxPay wallet film

The film is rendered procedurally by render_wallet_video.py: a rotating orthographic globe,
animated cross-border paths, three currency wallets, and three cross-faded explanatory steps.
No stock footage, generative-video provider, or real customer information is used.

Outputs in frontend/fluxpay-ui/src/css/media:

- cross-border-wallet.mp4: 1280×720, 24 fps, 12 seconds, silent H.264/yuv420p.
- cross-border-wallet-mobile.mp4: purpose-designed 720×960 portrait version, same duration.
- Matching JPEG posters for loading, autoplay rejection, and reduced-motion fallback.

Two additional original background loops are rendered by render_background_loops.py:

- wallet-possibility-loop.mp4: sapphire globe, floating currency wallets and flowing transfer paths for “Life, meets possibility.”
- wallet-journey-loop.mp4: midnight-blue globe horizon and connected currency tokens for “Go where life takes you.”

Each is a six-second, 1280×720, 24 fps silent H.264 loop with fast-start metadata and a matching
JPEG poster. The composition leaves room for foreground copy. Both use the homepage's lazy-loading,
off-screen pause, global motion control and reduced-motion poster fallback.

Both files use fast-start MP4 metadata. Text describes supported features rather than promising
instant settlement, fixed fees, or guaranteed payout. All routes and activity are illustrative.

## Regenerate

Use Python 3 with Pillow, numpy and imageio-ffmpeg installed, plus Segoe UI (Windows) or DejaVu Sans.
The script also discovers project-local imageio-ffmpeg from the ignored .video-tools directory.

    python scripts/media/render_wallet_video.py
    python scripts/media/render_wallet_video.py --mobile
    python scripts/media/render_background_loops.py

Pass --poster-only to inspect a frame without encoding the whole film.
The render process exits when finished; no background server is needed.

Map source: ne_110m_land.geojson from
https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson
Natural Earth data is public domain: https://www.naturalearthdata.com/about/terms-of-use/
Source checked 2026-09-16. Geographic points and connections are illustrative, not a service-coverage map.
