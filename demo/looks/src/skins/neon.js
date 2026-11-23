/*
 * Neon Grid. Behind the terminal is a night sky, a banded sun on the horizon and a grid floor
 * running toward the viewer. A handful of numbers describe it (how far the sun is up, how much of
 * the grid is drawn, how fast the floor runs, how deep in warp we are); the hooks move those
 * numbers and the frame loop draws them. At rest they follow the scene, so a phone laid out with
 * no hook running is still the right picture. The scene is dimmed behind the text, as far down as
 * the text reaches, and that lifts whenever the text leaves.
 *
 * Sky and floor are two canvases, as only the floor moves every frame. Both are capped at 1.5
 * device pixels per pixel, and the warp canvas at 1: a frame costs what it has pixels.
 */
(function () {
  'use strict';

  const HZ = 0.69;            // the horizon, as a part of the phone's height
  const CALM = 0.42;          // how fast the floor runs at rest, in grid cells a second
  const ON = { sky: 1, sun: 1, hz: 1, draw: 1, lines: 1, dim: 1, speed: CALM, warp: 0, flash: 0, veil: 0 };
  const OFF = { sky: 0, sun: 0, hz: 0, draw: 0, lines: 0, dim: 0, speed: 0, warp: 0, flash: 0, veil: 0 };
  const SETTLE = { speed: 1500, veil: 520 };     // how long a number takes to ease back to rest, if not 440 ms
  const E = {
    lin: (t) => t,
    in: (t) => t * t * t,
    out: (t) => 1 - Math.pow(1 - t, 3),
    io: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
  };
  const clamp = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
  const frac = (v) => v - Math.floor(v);
  const states = new WeakMap();

  /** A gradient with its stops: offset, colour, offset, colour... */
  function ramp(gradient, ...stops) {
    for (let i = 0; i < stops.length; i += 2) gradient.addColorStop(stops[i], stops[i + 1]);
    return gradient;
  }
  function seeded(seed) {
    return () => ((seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296);
  }

  /* ---------------- the numbers ---------------- */

  function tween(S, key, to, ms, ease, delay) {
    S.tw[key] = { a: null, b: to, t0: performance.now() + (delay || 0), d: Math.max(1, ms), e: ease || E.out, auto: false };
  }

  /** Where the text ends, as a part of the phone's height. */
  function textEnd(ctx, S) {
    const r = ctx.rect(ctx.lines);
    return (S.textTo = Math.max(0.05, (r.y + r.h) / 100));
  }

  /**
   * Notices the scene, and returns the numbers it rests at. A phone that went from dark to lit
   * without waking was laid out, not played, and one whose transition was cut short has nothing
   * left to show: both go straight to rest.
   */
  function settle(ctx, S) {
    const scene = ctx.root.dataset.scene;
    const rest = scene === 'off' ? OFF : S.on;
    const jumped = scene !== S.scene && (S.scene === null || (S.scene === 'off' && scene !== 'boot'));
    if (jumped || S.snap) { S.tw = {}; Object.assign(S.P, rest); S.kick = 0; S.dir = 1; S.text = textEnd(ctx, S); S.snap = false; }
    S.scene = scene;
    return rest;
  }

  function advance(ctx, S, now) {
    const P = S.P;
    const rest = settle(ctx, S);
    const dt = Math.min(50, Math.max(0, now - (S.last || now)));
    S.last = now;

    for (const key in S.tw) {
      const w = S.tw[key];
      if (w.auto && w.b !== rest[key]) { delete S.tw[key]; continue; }
      const t = (now - w.t0) / w.d;
      if (t < 0) continue;
      if (w.a === null) w.a = P[key];
      if (t >= 1) { P[key] = w.b; delete S.tw[key]; } else P[key] = w.a + (w.b - w.a) * w.e(t);
    }
    // With no hook driving, whatever is off its resting value eases back to it.
    if (!S.owner) {
      for (const key in rest) {
        if (S.tw[key] || Math.abs(P[key] - rest[key]) < 1e-3) continue;
        tween(S, key, rest[key], SETTLE[key] || 440, E.out);
        S.tw[key].auto = true;
      }
    }

    const gap = S.textTo - S.text;                     // the dimming follows the text down the page
    S.text = Math.abs(gap) < 0.002 ? S.textTo : S.text + gap * (1 - Math.exp(-dt / 70));
    S.kick *= Math.exp(-dt / 260);
    S.pulse *= Math.exp(-dt / 320);
    S.v = P.speed + S.kick;
    S.shift = (S.v * dt) / 1000;                       // cells moved this frame, for the smear
    S.off += S.shift;
    S.wt += (dt / 1000) * (0.3 + 2.6 * P.warp) * S.dir;
  }

  /** Runs a transition as the one thing moving the numbers. If it is cut short, the scene snaps to rest. */
  async function drive(ctx, fn) {
    const S = states.get(ctx.root);
    if (!S) return;
    const me = ++S.gen;
    settle(ctx, S);              // before the first frame has seen this scene, perhaps
    S.owner = me;
    try {
      await fn(S, S.P);
    } catch (e) {
      if (S.gen === me) S.snap = true;
      throw e;
    } finally {
      if (S.owner === me) S.owner = 0;
    }
  }

  /* ---------------- the scene ---------------- */

  /**
   * A canvas over a band of the phone, from `top` to `bottom` (parts of its height), at no more
   * than `cap` device pixels per pixel. It is drawn in the phone's coordinates: w by H, with the
   * band starting at y0.
   */
  function band(ctx, layer, top, bottom, cap) {
    const canvas = ctx.el('canvas');
    canvas.style.cssText = `position:absolute;left:0;width:100%;top:${top * 100}%;height:${(bottom - top) * 100}%;display:block`;
    layer.append(canvas);
    const c = { canvas, g: canvas.getContext('2d'), w: 1, h: 1, H: 1, y0: 0, dirty: true };
    c.fit = () => {
      const box = ctx.root.getBoundingClientRect();
      const k = Math.min(cap, window.devicePixelRatio || 1);
      c.w = Math.max(1, Math.round(box.width * k));
      c.H = Math.max(1, Math.round(box.height * k));
      c.y0 = Math.round(c.H * top);
      c.h = Math.max(1, Math.round(c.H * bottom) - c.y0);
      if (canvas.width !== c.w || canvas.height !== c.h) { canvas.width = c.w; canvas.height = c.h; }
      c.dirty = true;
    };
    c.fit();
    return c;
  }

  function makeStars() {
    const r = seeded(84);
    const list = [];
    for (let i = 0; i < 58; i++) {
      list.push({ x: r(), y: Math.pow(r(), 1.25) * 0.94, s: 0.22 + r() * r() * 0.5, a: 0.35 + r() * 0.65, f: 0.5 + r() * 1.8, p: r() * 6.28, big: r() < 0.09 });
    }
    return list;
  }
  function makeStreaks() {
    const r = seeded(1986);
    const tints = ['#3df2ff', '#ff4fd8', '#ffffff', '#b98cff', '#ffd9f6', '#3df2ff'];
    const list = [];
    for (let i = 0; i < 84; i++) {
      list.push({ ang: r() * 6.2832, p: r(), v: 0.6 + r() * 0.9, w: 0.5 + r(), a: 0.45 + r() * 0.55, c: tints[(r() * tints.length) | 0] });
    }
    return list;
  }

  const NIGHT = '#020106';

  /** Night over the scene where the text is, fading out below its last line. Drawn from `from` down. */
  function dimming(c, S, from) {
    const a = 0.62 * S.P.dim;
    if (a < 0.004) return;
    const g = c.g, u = c.w / 100;
    const last = S.text * c.H + 6 * u, end = last + 12 * u;
    if (end <= from) return;
    const at = (k) => (last + (end - last) * k) / end;
    const ink = (k) => `rgba(5, 3, 15, ${a * k})`;
    g.globalAlpha = 1;
    g.fillStyle = ramp(g.createLinearGradient(0, 0, 0, end), 0, ink(1), at(0), ink(1), at(0.35), ink(0.62), at(0.7), ink(0.2), 1, ink(0));
    g.fillRect(0, from, c.w, end - from);
  }

  /** The sky and the sun. Nothing here answers to speed, so it is rarely redrawn. */
  function drawSky(c, S, t) {
    const g = c.g, w = c.w, P = S.P;
    const u = w / 100;                                   // 1cqw in canvas pixels
    const hy = Math.round(c.H * HZ), cx = w / 2;

    g.setTransform(1, 0, 0, 1, 0, -c.y0);
    g.globalCompositeOperation = 'source-over';
    g.globalAlpha = 1;
    g.fillStyle = NIGHT;
    g.fillRect(0, c.y0, w, c.h);
    if (P.sky < 0.003) return;

    const sky = ramp(g.createLinearGradient(0, 0, 0, hy), 0, '#04020d', 0.42, '#0a0522', 0.78, '#1a0a3d', 1, '#3b1060');
    g.globalAlpha = P.sky;
    g.fillStyle = sky;
    g.fillRect(0, 0, w, hy + u);

    g.fillStyle = '#e6dcff';
    for (const s of S.stars) {
      const tw = 0.55 + 0.45 * Math.sin(t * s.f + s.p);
      const size = Math.max(1, s.s * u);
      const x = Math.round(s.x * w), y = Math.round(hy * (0.075 + 0.84 * s.y));
      g.globalAlpha = P.sky * s.a * tw * 0.8;
      g.fillRect(x, y, size, size);
      if (s.big) {
        g.globalAlpha *= 0.45;
        g.fillRect(x - size, y, size * 3, size);
        g.fillRect(x, y - size, size, size * 3);
      }
    }

    /* the sun: a disc cut into bands that widen toward the horizon, behind the floor */
    const R = 26.5 * u;
    const sy = hy + R * (1.14 - 1.5 * P.sun);            // at rest its centre is 0.36 R above the horizon
    if (P.sun > 0.003) {
      g.save();
      g.beginPath();
      g.rect(0, 0, w, hy + u);
      g.clip();
      const up = clamp(P.sun * 1.6);
      const halo = ramp(g.createRadialGradient(cx, sy, R * 0.5, cx, sy, R * 2.7), 0, 'rgba(255, 80, 170, 0.5)', 0.38, 'rgba(214, 44, 214, 0.17)', 1, 'rgba(110, 20, 200, 0)');
      g.globalAlpha = P.sky * up * 0.8;
      g.fillStyle = halo;
      g.fillRect(cx - R * 2.7, sy - R * 2.7, R * 5.4, R * 5.4);

      g.beginPath();
      const n = 9, ph = frac(t * 0.04);
      let y0 = sy - R - 1;
      for (let i = 0; i < n; i++) {
        const q = (i + ph) / n;
        const gy = sy - R * 0.48 + q * R * 1.5;
        const gh = R * 0.125 * q;
        g.rect(cx - R - 1, y0, 2 * R + 2, gy - gh / 2 - y0);
        y0 = gy + gh / 2;
      }
      g.rect(cx - R - 1, y0, 2 * R + 2, sy + R + 1 - y0);
      g.clip();
      const disc = ramp(g.createLinearGradient(0, sy - R, 0, sy + R * 0.62), 0, '#fff27a', 0.36, '#ffb24a', 0.7, '#ff5a8f', 1, '#ff2bd6');
      g.globalAlpha = P.sky * 0.96;
      g.fillStyle = disc;
      g.beginPath();
      g.arc(cx, sy, R, 0, 6.2832);
      g.fill();
      g.restore();

      // haze where the sky meets the floor
      const haze = ramp(g.createLinearGradient(0, hy - 16 * u, 0, hy), 0, 'rgba(255, 60, 200, 0)', 1, 'rgba(255, 60, 200, 0.3)');
      g.globalAlpha = P.sky * up;
      g.fillStyle = haze;
      g.fillRect(0, hy - 16 * u, w, 16 * u);
    }
    dimming(c, S, 0);
    g.globalAlpha = 1;
  }

  /** The floor, its grid and the horizon: everything that answers to speed. */
  function drawFloor(c, S) {
    const g = c.g, w = c.w, h = c.H, P = S.P;
    const u = w / 100;
    const hy = Math.round(h * HZ), cx = w / 2, fh = h - hy;
    const boost = clamp(Math.abs(S.v) / 14);             // how hard the floor is running
    const glow = Math.min(1.7, 1 + 0.6 * boost + 0.5 * S.pulse);

    g.setTransform(1, 0, 0, 1, 0, -c.y0);
    g.globalCompositeOperation = 'source-over';
    g.globalAlpha = 1;
    g.clearRect(0, c.y0, w, c.h);                        // the strip above the horizon holds only its glow
    g.fillStyle = NIGHT;
    g.fillRect(0, hy, w, fh);

    if (P.sky > 0.003) {
      const floor = ramp(g.createLinearGradient(0, hy, 0, h), 0, '#230841', 0.22, '#11052b', 1, '#05020e');
      g.globalAlpha = P.sky;
      g.fillStyle = floor;
      g.fillRect(0, hy, w, fh);
      if (P.sun > 0.003) {
        // the sun on the floor
        g.save();
        g.translate(cx, hy);
        g.scale(1, 0.62);
        const pool = ramp(g.createRadialGradient(0, 0, 0, 0, 0, 46 * u), 0, 'rgba(255, 96, 170, 0.5)', 0.5, 'rgba(230, 50, 200, 0.14)', 1, 'rgba(180, 40, 220, 0)');
        g.globalAlpha = Math.min(1, P.sky * clamp(P.sun * 1.4) * glow * 0.9);
        g.fillStyle = pool;
        g.fillRect(-46 * u, 0, 92 * u, 46 * u);
        g.restore();
      }
    }

    /* the grid: magenta at the horizon, cyan under your feet */
    const reach = Math.min(1, P.draw * 1.04);            // how far out from the horizon it is drawn
    if (reach > 0.003 && P.lines > 0.003) {
      const ink = ramp(g.createLinearGradient(0, hy, 0, h), 0, 'rgba(255, 70, 214, 0)', 0.07, 'rgba(255, 70, 214, 0.62)', 0.5, 'rgba(168, 96, 255, 0.8)', 1, 'rgba(70, 228, 255, 0.95)');
      const lit = Math.min(1, 0.72 + 0.5 * boost);
      const thin = Math.max(1, 0.26 * u);

      // the lines that run away from you; when the grid goes out, the outer ones go first
      const K = 19, cw = 25 * u;
      g.strokeStyle = ink;
      g.lineCap = 'butt';
      const yEnd = hy + fh * reach;
      for (let pass = 0; pass < 2; pass++) {
        g.lineWidth = pass ? thin : thin * 4.2;
        const base = (pass ? lit : 0.15 * lit) * (1 + (pass ? 0 : boost));
        if (P.lines >= 0.999) {
          g.globalAlpha = base;
          g.beginPath();
          for (let k = -K; k <= K; k++) { g.moveTo(cx, hy); g.lineTo(cx + k * cw * reach, yEnd); }
          g.stroke();
        } else {
          for (let k = -K; k <= K; k++) {
            const a = clamp((P.lines - (Math.abs(k) / K) * 0.7) / 0.12);
            if (a <= 0) continue;
            g.globalAlpha = base * a;
            g.beginPath();
            g.moveTo(cx, hy);
            g.lineTo(cx + k * cw * reach, yEnd);
            g.stroke();
          }
        }
      }

      // the lines that come toward you; smeared when they run fast, gone when they would only strobe
      const N = 30, step = 0.34, dMax = 1 + (N - 1) * step;
      const fast = clamp(1 - (Math.abs(S.v) - 11) / 16);
      if (fast > 0.003) {
        const phase = frac(S.off);
        const taps = Math.max(1, Math.min(5, Math.ceil(Math.abs(S.shift) / 0.05)));
        g.fillStyle = ink;
        for (let i = 0; i < N; i++) {
          // when the grid goes out, the nearest line goes first
          const out = P.lines >= 0.999 ? 1 : clamp((P.lines - (1 - (i + 1) / N) * 0.88) / 0.07);
          if (out <= 0) continue;
          for (let j = 0; j < taps; j++) {
            const d = 1 + (i - phase + (j / taps) * S.shift) * step;
            if (d < 0.97) continue;
            const y = hy + fh / d;
            if (y > yEnd) continue;
            const a = Math.min(1, 1.3 / d) * clamp((dMax - d) / (2 * step)) * lit * fast * out / taps;
            const lw = Math.max(1, u * (0.16 + 0.42 / d));
            g.globalAlpha = a * 0.16 * (1 + boost);
            g.fillRect(0, y - lw * 2.2, w, lw * 4.4);
            g.globalAlpha = a;
            g.fillRect(0, y - lw / 2, w, lw);
          }
        }
      }

      // while the grid is still drawing itself, its leading edge is hot
      if (reach < 0.999 && P.lines >= 0.999) {
        const edge = clamp(reach * 6) * clamp((1 - reach) * 5);
        const lw = Math.max(1, u * 0.5);
        g.fillStyle = '#c9fbff';
        g.globalAlpha = edge * 0.22;
        g.fillRect(0, yEnd - lw * 3, w, lw * 6);
        g.globalAlpha = edge * 0.95;
        g.fillRect(0, yEnd - lw / 2, w, lw);
      }
    }

    /* the horizon: it opens from the middle and closes back to it */
    if (P.hz > 0.003) {
      const half = (w / 2) * P.hz;
      const lw = Math.max(1, u * 0.36);
      const band = ramp(g.createLinearGradient(cx - half, 0, cx + half, 0), 0, 'rgba(255, 90, 220, 0)', 0.18, 'rgba(255, 120, 230, 0.9)', 0.5, 'rgba(255, 236, 250, 1)', 0.82, 'rgba(255, 120, 230, 0.9)', 1, 'rgba(255, 90, 220, 0)');
      g.fillStyle = band;
      g.globalAlpha = Math.min(1, 0.2 * glow * clamp(P.hz * 3));
      g.fillRect(cx - half, hy - lw * 4, half * 2, lw * 8);
      g.globalAlpha = Math.min(1, 0.85 * glow) * clamp(P.hz * 3);
      g.fillRect(cx - half, hy - lw / 2, half * 2, lw);
    }
    dimming(c, S, hy);
    g.globalAlpha = 1;
  }

  /** The warp, over everything: streaks from the vanishing point, the bloom they end in, and a white veil. */
  function drawWarp(c, S) {
    const g = c.g, w = c.w, h = c.h, P = S.P;
    if (P.warp < 0.004 && P.flash < 0.004 && P.veil < 0.004) {
      if (S.fxDirty) { g.setTransform(1, 0, 0, 1, 0, 0); g.clearRect(0, 0, w, h); S.fxDirty = false; }
      return;
    }
    S.fxDirty = true;
    const u = w / 100, hy = h * HZ, cx = w / 2;
    const far = Math.hypot(cx, hy) * 1.08;
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.globalAlpha = 1;
    g.globalCompositeOperation = 'source-over';
    g.clearRect(0, 0, w, h);
    if (P.warp >= 0.004) {
      g.globalCompositeOperation = 'lighter';
      g.lineCap = 'round';
      for (const s of S.streaks) {
        const p = frac(s.p + S.wt * s.v);
        const d0 = far * p * p;
        const len = far * (0.03 + 0.55 * Math.pow(P.warp, 1.6)) * (0.25 + p);
        const cos = Math.cos(s.ang), sin = Math.sin(s.ang);
        g.globalAlpha = Math.min(1, P.warp * 2) * Math.min(1, p * 4) * s.a;
        g.strokeStyle = s.c;
        g.lineWidth = Math.max(1, u * (0.12 + p * 0.85 * s.w));
        g.beginPath();
        g.moveTo(cx + cos * d0, hy + sin * d0);
        g.lineTo(cx + cos * (d0 + len), hy + sin * (d0 + len));
        g.stroke();
      }
      g.globalCompositeOperation = 'source-over';
    }
    if (P.flash >= 0.004) {
      const f = P.flash;
      const r = far * (0.1 + 1.7 * Math.pow(f, 1.4));
      const bloom = ramp(g.createRadialGradient(cx, hy, 0, cx, hy, r), 0, 'rgba(255, 255, 255, 1)', 0.45, 'rgba(255, 236, 252, 0.9)', 0.75, 'rgba(255, 140, 232, 0.4)', 1, 'rgba(255, 90, 220, 0)');
      g.globalAlpha = Math.min(1, f * 1.5);
      g.fillStyle = bloom;
      g.fillRect(0, 0, w, h);
      const white = clamp((f - 0.5) / 0.5);
      if (white > 0) { g.globalAlpha = white * white; g.fillStyle = '#fff'; g.fillRect(0, 0, w, h); }
    }
    if (P.veil >= 0.004) { g.globalAlpha = Math.min(1, P.veil); g.fillStyle = '#fff'; g.fillRect(0, 0, w, h); }
    g.globalAlpha = 1;
  }

  /* ---------------- the sign ---------------- */

  /** A tube striking: dark, a couple of stutters, then steady. */
  function strike(ctx, node, delay, ms) {
    const j = Math.random() * 0.06;
    return ctx.animate(node, [
      { opacity: 0, offset: 0, easing: 'step-end' },
      { opacity: 0.9, offset: 0.07, easing: 'step-end' },
      { opacity: 0.08, offset: 0.14 + j, easing: 'step-end' },
      { opacity: 1, offset: 0.36 + j, easing: 'step-end' },
      { opacity: 0.3, offset: 0.46 + j, easing: 'step-end' },
      { opacity: 1, offset: 0.58 + j, easing: 'step-end' },
      { opacity: 0.7, offset: 0.68 + j, easing: 'step-end' },
      { opacity: 1, offset: 0.76 + j, easing: 'step-end' },
      { opacity: 1, offset: 1 },
    ], { duration: ms, delay, fill: 'backwards' }).catch(() => {});
  }
  /** And going out. */
  function die(ctx, node, delay, ms) {
    const j = Math.random() * 0.08;
    return ctx.animate(node, [
      { opacity: 1, offset: 0, easing: 'step-end' },
      { opacity: 0.35, offset: 0.1, easing: 'step-end' },
      { opacity: 1, offset: 0.2 + j, easing: 'step-end' },
      { opacity: 0.12, offset: 0.42 + j, easing: 'step-end' },
      { opacity: 0.75, offset: 0.52 + j, easing: 'step-end' },
      { opacity: 0.1, offset: 0.66 + j, easing: 'step-end' },
      { opacity: 0.4, offset: 0.8, easing: 'step-end' },
      { opacity: 0, offset: 0.88, easing: 'step-end' },
      { opacity: 0, offset: 1 },
    ], { duration: ms, delay, fill: 'both' }).catch(() => {});
  }
  const signParts = (ctx) => [ctx.status, ...ctx.lines.querySelectorAll('.tl-line:not(.tl-clock), .neon-clock b')];
  const zoomFrom = (ctx) => { ctx.screen.style.cssText = `transform-origin:50% ${HZ * 100}%;will-change:transform,opacity`; };

  /* ---------------- the look ---------------- */

  TL.register({
    id: 'neon',
    name: 'Neon Grid',
    tagline: 'An outrun night that speeds up when you do',
    mood: 'A neon sign in front of a slow synthwave sunset: the grid idles while you read and surges the moment you unlock or launch.',
    motion: [
      'Waking, the grid draws out from the horizon, the sun rises and the sign stutters on',
      'Unlocking tears the page into sheared colour-split slices while the grid surges',
      'Launching goes to warp: streaks from the horizon until it whites out into the app',
    ],
    palette: { bg: '#05030f', fg: '#ece8ff', dim: '#8d84c6', accent: '#ff4fd8', app: '#3df2ff', ok: '#6dffc1', err: '#ff3d6e' },
    light: false,
    fonts: ['Share+Tech+Mono', 'Orbitron:wght@500;700'],
    hooks: {
      mount(ctx) {
        const cap = ctx.still ? 2 : 1.5;
        const edge = 2 * (9 / 19.5) / 100;               // 2cqw of the phone's height: room for the horizon's glow
        const sky = band(ctx, ctx.bg, 0, HZ + edge, cap);
        const floor = band(ctx, ctx.bg, HZ - edge, 1, cap);
        const warp = ctx.still ? null : band(ctx, ctx.fx, 0, 1, 1);
        const S = {
          P: Object.assign({}, OFF), tw: {}, scene: null, owner: 0, gen: 0, snap: false,
          on: Object.assign({}, ON, { speed: ctx.reduced ? 0 : CALM }),
          off: 0.3, wt: 0, dir: 1, kick: 0, pulse: 0, v: 0, shift: 0, last: 0, fxDirty: false,
          text: 0.08, textTo: 0.08, skyAt: 0, skyWas: '', dark: false,
          stars: makeStars(), streaks: makeStreaks(),
        };
        states.set(ctx.root, S);
        const paint = (now) => {
          const P = S.P;
          if (ctx.still) { Object.assign(P, ON); S.v = CALM; }      // a still is the scene at rest
          else if (S.scene === 'app' && !S.owner && P.veil < 0.004) return;   // under the app there is nothing to see
          const dark = P.sky < 0.003 && P.hz < 0.003;
          if (dark && S.dark && !sky.dirty && !floor.dirty) return;  // and nothing in the dark
          S.dark = dark;
          // the sky only changes with the sun and the text; otherwise a few frames a second keep its stars and bands alive
          const is = P.sky + ' ' + P.sun + ' ' + P.dim + ' ' + S.text;
          if (sky.dirty || is !== S.skyWas || now - S.skyAt > 125) {
            drawSky(sky, S, ctx.still ? 9 : now / 1000);
            S.skyWas = is; S.skyAt = now; sky.dirty = false;
          }
          drawFloor(floor, S);
          floor.dirty = false;
        };
        const ro = window.ResizeObserver && new ResizeObserver(() => {
          sky.fit(); floor.fit();
          if (warp) warp.fit();
          // When the prompt comes back under a full page the scrollback shrinks and its last line is cut: keep it at its end.
          ctx.lines.scrollTop = ctx.lines.scrollHeight;
          textEnd(ctx, S);
          if (ctx.still) { S.text = S.textTo; paint(0); }
        });
        if (ro) { ro.observe(ctx.root); ro.observe(ctx.lines); }
        ctx.onFrame((now) => {
          if (!ctx.still) advance(ctx, S, now);
          paint(now);
          if (warp) drawWarp(warp, S);
        });
        return () => { if (ro) ro.disconnect(); states.delete(ctx.root); };
      },

      /* The clock is the sign over the door: hollow tube numerals. */
      clock(ctx, node, time) {
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        const sign = ctx.el('span', 'neon-clock');
        [...time].forEach((ch) => sign.append(ctx.el('b', ch === ':' ? 'neon-colon' : '', ch)));
        node.append(sign);
      },

      /* Waking: the horizon opens, the grid draws itself out from it, the sun comes up, the sign strikes. */
      powerOn: (ctx) => drive(ctx, async (S, P) => {
        S.tw = {};
        Object.assign(P, OFF);
        P.lines = 1;
        P.speed = 2.6;
        tween(S, 'sky', 1, 700, E.out);
        tween(S, 'hz', 1, 300, E.out);
        tween(S, 'draw', 1, 920, E.io, 150);
        tween(S, 'sun', 1, 1300, E.out, 180);
        tween(S, 'speed', S.on.speed, 1500, E.out, 250);
        tween(S, 'dim', 1, 600, E.io, 300);
        signParts(ctx).forEach((node) => {
          const digit = node.tagName === 'B';
          strike(ctx, node, (digit ? 400 : 280) + Math.random() * 250, digit ? 620 : 520);
        });
        await ctx.sleep(1280);
      }),

      /* Typing: the cursor flares, and the floor takes a small push. */
      key(ctx) {
        const S = states.get(ctx.root);
        if (S) { S.kick = Math.min(2.4, S.kick + 0.5); S.pulse = Math.max(S.pulse, 0.45); }
        const cursor = ctx.prompt.querySelector('.tl-cursor');
        ctx.animate(cursor, [{ transform: 'scale(1.3, 1.25)' }, { transform: 'none' }], { duration: 200, easing: 'cubic-bezier(.2,.7,.2,1)' }).catch(() => {});
        ctx.animate(cursor, [{ opacity: 1, transform: 'scale(0.42)' }, { opacity: 0, transform: 'scale(1.1)' }], { duration: 340, easing: 'cubic-bezier(.2,.7,.2,1)', pseudoElement: '::after' }).catch(() => {});
      },

      /* Unlocking: the lock page is cut into slices that shear apart, colours split, while the grid surges. */
      granted: (ctx) => drive(ctx, async (S) => {
        const word = ctx.say('access granted', 'ok');
        word.dataset.role = 'granted';
        strike(ctx, word, 0, 240);
        S.pulse = 1;
        tween(S, 'speed', 10, 560, E.in);
        tween(S, 'warp', 0.36, 560, E.in);
        await ctx.sleep(240);

        const proto = ctx.screen.cloneNode(true);
        proto.querySelectorAll('.tl-in').forEach((n) => n.classList.remove('tl-in'));
        const slices = [];
        for (let y = 0; y < 100;) {
          const y1 = Math.min(100, y + 2.5 + Math.random() * 8.5);
          const band = ctx.el('div', 'neon-slice');
          band.style.cssText = `top:${y}cqh;height:${y1 - y}cqh`;
          const copy = proto.cloneNode(true);
          copy.style.cssText = `inset:auto;left:0;top:${-y}cqh;width:100cqw;height:100cqh`;
          band.append(copy);
          ctx.fx.append(band);
          slices.push(band);
          y = y1;
        }
        ctx.screen.style.opacity = '0';
        tween(S, 'dim', 0, 260, E.out);
        for (const amp of [0.14, 0.5, 0.22, 0.85, 0.4, 1.1, 1.45]) {
          for (const band of slices) {
            const hit = Math.random() < 0.28 + amp * 0.42;
            const dx = hit ? (Math.random() * 2 - 1) * amp * 15 : 0;
            band.style.transform = `translateX(${dx.toFixed(2)}cqw)`;
            band.style.setProperty('--neon-sx', (hit ? amp * (0.25 + Math.random() * 0.55) : amp * 0.1).toFixed(3) + 'em');
            band.style.opacity = Math.random() < amp * 0.1 ? '0.2' : '1';
          }
          await ctx.sleep(54);
        }
        // and they leave, every other one the other way: the page is clean
        tween(S, 'warp', 0, 520, E.out);
        await Promise.all(slices.map((band, i) => ctx.animate(band, [
          { transform: band.style.transform, opacity: 1 },
          { transform: `translateX(${i % 2 ? 112 : -112}cqw)`, opacity: 0.7 },
        ], { duration: 190, delay: i * 6, easing: 'cubic-bezier(.6,0,.9,.35)', fill: 'forwards' })));
      }),

      /* A notification: the horizon answers. */
      notify(ctx) {
        const S = states.get(ctx.root);
        if (S) { S.pulse = 1.4; S.kick = Math.min(3, S.kick + 1.3); }
      },

      /* Launching: warp. The floor accelerates, the page flies past, streaks pour out of the horizon into white. */
      launch: (ctx, line) => drive(ctx, async (S, P) => {
        S.dir = 1;
        S.pulse = 1;
        zoomFrom(ctx);
        ctx.animate(line, [{ filter: 'brightness(2.2)', offset: 0 }, { filter: 'brightness(1)' }], { duration: 260 }).catch(() => {});
        tween(S, 'speed', 3.2, 220, E.out);
        await ctx.sleep(200);
        tween(S, 'speed', 46, 860, E.in);
        tween(S, 'warp', 1, 820, E.in);
        tween(S, 'flash', 1, 400, E.in, 560);
        tween(S, 'dim', 0, 520, E.io, 160);
        ctx.animate(ctx.screen, [
          { transform: 'scale(1)', opacity: 1, offset: 0 },
          { transform: 'scale(1.1)', opacity: 1, offset: 0.4 },
          { transform: 'scale(3.4)', opacity: 0, offset: 1 },
        ], { duration: 800, delay: 60, easing: 'cubic-bezier(.55,0,.9,.55)', fill: 'forwards' }).catch(() => {});
        await ctx.sleep(980);
        // all white now: from here it is a plain veil, which lifts off the app
        delete S.tw.flash;
        P.flash = 0;
        P.veil = 1;
      }),

      /* Back: the same in reverse. White, then the streaks fall back into the horizon and the floor slows to a stop. */
      back: (ctx) => drive(ctx, async (S, P) => {
        tween(S, 'veil', 1, 140, E.out);
        await ctx.sleep(160);
        ctx.appview.style.visibility = 'hidden';
        delete S.tw.veil;
        P.veil = 0;
        P.flash = 1;
        P.warp = 1;
        P.speed = -40;
        P.dim = 1;                                        // behind the white, the page is back already
        S.dir = -1;
        tween(S, 'speed', S.on.speed, 1250, E.out);
        tween(S, 'warp', 0, 700, E.out);
        tween(S, 'flash', 0, 540, E.out);
        zoomFrom(ctx);
        await ctx.animate(ctx.screen, [
          { transform: 'scale(3.2)', opacity: 0 },
          { transform: 'scale(1)', opacity: 1 },
        ], { duration: 700, easing: 'cubic-bezier(.12,.75,.2,1)', fill: 'both' });
        await ctx.sleep(80);
        S.dir = 1;
      }),

      /* Locking: the sign flickers out, the sun sets, the grid goes out line by line, the horizon closes. */
      powerOff: (ctx) => drive(ctx, async (S, P) => {
        signParts(ctx).forEach((node) => die(ctx, node, Math.random() * 420, 480));
        tween(S, 'speed', 0, 520, E.out);
        tween(S, 'dim', 0, 520, E.io, 220);
        tween(S, 'sun', 0, 1050, E.in, 120);
        tween(S, 'lines', 0, 980, E.lin, 320);
        tween(S, 'sky', 0, 620, E.io, 900);
        tween(S, 'hz', 0, 280, E.in, 1380);
        await ctx.sleep(1700);
        P.draw = 0;
      }),
    },
  });
})();
