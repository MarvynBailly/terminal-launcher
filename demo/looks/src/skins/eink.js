/*
 * E-Ink: paper that happens to update.
 * Nothing here eases. A page change is a full-panel refresh (negative, black, white, page) in hard
 * steps; a small change lands at once with one frame of invert over just what changed, and what
 * was there before lingers as a ghost. All of it is drawn in the layer over the page with
 * `difference` blending: a layer of paper + ink swaps the two exactly, a dark grey one leaves a ghost.
 */
(function () {
  'use strict';
  const INK = '#16181a', PAPER = '#dfe2d7', SWAP = '#f5faf1';
  const M4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];
  /** The 8 by 8 Bayer threshold at a pixel, 0 to 1. */
  const bayer = (x, y) => (4 * M4[(y & 3) * 4 + (x & 3)] + [0, 2, 3, 1][((y >> 2) & 1) * 2 + ((x >> 2) & 1)] + 0.5) / 64;

  /** A w by h tile with an ink pixel wherever tone(x, y) beats the threshold, as a CSS image. */
  function tile(w, h, tone, thr) {
    thr = thr || bayer;
    let d = '';
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        if (tone(x, y) <= thr(x, y)) continue;
        let n = 1;
        while (x + n < w && tone(x + n, y) > thr(x + n, y)) n++;
        d += `M${x} ${y}h${n}v1h-${n}z`;
        x += n - 1;
      }
    }
    return `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='${w}' height='${h}'%3E%3Cpath d='${d}' fill='%2316181a'/%3E%3C/svg%3E")`;
  }
  /* a gauge darkens as it fills: sixteenths of ink in each of its 14 cells, on the 4 by 4 matrix so every step is a clean pattern */
  const BAR = [4, 4, 4, 6, 6, 8, 8, 8, 10, 10, 12, 12, 14, 16];
  let tiles = null;
  const TILES = () => tiles || (tiles = {
    '--eink-ramp': tile(8, 48, (x, y) => 0.1 + 0.95 * Math.max(0, (y - 7) / 34)),     // the clock's fill: ink settling downwards
    '--eink-bar': tile(56, 8, (x) => BAR[x >> 2] / 16, (x, y) => (M4[(y & 3) * 4 + (x & 3)] + 0.5) / 16),
    '--eink-fade': tile(8, 6, (x, y) => [0.5, 0.33, 0.2, 0.1, 0.05, 0.02][y]),        // the shadow under a rule
    '--eink-edge': tile(8, 7, (x, y) => [0.12, 0.3, 0.5, 0.7, 0.88, 2, 2][y]),         // where the scrollback runs out under the head
  });

  /* ---------------- the sleeping picture: a moon over hills, one bit deep ---------------- */

  const W = 195, H = 422;
  let picture = null;
  /** The picture is worked out once, as pixels; every phone on the page gets them on a canvas of its own. */
  function sleepPicture() {
    const cv = document.createElement('canvas');
    cv.width = W; cv.height = H;
    const g = cv.getContext('2d');
    if (!g) return cv;
    if (picture) { g.putImageData(picture, 0, 0); return cv; }
    const img = picture = g.createImageData(W, H), px = img.data;
    const rnd = (n) => { const s = Math.sin(n * 127.1 + 311.7) * 43758.5453; return s - Math.floor(s); };
    // stars, in the part of the sky that is solid ink
    const star = new Uint8Array(W * H);
    for (let i = 0; i < 30; i++) {
      const sx = 3 + Math.floor(rnd(i) * (W - 6)), sy = 3 + Math.floor(rnd(i + 60) * 62), big = rnd(i + 120) > 0.75;
      star[sy * W + sx] = 1;
      if (big) for (const o of [-1, 1, -W, W]) star[sy * W + sx + o] = 1;
    }
    const LAYERS = [[238, 13, 0.047, 0.3, 0.1], [264, 17, 0.031, 0.5, 0.2], [294, 19, 0.022, 0.74, 0.38], [334, 12, 0.016, 1.2, 1.2]];
    const ridge = LAYERS.map(([base, amp, f], k) => Array.from({ length: W }, (_, x) =>
      base + amp * (0.6 * Math.sin(x * f + k * 2.1) + 0.3 * Math.sin(x * f * 2.3 + k * 3.7) + 0.1 * Math.sin(x * f * 5.1 + k))));
    const PINES = [[14, 30], [24, 22], [33, 26], [151, 20], [161, 28], [172, 21], [181, 16]];
    const MX = 138, MY = 112, MR = 29;
    function tone(x, y) {
      let t = 1.3 - 1.18 * (y / 246);
      const dm = Math.hypot(x - MX, y - MY);
      if (dm < MR) {
        t = 0.36 * ((x - MX) * 0.55 + (y - MY) * 0.83) / MR - 0.06;      // a full moon, shaded towards one limb
      } else {
        t -= 0.55 * Math.exp(-(dm - MR) / 16);
        if (star[y * W + x]) t = 0;
      }
      for (let k = 0; k < 4; k++) {
        const top = ridge[k][x];
        if (y >= top) { const L = LAYERS[k]; t = L[3] - (L[3] - L[4]) * Math.min(1, (y - top) / 46); }
      }
      for (const [px0, tall] of PINES) {
        const foot = ridge[3][px0] + 2, up = y - (foot - tall);
        if (up >= 0 && y <= foot && Math.abs(x - px0) <= 0.3 + (up % 6) * 0.5 + up * 0.1) t = 1.2;
      }
      return t;
    }
    for (let y = 0, o = 0; y < H; y++) for (let x = 0; x < W; x++, o += 4) {
      const on = tone(x, y) > bayer(x, y);
      px[o] = on ? 22 : 223; px[o + 1] = on ? 24 : 226; px[o + 2] = on ? 26 : 215; px[o + 3] = 255;
    }
    g.putImageData(img, 0, 0);
    return cv;
  }

  /* ---------------- refreshing ---------------- */

  function place(node, b) {
    node.style.cssText = `left:${b.x}%;top:${b.y}%;width:${b.w}%;height:${b.h}%`;
    return node;
  }

  /**
   * Where a node's ink is (its text, not its whole row; `whole` for its box), in percent of the
   * phone. A line that was just added is measured before the page scrolls up to show it, so the
   * scroll that is about to happen is allowed for.
   */
  function inkBox(ctx, node, whole) {
    const a = ctx.root.getBoundingClientRect(), L = ctx.lines;
    let b;
    if (whole) b = node.getBoundingClientRect();
    else { const range = document.createRange(); range.selectNodeContents(node); b = range.getBoundingClientRect(); }
    const w = a.width || 1, h = a.height || 1;
    const rise = L.contains(node) ? Math.max(0, L.scrollHeight - L.clientHeight - L.scrollTop) : 0;
    return { x: ((b.left - a.left) / w) * 100 - 1.1, y: ((b.top - rise - a.top) / h) * 100, w: (b.width / w) * 100 + 2.2, h: (b.height / h) * 100 };
  }

  /** A partial refresh: one frame of invert over a box. */
  function flash(ctx, box, delay, ms) {
    const n = place(ctx.el('div', 'eink-inv'), box);
    ctx.fx.append(n);
    const gone = () => n.remove();
    ctx.animate(n, [{ opacity: 1 }, { opacity: 1 }], { duration: ms || 45, delay: delay || 0 }).then(gone, gone);
  }

  /** A ghost goes in a few steps, not smoothly. */
  function fade(ctx, g, from) {
    const gone = () => g.remove();
    ctx.animate(g, [{ opacity: from }, { opacity: 0 }], { duration: 1500, easing: 'steps(5, end)' }).then(gone, gone);
  }

  /** When a new line moves the rest of the page, the page as it was stays behind for a moment, faintly. */
  const ghosted = new WeakMap();
  function ghost(ctx, node) {
    const L = ctx.lines, now = performance.now();
    const moved = L.scrollHeight - L.clientHeight > L.scrollTop + 1;
    if (!moved || now - (ghosted.get(ctx.root) || 0) < 700) return;
    ghosted.set(ctx.root, now);
    const copy = L.cloneNode(true);
    copy.children[[...L.children].indexOf(node)].remove();
    copy.querySelectorAll('.tl-in').forEach((n) => n.classList.remove('tl-in'));
    const g = place(ctx.el('div', 'eink-ghost'), ctx.rect(L));
    g.append(copy);
    ctx.fx.append(g);
    copy.scrollTop = L.scrollTop;
    fade(ctx, g, 0.15);
  }

  /** A line that is taken away and written over ("asking…" giving way to the answer) shows through what replaces it. */
  function watchReplaced(ctx) {
    const mo = new MutationObserver((records) => {
      if (ctx.still || ctx.reduced) return;
      for (const rec of records) {
        const old = rec.removedNodes[0], above = rec.previousSibling;
        if (rec.removedNodes.length !== 1 || old.nodeType !== 1 || !above || !above.isConnected) continue;   // not the page being cleared
        const r = ctx.rect(above), g = ctx.el('div', 'eink-ghost');
        g.style.cssText = `left:${r.x}%;top:${r.y + r.h}%;width:${r.w}%`;
        old.classList.remove('tl-in');
        g.append(old);
        ctx.fx.append(g);
        fade(ctx, g, 0.4);
      }
    });
    mo.observe(ctx.lines, { childList: true });
    return () => mo.disconnect();
  }

  /**
   * A full-panel refresh in hard steps of 85 ms: the old page in negative, black, white, and then
   * (with `pale`) the new page faint for a frame before it settles. `mid` runs while the panel is white.
   */
  async function refresh(ctx, o) {
    o = o || {};
    if (ctx.reduced) { if (o.mid) o.mid(); return ctx.sleep(120); }
    const F = 85, n = o.pale ? 4 : 3, at = (i) => i / n;
    // the negative, and over it a layer that takes the colour out: another app's colours do not flash pink on a grey panel
    const neg = ctx.el('div', 'eink-neg'), grey = ctx.el('div', 'eink-grey'), flat = ctx.el('div', 'eink-flat');
    ctx.fx.append(neg, grey, flat);
    for (const layer of [neg, grey]) {
      ctx.animate(layer, [{ opacity: 1, easing: 'step-end' }, { opacity: 0, offset: at(1) }, { opacity: 0 }], { duration: F * n, fill: 'forwards' }).catch(() => {});
    }
    const frames = [
      { opacity: 0, backgroundColor: INK, easing: 'step-end' },
      { opacity: 1, backgroundColor: INK, offset: at(1), easing: 'step-end' },
      { opacity: 1, backgroundColor: PAPER, offset: at(2), easing: 'step-end' },
    ];
    if (o.pale) frames.push({ opacity: 0.6, backgroundColor: PAPER, offset: at(3), easing: 'step-end' }, { opacity: 0, backgroundColor: PAPER });
    else frames.push({ opacity: 1, backgroundColor: PAPER });
    const done = ctx.animate(flat, frames, { duration: F * n, fill: 'forwards' });
    if (o.mid) { done.catch(() => {}); await ctx.sleep(F * 2.5); o.mid(); }
    await done;
    if (!o.pale) await ctx.sleep(60);
  }

  TL.register({
    id: 'eink',
    name: 'E-Ink',
    tagline: 'Paper that happens to update',
    mood: 'A calm e-paper reader: ink on grey-green paper, greys made of dither, and a screen that refreshes instead of animating.',
    motion: [
      'Every page change is a full refresh: negative, black, white, page',
      'New text lands at once with a one-frame invert and a fading ghost',
      'Unlocking turns the clock over block by block, in dither order',
    ],
    palette: { bg: PAPER, fg: INK, dim: '#5c6059', accent: INK, app: '#34373a', ok: '#5c6059', err: '#000000' },
    light: true,
    fonts: ['IBM+Plex+Mono:ital,wght@0,400;0,500;0,600;1,400;1,600', 'Abril+Fatface'],
    hooks: {
      mount(ctx) {
        const t = TILES();
        for (const k in t) ctx.root.style.setProperty(k, t[k]);
        const sleep = ctx.el('div', 'eink-sleep');
        sleep.append(sleepPicture(), ctx.el('div', 'eink-sleep-clock', TL.content.time));
        ctx.fx.append(sleep);
        return watchReplaced(ctx);
      },

      clock(ctx, node, time) {
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        const plate = ctx.el('span', 'eink-plate');
        plate.append(ctx.el('span', 'eink-num', time));
        node.append(plate);
      },

      powerOn: (ctx) => refresh(ctx, { pale: true }),
      powerOff(ctx) {
        // the picture it is about to sleep on is drawn afresh, should the browser have let go of a canvas nobody was looking at
        const cv = ctx.fx.querySelector('.eink-sleep canvas'), g = cv && picture && cv.getContext('2d');
        if (g) g.putImageData(picture, 0, 0);
        return refresh(ctx);
      },

      line(ctx, node, d) {
        if (ctx.reduced) return;
        ghost(ctx, node);
        if (d.t === 'grid') {
          const cells = [...node.children].map((cell) => [inkBox(ctx, cell), +cell.style.getPropertyValue('--col') * 80]);
          for (const [box, delay] of cells) flash(ctx, box, delay);
        } else if (!node.classList.contains('is-blank')) {
          flash(ctx, inkBox(ctx, node, d.t === 'note'));
        }
      },

      /* The cursor moves on: where it stood, the new character shows in negative for a frame, then a ghost of the block fades. */
      key(ctx) {
        if (ctx.reduced) return;
        const r = ctx.rect(ctx.prompt.querySelector('.tl-cursor'));
        const n = place(ctx.el('div', 'eink-key'), { x: r.x - r.w, y: r.y, w: r.w, h: r.h });
        ctx.fx.append(n);
        const gone = () => n.remove();
        const grey = (v, offset) => ({ backgroundColor: `rgb(${v},${v},${v})`, offset, easing: 'step-end' });
        ctx.animate(n, [{ backgroundColor: SWAP, easing: 'step-end' }, grey(48, 0.045), grey(34, 0.17), grey(22, 0.36), grey(11, 0.62), grey(0, 1)], { duration: 1200 }).then(gone, gone);
      },

      async granted(ctx) {
        const plate = ctx.lines.querySelector('.eink-plate');
        if (plate && !ctx.reduced) {
          const COLS = 22, ROWS = 6, STEPS = 9, STEP = 50;
          const grid = place(ctx.el('div', 'eink-cells'), ctx.rect(plate));
          ctx.fx.append(grid);
          for (let r = 0; r < ROWS; r++) for (let c = 0; c < COLS; c++) {
            // a dithered wipe: left to right, each block waiting for its Bayer turn
            const k = Math.floor(((c / COLS) * 0.6 + (M4[(r & 3) * 4 + (c & 3)] / 16) * 0.4) * STEPS);
            const cell = ctx.el('i');
            grid.append(cell);
            ctx.animate(cell, [{ opacity: 0, easing: 'step-end' }, { opacity: 1 }], { duration: (k + 1) * STEP, fill: 'forwards' }).catch(() => {});
          }
          await ctx.sleep(STEPS * STEP + 140);
        }
        const word = ctx.say('access granted', 'ok');
        word.dataset.role = 'granted';
        if (!ctx.reduced) flash(ctx, inkBox(ctx, word));
        await ctx.sleep(360);
        await refresh(ctx);
      },

      /* A notification asks twice. */
      async notify(ctx, node) {
        if (ctx.reduced) return;
        await ctx.sleep(240);
        flash(ctx, inkBox(ctx, node, true), 0, 60);
        await ctx.sleep(120);
      },

      /* The line is picked, as a tapped item is on a reader: it stays inverted, then the panel refreshes. */
      async launch(ctx, line) {
        await ctx.sleep(260);
        if (!ctx.reduced) {
          const pick = place(ctx.el('div', 'eink-inv'), inkBox(ctx, line.querySelector('.tl-launch-text') || line));
          pick.style.opacity = 1;
          ctx.fx.append(pick);
        }
        await ctx.sleep(300);
        await refresh(ctx);
      },

      back: (ctx) => refresh(ctx, { pale: true, mid: () => { ctx.appview.style.visibility = 'hidden'; } }),
    },
  });
})();
