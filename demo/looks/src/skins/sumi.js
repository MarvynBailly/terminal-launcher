/*
 * Sumi: ink on paper. The script draws what a stylesheet cannot: the wash behind the page, each
 * gauge as one brush stroke that runs dry, the seal, and the ink that crosses the page when the
 * phone wakes, unlocks, opens an app and locks.
 */
(function () {
  'use strict';

  const PAPER = [241, 236, 223];      // --bg
  const INK = [30, 28, 26];           // a loaded brush
  const NIGHT = [16, 18, 22];         // the ground of the app that opens: the bloom ends in it
  const WASH = [104, 100, 93];        // ink let down with water
  const RED = [196, 67, 42];          // --err

  /* the same numbers every time: a still and a film must be the same picture */
  function rng(seed) {
    return function () {
      seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const sat = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
  const fix = (v) => Math.round(v * 10) / 10;

  /* ---------------- a gauge: one stroke, heavy where the brush lands, dry where it lifts ---------------- */

  let strokes = 0;
  function stroke(line) {
    const bar = line.querySelector && line.querySelector('.tl-gauge-bar');
    if (!bar || bar.querySelector('svg')) return;
    const pct = Math.max(4, Math.min(100, parseFloat(line.style.getPropertyValue('--pct')) || 0));
    let seed = Math.round(pct * 131);
    for (const ch of line.textContent) seed = (seed * 31 + ch.charCodeAt(0)) | 0;
    const r = rng(seed), hairs = 9;
    const phase = r() * 6.28, sway = 0.25 + r() * 0.3, tilt = (r() - 0.5) * 0.7;
    const lean = sway * Math.sin(phase) - tilt / 2;
    // where the brush lands: a rounded head that the hairs run out of (wound the same way as they are)
    const paths = ['M6 ' + fix(8.9 + lean) + 'Q.8 ' + fix(8.9 + lean) + ' .3 ' + fix(5.1 + lean) + 'T5.5 ' + fix(1.1 + lean) + 'Z'];
    for (let k = 0; k < hairs; k++) {
      const off = (k / (hairs - 1)) * 2 - 1;                          // -1 the top hair, 1 the bottom one
      const reach = pct * (1.04 - 0.14 * Math.pow(Math.abs(off), 1.5) - r() * 0.08);   // where this hair runs out of ink
      const load = 0.96 + r() * 0.3;
      let top = [], bot = [], from = '', to = '';
      const lift = () => {       // each run of a hair ends in a point, as a streak does
        if (top.length) paths.push('M' + from + 'L' + top.join('L') + 'L' + to + 'L' + bot.reverse().join('L') + 'Z');
        top = []; bot = [];
      };
      // the brush lands on its belly: the middle hairs touch first, the lower ones a little late
      for (let x = 2 * off * off + 0.5 * (off + 1) + r() * 0.5; x <= reach;) {
        const p = x / reach;
        const press = 1 + 0.26 * Math.exp(-x / 4) - 0.3 * p * p;       // heavy where it lands, lifting as it goes
        const y = 5 + off * 3.05 * press + sway * Math.sin(x * 0.07 + phase) + tilt * (p - 0.5) + (r() - 0.5) * 0.14;
        const dry = Math.pow(p, 4);
        const half = 0.6 * load * (1 - 0.88 * dry) + (r() - 0.5) * (0.07 + 0.5 * dry);
        if (half < 0.15 || (p > 0.7 && r() < dry * 0.3)) lift();
        else {
          if (!top.length) from = fix(x - 0.9) + ' ' + fix(y);
          to = fix(x + 0.9) + ' ' + fix(y);
          top.push(fix(x) + ' ' + fix(y - half)); bot.push(fix(x) + ' ' + fix(y + half));
        }
        x += p < 0.6 ? 2.6 : 1.2;
      }
      lift();
    }
    const id = 'sumi-g' + (++strokes);
    const tones = line.classList.contains('is-alert') ? ['#a8311b', '#c4432a', '#d9694f'] : ['#12110f', '#2a2826', '#56524c'];
    bar.insertAdjacentHTML('beforeend',
      '<svg class="sumi-stroke" viewBox="0 0 100 10" preserveAspectRatio="none" aria-hidden="true">' +
      '<linearGradient id="' + id + '" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="' + pct + '" y2="0">' +
      '<stop offset="0" stop-color="' + tones[0] + '"/><stop offset=".62" stop-color="' + tones[1] + '"/><stop offset="1" stop-color="' + tones[2] + '"/></linearGradient>' +
      '<path fill="url(#' + id + ')" d="' + paths.join('') + '"/></svg>');
  }

  /* ---------------- the seal: a square of vermilion with the prompt cut out of it ---------------- */

  const SEAL =
    '<svg viewBox="0 0 40 40" aria-hidden="true">' +
    '<filter id="sumi-sf" x="-.15" y="-.15" width="1.3" height="1.3" color-interpolation-filters="sRGB">' +
    '<feTurbulence type="fractalNoise" baseFrequency=".2" numOctaves="2" seed="5" result="a"/>' +
    '<feDisplacementMap in="SourceGraphic" in2="a" scale="2.4" xChannelSelector="R" yChannelSelector="G" result="b"/>' +
    '<feTurbulence type="fractalNoise" baseFrequency=".75" numOctaves="2" seed="11"/>' +
    '<feColorMatrix values="0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 -7 0 0 0 5.35" result="c"/>' +
    '<feComposite in="b" in2="c" operator="in"/></filter>' +
    '<mask id="sumi-sm"><rect width="40" height="40" fill="#fff"/>' +
    '<path d="M11.5 11.5l9.5 8-9.5 8M22.5 29h7.5" fill="none" stroke="#000" stroke-width="4" stroke-linecap="square"/></mask>' +
    '<g filter="url(#sumi-sf)"><rect x="3.5" y="3.5" width="33" height="33" rx="2.6" fill="#c4432a" mask="url(#sumi-sm)"/></g></svg>';
  const SEAL_H = (19 * 9) / 19.5;     // the pressed seal is 19cqw square: its height in percent of the phone's

  /* ---------------- the wash behind the page ---------------- */

  const tone = (a) => 'rgba(48,46,43,' + a + ')';

  /* A far ridge: darkest along its crest, thinning downwards into mist, and lost in places. */
  function ridge(g, w, h, u, o) {
    const layer = document.createElement('canvas');
    layer.width = w; layer.height = h;
    const q = layer.getContext('2d'), r = rng(o.seed);
    const big = Array.from({ length: 14 }, r), small = Array.from({ length: 44 }, r);
    const at = (a, x) => { const i = Math.max(0, Math.min(a.length - 2, Math.floor(x))); return a[i] + (a[i + 1] - a[i]) * (x - i); };
    const crest = (x) => {
      let v = o.rough * (at(big, x * 12) + 0.32 * at(small, x * 42));
      for (const [c, a, s] of o.peaks) v += a * Math.exp(-Math.pow(Math.abs(x - c) / s, 1.6));
      return o.y * h - v * u;
    };
    const line = () => { q.beginPath(); for (let i = -9; i <= 189; i++) q.lineTo((i / 180) * w, crest(i / 180)); };
    line();
    q.lineTo(w * 1.05, h); q.lineTo(-w * 0.05, h); q.closePath();
    q.clip();
    q.lineJoin = 'round';
    for (let k = 1; k <= 16; k++) {
      line();
      q.lineWidth = 2 * o.depth * u * Math.pow(k / 16, 1.5);
      q.strokeStyle = tone(o.ink / 16);
      q.stroke();
    }
    line();
    q.lineWidth = 0.45 * u; q.strokeStyle = tone(o.ink * 0.8); q.stroke();
    const mist = q.createLinearGradient(0, 0, w, 0);
    for (const [x, a] of o.mist) mist.addColorStop(x, 'rgba(0,0,0,' + a + ')');
    q.globalCompositeOperation = 'destination-in';
    q.fillStyle = mist; q.fillRect(0, 0, w, h);
    g.drawImage(layer, 0, 0);
  }

  function art(g, w, h) {
    const u = w / 100;
    g.clearRect(0, 0, w, h);
    if (w < 16) return;
    // the moon: one pass of very thin ink, pooled a little towards its edge
    const mx = 70 * u, my = 0.628 * h, mr = 10.5 * u;
    g.beginPath();
    for (let i = 0; i < 96; i++) {
      const t = (i / 96) * 6.2832, rr = mr * (1 + 0.011 * Math.sin(3 * t + 1) + 0.006 * Math.sin(7 * t + 4));
      g.lineTo(mx + rr * Math.cos(t), my + rr * Math.sin(t));
    }
    const disc = g.createRadialGradient(mx - mr * 0.22, my - mr * 0.3, mr * 0.1, mx, my, mr * 1.02);
    [[0, 0.04], [0.72, 0.075], [0.95, 0.115], [1, 0.15]].forEach(([at, a]) => disc.addColorStop(at, tone(a)));
    g.fillStyle = disc; g.fill();
    ridge(g, w, h, u, { seed: 21, y: 0.738, rough: 1.5, depth: 11, ink: 0.15, peaks: [[0.2, 8.5, 0.17], [0.47, 3.4, 0.1], [0.92, 5.6, 0.16]], mist: [[0, 0.8], [0.28, 1], [0.52, 0.55], [0.68, 0.1], [0.84, 0.45], [1, 0.8]] });
    ridge(g, w, h, u, { seed: 8, y: 0.812, rough: 2.1, depth: 14, ink: 0.21, peaks: [[0, 2.6, 0.2], [0.66, 7.6, 0.2], [1.06, 5, 0.15]], mist: [[0, 0.08], [0.28, 0.3], [0.52, 0.95], [0.8, 1], [1, 0.6]] });
  }

  /* ---------------- the ink that crosses the page ---------------- */

  /* Smooth noise over a w by h field, 0 to 1. Clouds for ink finding its way, streaks for the hairs of a brush. */
  function noise(w, h, fx, fy, octaves, seed) {
    const lat = Float32Array.from({ length: 4096 }, rng(seed)), out = new Float32Array(w * h);
    let lo = 9, hi = -9;
    for (let y = 0, i = 0; y < h; y++) for (let x = 0; x < w; x++, i++) {
      let v = 0, amp = 0.5, sx = x * fx, sy = y * fy;
      for (let o = 0; o < octaves; o++, amp *= 0.5, sx *= 2, sy *= 2) {
        const x0 = Math.floor(sx), y0 = Math.floor(sy), k = x0 + y0 * 71 + o * 1013;
        let tx = sx - x0, ty = sy - y0;
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty);
        const a = lat[k & 4095], b = lat[(k + 1) & 4095], c = lat[(k + 71) & 4095], d = lat[(k + 72) & 4095];
        const top = a + (b - a) * tx;
        v += amp * (top + (c + (d - c) * tx - top) * ty);
      }
      out[i] = v;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
    return out.map((v) => (v - lo) / (hi - lo));
  }

  /*
   * One small canvas over the page, drawn large and soft. Most transitions are the same idea: a
   * field says how late the ink reaches each point, a front T moves through it, and what is drawn
   * at a point depends on how far behind the front it is. Locking is the other way round: the marks
   * already on the page are let go into water, and spread and thin there.
   */
  function inkwell(ctx) {
    const W = 150, H = 325, N = W * H;
    const cv = ctx.el('canvas', 'sumi-ink');
    cv.width = W; cv.height = H;
    ctx.fx.append(cv);
    const g = cv.getContext('2d'), img = g.createImageData(W, H), d = img.data;
    const f = new Float32Array(N), win = new Float32Array(N);
    let cloud = null, hair = null, job = null;
    const clouds = () => cloud || (cloud = noise(W, H, 3.4 / W, 3.4 / W, 5, 11));

    function draw(T, m) {
      const kind = job.kind, c = job.ink;
      for (let i = 0, j = 0; i < N; i++, j += 4) {
        const u = T - f[i];
        let a = 0, k = 1;                 // how much is painted here, and how much of that is ink rather than paper
        if (kind === 'wake') {
          // ahead of the front the paper is blank; at the front the ink is thick; behind it dries off the page
          if (u < 0) { a = 1; k = u < -0.08 ? 0 : 0.9 * (1 + u * 12.5) * (1 + u * 12.5); }
          else { const q = 1 - u / 0.85; a = q > 0 ? 0.9 * Math.pow(q, 1.2 + 2.6 * (1 - cloud[i])) : 0; }
        } else if (kind === 'bloom') {
          // black, with a thin grey fringe of water running ahead of it
          a = u > 0.014 ? 1 : u > 0 ? 0.3 + 50 * u : u > -0.06 ? 0.3 * (1 + u / 0.06) * (1 + u / 0.06) : 0;
        } else if (u > 0) {
          // a broad wet brush: darkest at its ragged front, each hair leaving its own tone and drying at its own pace.
          // Behind the front the old page is painted out with paper; once the hook lets go, only wash is left.
          const q = u < 0.14 ? 1 : 1 - (u - 0.14) / 0.6;
          k = q <= 0 ? 0 : (0.35 + 0.55 * hair[i] + (u < 0.08 ? 0.45 * (1 - u / 0.08) : 0)) * Math.pow(q, 0.7 + 2.4 * (1 - hair[i]));
          if (k > 1) k = 1;
          a = (u < 0.03 ? u / 0.03 : 1) * win[i];
          if (job.free) { a *= k; k = 1; }
        }
        d[j] = PAPER[0] + (c[0] - PAPER[0]) * k; d[j + 1] = PAPER[1] + (c[1] - PAPER[1]) * k; d[j + 2] = PAPER[2] + (c[2] - PAPER[2]) * k;
        d[j + 3] = a * m * 255;
      }
      g.putImageData(img, 0, 0);
    }

    /* Pigment in water: four planes (red, green, blue, how much), spread a little every frame and sinking slightly. */
    function seep(j, now) {
      const t = now - j.t0, tmp = j.tmp;
      for (const due = Math.min(260, Math.floor(t * 0.085)); j.passes < due; j.passes++) {
        for (const plane of j.planes) {
          for (let i = 1; i < N - 1; i++) tmp[i] = 0.25 * plane[i - 1] + 0.5 * plane[i] + 0.25 * plane[i + 1];
          for (let i = W; i < N - W; i++) plane[i] = 0.3 * tmp[i - W] + 0.5 * tmp[i] + 0.2 * tmp[i + W];
        }
      }
      const [R, G, B, A] = j.planes;
      const gain = sat(t / 240) * Math.pow(1 - sat((t - 300) / (j.dur - 300)), 1.4) * 255;
      for (let i = 0, k = 0; i < N; i++, k += 4) {
        const a = A[i];
        if (a < 0.003) { d[k + 3] = 0; continue; }
        d[k] = R[i] / a; d[k + 1] = G[i] / a; d[k + 2] = B[i] / a;
        d[k + 3] = Math.min(255, a * gain * (0.7 + 0.6 * cloud[i]));
      }
      g.putImageData(img, 0, 0);
    }

    function stop() { job = null; g.clearRect(0, 0, W, H); }

    function frame(now) {
      const j = job;
      if (!j) return;
      // the demo went somewhere else while the ink was moving: leave nothing on the page
      if (!j.free && j.scene !== ctx.root.dataset.scene) return stop();
      if (j.planes) return now - j.t0 < j.dur ? seep(j, now) : stop();
      if (j.gone) {
        const m = 1 - (now - j.gone) / j.thin;
        return m > 0 ? draw(j.at, m) : stop();
      }
      const p = sat((now - j.t0) / j.dur);
      if (!j.held) {
        j.at = j.T(p);
        draw(j.at, j.M ? j.M(p) : 1);
      }
      if (p >= 1) { if (j.hold) j.held = true; else stop(); }
    }
    ctx.onFrame(frame);

    const begin = (o) => {
      job = Object.assign({ t0: performance.now(), scene: ctx.root.dataset.scene }, o);
      frame(job.t0);
    };

    return {
      canvas: cv,
      gen: 0,
      stop,
      run: begin,
      /** How late ink from a point (in fractions of the page) reaches everywhere else: 0 there, 1 at the farthest. */
      from(px, py, rough) {
        clouds();
        let hi = 0;
        for (let y = 0, i = 0; y < H; y++) for (let x = 0; x < W; x++, i++) {
          f[i] = Math.hypot(x - px * W, y - py * H) * (1 + rough * (cloud[i] - 0.5));
          if (f[i] > hi) hi = f[i];
        }
        for (let i = 0; i < N; i++) f[i] /= hi;
      },
      /** A brush as broad as the band from y0 to y1 (percent of the page), moving left to right. */
      across(y0, y1) {
        hair = hair || noise(W, H, 1.1 / W, 80 / H, 2, 5);
        const a = (y0 / 100) * H, b = (y1 / 100) * H, mid = (a + b) / 2, span = (b - a) / 2;
        for (let y = 0, i = 0; y < H; y++) for (let x = 0; x < W; x++, i++) {
          const lag = Math.abs(y - mid) / span;
          f[i] = x / W + 0.09 * (0.5 - hair[i]) + 0.05 * lag * lag;
          win[i] = sat((Math.min(y - a, b - y) - 7.5 * hair[i]) / 3);
        }
      },
      /** Lets go of the marks on the page: each a box in percent of the page, the colour of its ink and how much of the box it covers. */
      melt(marks, dur) {
        clouds();
        const planes = [0, 1, 2, 3].map(() => new Float32Array(N));
        for (const m of marks) {
          const x0 = Math.max(0, Math.round((m.x / 100) * W)), x1 = Math.min(W, Math.round(((m.x + m.w) / 100) * W));
          const y0 = Math.max(0, Math.round((m.y / 100) * H)), y1 = Math.min(H, Math.max(Math.round(((m.y + m.h) / 100) * H), y0 + 1));
          for (let y = y0; y < y1; y++) for (let i = y * W + x0; i < y * W + x1; i++) {
            if (planes[3][i]) continue;
            planes[3][i] = m.a;
            for (let c = 0; c < 3; c++) planes[c][i] = m.ink[c] * m.a;
          }
        }
        begin({ planes, tmp: new Float32Array(N), passes: 0, dur });
      },
      /** The hook is done with the page; what ink is left finishes by itself. */
      free() { if (job) { job.free = true; frame(performance.now()); } },
      thin(ms) { if (job) { job.free = true; job.gone = performance.now(); job.thin = ms; } },
    };
  }

  /* What is written on the page, as marks for the ink to let go of: one for each run of text and each stroke. */
  function marks(ctx) {
    const page = ctx.root.getBoundingClientRect(), top = ctx.lines.getBoundingClientRect().top, list = [];
    const range = document.createRange();
    ctx.screen.querySelectorAll('.tl-status > span, .tl-line > span').forEach((node) => {
      const brush = node.querySelector('.sumi-stroke'), line = node.parentNode;
      range.selectNodeContents(node);
      const r = (brush ? node : range).getBoundingClientRect();
      if (!r.width || r.top > page.bottom || (line !== ctx.status && r.top + r.height / 2 < top)) return;
      const mark = { x: ((r.left - page.left) / page.width) * 100, w: (r.width / page.width) * 100, y: ((r.top - page.top) / page.height) * 100, h: (r.height / page.height) * 100 };
      if (brush) {
        mark.w *= (parseFloat(line.style.getPropertyValue('--pct')) || 0) / 100;
        mark.ink = line.classList.contains('is-alert') ? RED : INK;
        mark.a = 0.8;
      } else {
        if (!node.textContent.trim()) return;
        mark.y += mark.h * 0.3; mark.h *= 0.46;
        mark.ink = (getComputedStyle(node).color.match(/[\d.]+/g) || INK).slice(0, 3).map(Number);
        mark.a = 0.38;
      }
      list.push(mark);
    });
    return list;
  }

  /*
   * A transition that owns the ink. If the demo is cancelled part-way, the ink goes with it.
   * Where motion is to be kept low, `calm` names the part of the phone that simply fades instead.
   */
  function withInk(fn, calm) {
    return async function (ctx, ...args) {
      const ink = ctx.sumi;
      if (!ink) return;
      if (ctx.reduced) return calm && ctx.animate(ctx[calm], [{ opacity: 1 }, { opacity: 0 }], { duration: 240, fill: 'forwards' });
      const mine = ++ink.gen;
      try {
        await fn(ctx, ink, ...args);
      } catch (e) {
        if (mine === ink.gen) ink.stop();
        throw e;
      }
    };
  }

  const gone = () => {};

  /* a drop of ink falls to a point of the page (percent) */
  async function drop(ctx, x, y, ms) {
    const node = ctx.el('i', 'sumi-drop');
    node.style.left = x + '%'; node.style.top = y + '%';
    ctx.fx.append(node);
    await ctx.animate(node, [
      { transform: 'translate(-50%, -16cqh) scale(.55, 1.7)', opacity: 0 },
      { opacity: 1, offset: 0.35 },
      { transform: 'translate(-50%, -50%)', opacity: 1 },
    ], { duration: ms, easing: 'cubic-bezier(.6, 0, 1, .55)', fill: 'forwards' });
    return node;
  }

  /* a soft mark that spreads and is taken up by the paper */
  function dab(ctx, x, y, wide, ms) {
    const node = ctx.el('i', 'sumi-dab' + (wide ? ' is-wide' : ''));
    node.style.left = x + '%'; node.style.top = y + '%';
    ctx.fx.append(node);
    ctx.animate(node, [
      { transform: 'translate(-50%, -50%) scale(.3)', opacity: wide ? 0.9 : 0.75 },
      { transform: 'translate(-50%, -50%)', opacity: 0 },
    ], { duration: ms, easing: 'cubic-bezier(.2, .7, .3, 1)' }).then(() => node.remove(), () => node.remove());
  }

  TL.register({
    id: 'sumi',
    name: 'Sumi',
    tagline: 'Ink on paper, and a lot of empty space',
    mood: 'Quiet and deliberate: a page of handmade paper where every gauge is a single brush stroke and nothing hurries.',
    motion: [
      'A wash of ink bleeds in from the corner and dries off the page',
      'A vermilion seal is pressed by the clock, then a broad brush wipes the page',
      'A drop of ink falls on the app\'s line and blooms until the page is black',
    ],
    palette: { bg: '#f1ecdf', fg: '#33312d', dim: '#7a746a', accent: '#161513', app: '#2b3f6c', ok: '#4e6a58', err: '#c4432a' },
    light: true,
    fonts: ['Shippori+Mincho:wght@400;500', 'DM+Mono:wght@400;500'],
    hooks: {
      mount(ctx) {
        // The wash is painted once, and again when the phone changes size or the browser has dropped the
        // canvas (it does on a page of many canvases, and a still has no frame in which to notice).
        const wash = ctx.el('canvas', 'sumi-art');
        ctx.bg.append(wash);
        const paint = (again) => {
          const box = ctx.root.getBoundingClientRect(), dpr = Math.min(2, window.devicePixelRatio || 1);
          const w = Math.max(1, Math.round(box.width * dpr)), h = Math.max(1, Math.round(box.height * dpr));
          if (again !== true && wash.width === w && wash.height === h) return;
          wash.width = w; wash.height = h;
          art(wash.getContext('2d'), w, h);
        };
        paint();
        wash.addEventListener('contextrestored', () => paint(true));
        const sized = window.ResizeObserver ? new ResizeObserver(paint) : null;
        if (sized) sized.observe(ctx.root);

        // the seal, kept small in the corner while the phone is open; and the faces, asked for before they are needed
        const mark = ctx.el('div', 'sumi-mark'), faces = ctx.el('div', 'sumi-faces');
        mark.innerHTML = SEAL;
        faces.innerHTML = '0123456789: <b>Wed 18 Nov</b> <i>$</i>';
        ctx.bg.append(mark, faces);

        // Gauges are drawn as their lines arrive, here rather than in the line hook, so that a still has its strokes too.
        const drawn = new MutationObserver((list) => list.forEach((m) => m.addedNodes.forEach(stroke)));
        drawn.observe(ctx.lines, { childList: true });

        if (!ctx.still) ctx.sumi = inkwell(ctx);
        return () => { drawn.disconnect(); if (sized) sized.disconnect(); };
      },

      clock(ctx, node, time) {
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        const face = ctx.el('span', 'sumi-time');
        for (const ch of time) face.append(ctx.el('span', '', ch));
        node.append(face);
      },

      /* a wash of ink bleeds across blank paper from the top left corner, and the page is under it as it dries */
      powerOn: withInk(async (ctx, ink) => {
        ink.from(-0.04, -0.02, 0.5);
        ink.run({ kind: 'wake', ink: INK, dur: 1800, T: (p) => -0.12 + 2.1 * Math.pow(p, 1.15) });
        await ctx.sleep(1250);
        ink.free();
      }),

      /* each key is a soft dab where the character was set down */
      key(ctx) {
        if (ctx.reduced) return;
        const r = ctx.rect(ctx.prompt.querySelector('.tl-cursor'));
        dab(ctx, r.x - 1.1, r.y + r.h / 2, false, 520);
      },

      /* the seal is pressed beside the clock, the words under it; then one broad wet stroke takes the page away */
      granted: withInk(async (ctx, ink) => {
        const word = ctx.say('access granted', 'ok');
        word.dataset.role = 'granted';
        word.classList.add('sumi-wet');
        const seal = ctx.el('div', 'sumi-seal');
        seal.innerHTML = '<i></i>' + SEAL;
        seal.style.top = ctx.rect(word).y - SEAL_H - 0.6 + '%';
        ctx.fx.insertBefore(seal, ink.canvas);
        await ctx.animate(seal, [
          { transform: 'translate(1cqw, -5cqw) scale(1.75) rotate(-13deg)', opacity: 0, easing: 'cubic-bezier(.6, 0, .9, .5)' },
          { transform: 'scale(.93) rotate(-3deg)', opacity: 1, offset: 0.52, easing: 'cubic-bezier(.2, .7, .3, 1)' },
          { transform: 'scale(1) rotate(-4deg)', opacity: 1 },
        ], { duration: 340, fill: 'backwards' });
        // the paper gives a little under the press, and the ink spreads past the seal's edge
        ctx.animate(ctx.screen, [{ transform: 'none' }, { transform: 'translateY(.14em)', offset: 0.25 }, { transform: 'none' }], { duration: 300, easing: 'ease-out' }).catch(gone);
        ctx.animate(seal.firstChild, [{ opacity: 0, transform: 'scale(.9)' }, { opacity: 0.4, transform: 'scale(1.08)', offset: 0.5 }, { opacity: 0.24, transform: 'scale(1.1)' }], { duration: 900, easing: 'ease-out', fill: 'forwards' }).catch(gone);
        await ctx.sleep(640);

        const lr = ctx.rect(ctx.lines);
        ink.across(lr.y - 2.5, lr.y + lr.h + 3);
        ink.run({ kind: 'sweep', ink: WASH, dur: 950, T: (p) => -0.1 + 2 * p });
        await ctx.sleep(610);           // the front has left the page: what it painted out can go
        ink.free();
      }, 'lines'),

      /* an ink drop lands at the head of the notification and spreads into the line */
      async notify(ctx, node) {
        if (ctx.reduced) return;
        const r = ctx.rect(node), x = r.x + 2, y = r.y + r.h / 2;
        (await drop(ctx, x, y, 290)).remove();
        dab(ctx, x, y, true, 800);
      },

      /* a single drop falls on the launched line and blooms outward until the page is black */
      launch: withInk(async (ctx, ink, line) => {
        const r = ctx.rect(line.querySelector('.tl-spin') || line);
        const x = r.x + r.w / 2, y = r.y + r.h / 2;
        await ctx.sleep(380);
        await drop(ctx, x, y, 320);
        ink.from(x / 100, y / 100, 0.6);
        ink.run({
          kind: 'bloom', ink: NIGHT, dur: 1250, hold: true,
          T: (p) => (p < 0.14 ? 0.06 * Math.sin((p / 0.14) * 1.5708) : 0.06 + 1.1 * Math.pow((p - 0.14) / 0.86, 2.4)),
        });
        await ctx.sleep(1270);
        ink.thin(300);                  // the app comes up through the ink
      }),

      /* back from the app: the ink closes over it, then recedes into the line it came from */
      back: withInk(async (ctx, ink) => {
        const line = [...ctx.lines.querySelectorAll('.tl-launch-text')].pop();
        const r = line ? ctx.rect(line) : { x: 50, y: 50, w: 0, h: 0 };
        ink.from((r.x + r.w) / 100 + 0.02, (r.y + r.h / 2) / 100, 0.6);
        ink.run({ kind: 'bloom', ink: NIGHT, dur: 220, hold: true, T: () => 1.2, M: (p) => p });
        await ctx.sleep(300);
        ctx.appview.style.visibility = 'hidden';
        ink.run({ kind: 'bloom', ink: NIGHT, dur: 1000, T: (p) => 1.16 * Math.pow(1 - p, 2.1) - 0.11 * p });
        await ctx.sleep(1000);
      }, 'appview'),

      /* lock: every mark lets go of its edges, thins to a pale wash that sinks a little, and the paper is left blank */
      powerOff: withInk(async (ctx, ink) => {
        ink.melt(marks(ctx), 1500);
        await ctx.animate(ctx.screen, [{ opacity: 1 }, { opacity: 0 }], { duration: 430, easing: 'cubic-bezier(.4, 0, .6, 1)', fill: 'forwards' });
        await ctx.sleep(1080);
      }, 'screen'),
    },
  });
})();
