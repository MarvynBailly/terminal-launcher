/*
 * Aurora: a slow aurora on a canvas behind the text, and movement on springs.
 * The sky is six big soft radial gradients, added together on a canvas no wider than about
 * 420 pixels and drawn at a third of the frame rate while nothing changes. No CSS blur.
 */
(function () {
  'use strict';

  /* ---- springs, as easings ---- */

  /** A damped spring from 0 to 1 as a CSS linear() easing. `z` is the damping ratio: lower bounces more. */
  function spring(z) {
    const n = 28, w = -Math.log(0.004) / z, wd = w * Math.sqrt(1 - z * z);
    const out = [];
    for (let i = 0; i <= n; i++) {
      const t = i / n;
      out.push(i === n ? 1 : +(1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + ((z * w) / wd) * Math.sin(wd * t))).toFixed(3));
    }
    return 'linear(' + out.join(', ') + ')';
  }
  const hasLinear = !!(window.CSS && CSS.supports && CSS.supports('animation-timing-function', 'linear(0, 1)'));
  const SOFT = hasLinear ? spring(0.8) : 'cubic-bezier(.2,.9,.3,1)';
  const SPRING = hasLinear ? spring(0.62) : 'cubic-bezier(.3,1.35,.5,1)';
  const BOUNCE = hasLinear ? spring(0.5) : 'cubic-bezier(.3,1.6,.5,1)';
  const DROP = hasLinear ? spring(0.76) : 'cubic-bezier(.3,1.2,.5,1)';

  /* ---- the sky ---- */

  /*
   * Each light: hue, saturation, lightness, strength, then where it sits and how big it is
   * (x, y in the phone, radius in phone widths) on the lock page and on the home page,
   * how flat its ellipse is, its tilt, and the slow rates it wanders at.
   */
  const LIGHTS = [
    { h: 176, s: 86, l: 44, a: 0.52, lock: [0.36, 0.39, 0.82], home: [1.00, 0.27, 0.86], k: 0.62, tilt: -0.55, v: [0.21, 0.17, 0.13], p: 0.3 },  // teal
    { h: 262, s: 84, l: 58, a: 0.58, lock: [1.00, 0.63, 0.95], home: [0.06, 0.94, 0.98], k: 0.66, tilt: -0.75, v: [0.16, 0.23, 0.11], p: 2.1 },  // violet
    { h: 152, s: 80, l: 46, a: 0.44, lock: [0.02, 0.74, 0.88], home: [0.80, 1.00, 0.80], k: 0.5, tilt: -0.95, v: [0.19, 0.14, 0.17], p: 4.4 },   // green
    { h: 334, s: 80, l: 60, a: 0.40, lock: [0.70, 0.99, 0.62], home: [1.06, 0.66, 0.56], k: 0.78, tilt: 0.3, v: [0.25, 0.19, 0.15], p: 5.6 },    // rose
    { h: 222, s: 86, l: 50, a: 0.40, lock: [0.80, 0.10, 0.92], home: [0.20, 0.50, 0.95], k: 0.7, tilt: 0.9, v: [0.13, 0.18, 0.09], p: 1.2 },     // deep blue
    { h: 166, s: 90, l: 64, a: 0.20, lock: [0.48, 0.73, 1.05], home: [0.58, 0.90, 1.05], k: 0.21, tilt: -0.62, v: [0.11, 0.15, 0.07], p: 3.3 },  // a thin bright ribbon
  ];
  /* what the sky does in each scene: level of light, how far the lights have moved to their home places, hue shift, how risen they are, and how quickly it gets there */
  const SCENES = {
    off: { lv: 0, mix: 0, hue: 0, dawn: 0, tau: 0.3 },
    boot: { lv: 1, mix: 0, hue: 0, dawn: 1, tau: 0.5 },
    lock: { lv: 1, mix: 0, hue: 0, dawn: 1, tau: 0.5 },
    granted: { lv: 1.22, mix: 1, hue: -52, dawn: 1, tau: 0.42 },
    home: { lv: 1, mix: 1, hue: -14, dawn: 1, tau: 0.7 },
    launch: { lv: 0.62, mix: 1, hue: -14, dawn: 1, tau: 0.3 },
    app: { lv: 0.62, mix: 1, hue: -14, dawn: 1, tau: 0.3 },
    poweroff: { lv: 0, mix: 1, hue: -6, dawn: 0, tau: 0.27 },
  };
  const BEFORE = { boot: 'off', granted: 'lock', launch: 'home', app: 'home', poweroff: 'home' };
  const skies = new WeakMap();   // per phone: the state of its sky

  function makeSky(ctx) {
    const canvas = ctx.el('canvas', 'aurora-sky');
    const g = canvas.getContext('2d');
    const s = { lv: 0, mix: 0, hue: 0, dawn: 0, swell: 1, swellV: 0, pulse: 0, last: 0, t0: null, t: 40, fresh: true, n: 0 };
    let SW = 1, SH = 1;

    /*
     * The canvas is the phone's own pixels divided by a whole number, never more than about 420
     * across: the picture is soft, so it needs no more, and a whole-number stretch leaves no moire.
     */
    function fit() {
      const box = ctx.root.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
      const dw = Math.max(1, Math.round(box.width * dpr)), dh = Math.max(1, Math.round(box.height * dpr));
      const n = Math.max(1, Math.ceil(dw / 420));
      const w = Math.max(1, Math.round(dw / n)), h = Math.max(1, Math.round(dh / n));
      if (w === SW && h === SH) return false;
      canvas.width = SW = w; canvas.height = SH = h;
      return true;
    }

    function paint(t) {
      s.t = t;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.globalCompositeOperation = 'source-over';
      g.fillStyle = '#05060c';
      g.fillRect(0, 0, SW, SH);
      const level = s.lv * (1 + s.pulse * 0.1);
      if (level > 0.004) {
        const m = s.mix * s.mix * (3 - 2 * s.mix);
        g.globalCompositeOperation = 'lighter';
        for (const b of LIGHTS) {
          const x = b.lock[0] + (b.home[0] - b.lock[0]) * m + 0.085 * Math.sin(t * b.v[0] + b.p);
          const y = b.lock[1] + (b.home[1] - b.lock[1]) * m + 0.055 * Math.cos(t * b.v[1] + b.p * 1.7) + (1 - s.dawn) * 0.3;
          const r = (b.lock[2] + (b.home[2] - b.lock[2]) * m) * (1 + 0.07 * Math.sin(t * b.v[2] + b.p * 2.3)) * s.swell * SW;
          const a = b.a * level;
          const hsl = (b.h + s.hue) + ',' + b.s + '%,' + b.l + '%,';
          const fill = g.createRadialGradient(0, 0, 0, 0, 0, r);
          fill.addColorStop(0, 'hsla(' + hsl + a.toFixed(3) + ')');
          fill.addColorStop(0.22, 'hsla(' + hsl + (a * 0.8).toFixed(3) + ')');
          fill.addColorStop(0.48, 'hsla(' + hsl + (a * 0.4).toFixed(3) + ')');
          fill.addColorStop(0.74, 'hsla(' + hsl + (a * 0.11).toFixed(3) + ')');
          fill.addColorStop(1, 'hsla(' + hsl + '0)');
          g.setTransform(1, 0, 0, 1, x * SW, y * SH);
          g.rotate(b.tilt + 0.3 * Math.sin(t * b.v[2] * 0.8 + b.p));
          g.scale(1, b.k);
          g.fillStyle = fill;
          g.fillRect(-r, -r, 2 * r, 2 * r);
        }
        // the scrim: night held over the sky where the words are, deeper on the home page
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.globalCompositeOperation = 'source-over';
        const veil = g.createLinearGradient(0, 0, SW, SH * 0.5);
        veil.addColorStop(0, 'rgba(5,6,12,' + (0.34 + 0.1 * m).toFixed(3) + ')');
        veil.addColorStop(1, 'rgba(5,6,12,' + (0.1 + 0.1 * m).toFixed(3) + ')');
        g.fillStyle = veil;
        g.fillRect(0, 0, SW, SH);
        const top = g.createLinearGradient(0, 0, 0, SH * 0.2);
        top.addColorStop(0, 'rgba(5,6,12,0.5)');
        top.addColorStop(1, 'rgba(5,6,12,0)');
        g.fillStyle = top;
        g.fillRect(0, 0, SW, SH * 0.2);
      }
    }

    /** The sky as it rests in the scene on show: for stills, and for a phone that starts part-way through. */
    function settle(scene) {
      const sc = SCENES[scene] || SCENES.home;
      s.lv = sc.lv; s.mix = sc.mix; s.hue = sc.hue; s.dawn = sc.dawn; s.swell = 1; s.swellV = 0; s.pulse = 0;
    }

    function frame(ms) {
      if (s.t0 == null) s.t0 = ms;
      const t = 40 + (ms - s.t0) / 1000;
      const scene = ctx.root.dataset.scene;
      if (ctx.still) { settle(scene); paint(40); return; }
      const sc = SCENES[scene] || SCENES.home;
      // A phone that starts part-way through the demo starts from the sky of the page it is leaving.
      if (s.fresh) {
        s.fresh = false;
        settle(BEFORE[scene] || scene);
        const L = ctx.lines;   // and from the end of its scrollback, without gliding there
        L.style.scrollBehavior = 'auto'; L.scrollTop = L.scrollHeight; L.style.scrollBehavior = '';
      }
      const dt = Math.min(0.05, Math.max(0, (ms - s.last) / 1000));
      s.last = ms;
      const k = 1 - Math.exp(-dt / sc.tau);
      s.lv += (sc.lv - s.lv) * k;
      s.dawn += (sc.dawn - s.dawn) * k;
      s.hue += (sc.hue - s.hue) * (1 - Math.exp(-dt / 0.55));
      s.mix += (sc.mix - s.mix) * (1 - Math.exp(-dt / 0.6));
      s.swellV += (-(s.swell - 1) * 46 - s.swellV * 6.2) * dt;   // a spring: a kick swells the sky and it settles
      s.swell += s.swellV * dt;
      s.pulse *= Math.exp(-dt / 0.28);
      // At rest the lights only wander, a fraction of a pixel a frame: every third frame is plenty.
      const moving = Math.abs(sc.lv - s.lv) > 0.004 || Math.abs(sc.mix - s.mix) > 0.004 || Math.abs(sc.hue - s.hue) > 0.2
        || Math.abs(s.swell - 1) > 0.002 || Math.abs(s.swellV) > 0.004 || s.pulse > 0.01;
      if (!moving && (s.n = (s.n + 1) % 3)) return;
      paint(t);
    }

    fit();
    paint(s.t);
    // A browser that loses its graphics context hands the canvas back blank: a still would stay that way.
    canvas.addEventListener('contextrestored', () => paint(s.t));
    s.canvas = canvas;
    s.frame = frame;
    s.refit = () => { if (fit()) paint(s.t); };
    s.kick = (v) => { s.swellV += v; };
    s.tap = () => { s.pulse = Math.min(1.4, s.pulse + 0.55); };
    return s;
  }

  /* ---- helpers ---- */

  /** Where a line will rest, in percent of the phone, once the scrollback has finished gliding to its end. */
  function restRect(ctx, node) {
    const r = ctx.rect(node), L = ctx.lines;
    const pending = L.scrollHeight - L.clientHeight - L.scrollTop;
    if (pending > 0.5) r.y -= (pending / (ctx.root.getBoundingClientRect().height || 1)) * 100;
    return r;
  }
  const PAD_X = 2.9, PAD_Y = 0.62;   // how far the glass card reaches past its line, in percent of the phone
  function cardBox(r) {
    return { left: r.x - PAD_X, top: r.y - PAD_Y, right: 100 - r.x - r.w - PAD_X, bottom: 100 - r.y - r.h - PAD_Y };
  }
  const edges = (b) => ({ left: b.left + '%', top: b.top + '%', right: b.right + '%', bottom: b.bottom + '%' });
  const FULL = { left: '0%', top: '0%', right: '0%', bottom: '0%' };
  const RECEDED = { transform: 'scale(0.93)', filter: 'blur(1.7cqw)', opacity: 0.45 };
  const HERE = { transform: 'scale(1)', filter: 'blur(0cqw)', opacity: 1 };
  const quiet = (p) => p.catch(() => {});

  TL.register({
    id: 'aurora',
    name: 'Aurora',
    tagline: 'Soft glass over a slow northern sky',
    mood: 'Calm and expensive: crisp mono type and a few panes of frosted glass floating over a living aurora.',
    motion: [
      'Dawn: the sky brightens from night and the lock page rises into focus',
      'Unlock: the clock dissolves toward you while the aurora swells and turns',
      'Launch: the line lifts into a glass card that springs open into the app',
    ],
    palette: { bg: '#05060c', fg: '#f2f4fa', dim: '#a7afc6', accent: '#74ecd6', app: '#bcb5ff', ok: '#8cf0b4', err: '#ff9478' },
    light: false,
    fonts: ['Geist+Mono:wght@200;300;400'],
    hooks: {
      mount(ctx) {
        const sky = makeSky(ctx);
        skies.set(ctx, sky);
        ctx.bg.append(sky.canvas, ctx.el('div', 'aurora-grain'));
        ctx.fx.append(ctx.el('div', 'aurora-veil'));
        ctx.onFrame(sky.frame);
        const undo = [];
        if (window.ResizeObserver) {
          const size = new ResizeObserver(sky.refit);
          size.observe(ctx.root);
          undo.push(() => size.disconnect());
        }
        if (ctx.still && window.MutationObserver) {
          // A still has no frames after the first: redraw the sky when the scene it shows is set.
          const watch = new MutationObserver(() => sky.frame(0));
          watch.observe(ctx.root, { attributes: true, attributeFilter: ['data-scene'] });
          undo.push(() => watch.disconnect());
        }
        return () => undo.forEach((fn) => fn());
      },

      /* the lock clock: large light numerals, one element a glyph */
      clock(ctx, node, time) {
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        [...time].forEach((ch, i) => {
          const d = ctx.el('span', 'aurora-d', ch === ':' ? null : ch);
          if (ch === ':') { d.classList.add('is-colon'); d.append(ctx.el('span', 'aurora-colon', ch)); }
          d.style.setProperty('--i', i);
          node.append(d);
        });
      },

      /* dawn: the sky comes up (the frame loop, from the scene), then the lock page rises into focus */
      async powerOn(ctx) {
        const from = { opacity: 0, transform: 'translateY(6cqw) scale(0.95)', filter: 'blur(2.2cqw)' };
        const to = { opacity: 1, transform: 'translateY(0cqw) scale(1)', filter: 'blur(0cqw)' };
        const rise = (node, delay) => quiet(ctx.animate(node, [from, to], { duration: 950, delay, easing: SPRING, fill: 'backwards' }));
        let at = 320;
        for (const row of ctx.lines.children) {
          // the clock comes up a numeral at a time
          if (row.classList.contains('tl-clock')) for (const d of row.children) { rise(d, at); at += 60; }
          else { rise(row, at); at += 90; }
        }
        quiet(ctx.animate(ctx.status, [{ opacity: 0, transform: 'translateY(-3cqw)' }, { opacity: 1, transform: 'none' }], { duration: 700, delay: 420, easing: SOFT, fill: 'backwards' }));
        await ctx.sleep(1250);
      },

      line(ctx, node) {
        const sky = skies.get(ctx), scene = ctx.root.dataset.scene;
        if (scene === 'home' && sky && sky.floatUntil > performance.now()) node.classList.add('aurora-float');
        if (scene === 'lock' && !node.dataset.role) {
          // The boot log grows upwards above the clock: the lines already there glide up to make room.
          const up = (node.getBoundingClientRect().height / (ctx.root.getBoundingClientRect().width || 1)) * 100;
          for (let prev = node.previousElementSibling; prev; prev = prev.previousElementSibling) {
            quiet(ctx.animate(prev, [{ transform: 'translateY(' + up + 'cqw)' }, { transform: 'translateY(0cqw)' }], { duration: 300, easing: SOFT, composite: 'add' }));
          }
        }
      },

      /* typing is soft: the cursor glides to its new place, and the sky answers each key very slightly */
      key(ctx) {
        const sky = skies.get(ctx);
        if (sky) sky.tap();
        ctx.root.style.removeProperty('--aurora-fade');
        const cursor = ctx.prompt.querySelector('.tl-cursor');
        if (cursor) {
          quiet(ctx.animate(cursor, [
            { transform: 'translateX(-0.62em) scaleX(1.5)', boxShadow: '0 0 1.5em 0.22em rgba(126, 240, 220, 0.85)' },
            { transform: 'translateX(0em) scaleX(1)', boxShadow: '0 0 0.8em 0em rgba(126, 240, 220, 0.6)' },
          ], { duration: 280, easing: SOFT }));
        }
      },

      /* unlock: the clock comes toward the viewer and dissolves, the sky swells and turns */
      async granted(ctx) {
        const sky = skies.get(ctx), L = ctx.lines;
        L.style.overflow = 'visible';
        L.style.webkitMaskImage = L.style.maskImage = 'none';
        if (sky) sky.kick(1.7);
        const clock = L.querySelector('.tl-clock');
        [...L.children].filter((n) => n !== clock).forEach((row, i, all) => {
          quiet(ctx.animate(row, [
            { opacity: 1, transform: 'translateY(0cqw) scale(1)', filter: 'blur(0cqw)' },
            { opacity: 0, transform: 'translateY(-2.4cqw) scale(1.04)', filter: 'blur(1.2cqw)' },
          ], { duration: 360, delay: (all.length - 1 - i) * 22, easing: 'cubic-bezier(.4,0,.8,.5)', fill: 'forwards' }));
        });
        // "locked" leaves the status pill; the stylesheet brings the next word in when the home page is set
        const where = ctx.status.querySelector('.tl-status-l');
        if (where) quiet(ctx.animate(where, [{ opacity: 1, transform: 'translateY(0em)' }, { opacity: 0, transform: 'translateY(-0.5em)' }], { duration: 240, delay: 560, easing: 'ease-in', fill: 'forwards' }));
        if (clock) {
          // toward the viewer: it grows from its own middle while drifting to the middle of the screen
          const r = ctx.rect(clock);
          const dx = 50 - (r.x + r.w / 2), dy = (46 - (r.y + r.h / 2)) * 0.5;
          await ctx.animate(clock, [
            { opacity: 1, transform: 'translate(0cqw, 0cqh) scale(1)', filter: 'blur(0cqw)' },
            { opacity: 1, transform: 'translate(' + dx * 0.45 + 'cqw, ' + dy * 0.3 + 'cqh) scale(1.14)', filter: 'blur(0cqw)', offset: 0.34 },
            { opacity: 0, transform: 'translate(' + dx + 'cqw, ' + dy + 'cqh) scale(2.8)', filter: 'blur(2.4cqw)' },
          ], { duration: 700, delay: 60, easing: 'cubic-bezier(.45,0,.7,.35)', fill: 'forwards' });
        } else {
          await ctx.sleep(600);
        }
        await ctx.sleep(80);
        // the summary that prints next floats up from further below, in full view
        if (sky) sky.floatUntil = performance.now() + 1500;
        ctx.root.style.setProperty('--aurora-fade', '2.8em');
      },

      /* a notification drops in from the top edge, as glass over the text, and settles into its place */
      async notify(ctx, node) {
        const r = restRect(ctx, node);
        const fly = ctx.el('div', 'aurora-fly');
        const twin = node.cloneNode(true);
        twin.classList.remove('tl-in');
        fly.append(twin);
        fly.style.cssText = 'left:' + r.x + '%;top:' + r.y + '%;width:' + r.w + '%';
        node.style.visibility = 'hidden';
        ctx.fx.append(fly);
        const sky = skies.get(ctx);
        if (sky) sky.kick(0.45);
        try {
          // in the air it throws a deeper shadow, which closes up to its own as it lands
          const high = 'inset 0 0 0 max(1px, 0.07em) rgba(255, 255, 255, 0.17), inset 0 0.1em 0 rgba(255, 255, 255, 0.08), 0 1.7em 3.2em rgba(0, 0, 0, 0.5)';
          quiet(ctx.animate(twin, [{ boxShadow: high }, { boxShadow: high, offset: 0.3 }], { duration: 900, delay: 140, easing: 'ease-out', fill: 'backwards' }));
          await ctx.animate(fly, [
            { transform: 'translateY(' + -(r.y + r.h + 4) + 'cqh) scale(0.94)' },
            { transform: 'translateY(0cqh) scale(1)' },
          ], { duration: 900, delay: 140, easing: DROP, fill: 'backwards' });
        } finally {
          node.style.visibility = '';
          fly.remove();
        }
      },

      /* launch: the line lifts into a glass card, everything behind recedes, the card springs open into the app */
      async launch(ctx, line) {
        await ctx.sleep(200);   // the line arrives, and is read
        const box = cardBox(restRect(ctx, line));
        const card = ctx.el('div', 'aurora-card');
        const fill = ctx.el('div', 'aurora-card-fill');
        const twin = line.cloneNode(true);
        twin.classList.remove('tl-in');
        twin.style.left = PAD_X + 'cqw';
        card.append(fill, twin);
        Object.assign(card.style, edges(box));
        ctx.fx.append(card);
        quiet(ctx.animate(line, [{ opacity: 0 }, { opacity: 0 }], { duration: 1, fill: 'forwards' }));
        quiet(ctx.animate(ctx.screen, [HERE, RECEDED], { duration: 900, easing: 'cubic-bezier(.25,.7,.2,1)', fill: 'forwards' }));
        await ctx.animate(card, [
          { opacity: 0, transform: 'scale(0.97)' },
          { opacity: 1, transform: 'scale(1.045)' },
        ], { duration: 400, easing: BOUNCE, fill: 'both' });
        await ctx.sleep(50);
        const sky = skies.get(ctx);
        if (sky) sky.kick(1.1);   // seen through the glass as it opens: the sky swells once more
        quiet(ctx.animate(twin, [{ opacity: 1 }, { opacity: 0 }], { duration: 240, easing: 'ease-out', fill: 'forwards' }));
        quiet(ctx.animate(fill, [{ opacity: 0 }, { opacity: 0, offset: 0.3 }, { opacity: 1 }], { duration: 500, easing: 'ease-in-out', fill: 'forwards' }));
        quiet(ctx.animate(card, [
          Object.assign({ borderRadius: '4cqw', transform: 'scale(1.045)' }, edges(box)),
          Object.assign({ borderRadius: '0cqw', transform: 'scale(1)' }, FULL),
        ], { duration: 720, easing: SOFT, fill: 'forwards' }));
        await ctx.sleep(520);
      },

      /* back: the reverse. The app folds down into the card on its line, the terminal comes forward again. */
      async back(ctx) {
        const line = [...ctx.lines.querySelectorAll('.tl-launch')].pop();
        const box = cardBox(line ? ctx.rect(line) : { x: 6, y: 84, w: 88, h: 3 });
        const card = ctx.el('div', 'aurora-card');
        Object.assign(card.style, FULL);
        ctx.fx.append(card);
        const hole = 'inset(' + box.top + '% ' + box.right + '% ' + box.bottom + '% ' + box.left + '% round 4cqw)';
        const fold = { duration: 600, easing: SOFT, fill: 'forwards' };
        quiet(ctx.animate(ctx.screen, [RECEDED, HERE], { duration: 780, delay: 40, easing: SPRING, fill: 'backwards' }));
        quiet(ctx.animate(ctx.appview, [{ clipPath: 'inset(0% 0% 0% 0% round 0cqw)' }, { clipPath: hole }], fold));
        quiet(ctx.animate(ctx.appview, [{ opacity: 1 }, { opacity: 1, offset: 0.12 }, { opacity: 0, offset: 0.55 }, { opacity: 0 }], { duration: 600, fill: 'forwards' }));
        quiet(ctx.animate(card, [Object.assign({ borderRadius: '0cqw' }, FULL), Object.assign({ borderRadius: '4cqw' }, edges(box))], fold));
        // the glass shows as the app thins, rests on its line for a moment, then melts into it
        await ctx.animate(card, [{ opacity: 0 }, { opacity: 1, offset: 0.3 }, { opacity: 1, offset: 0.6 }, { opacity: 0 }], { duration: 780, fill: 'both' });
      },

      /* lock: the page sinks and loses focus, the sky dims to night (the frame loop, from the scene) */
      async powerOff(ctx) {
        quiet(ctx.animate(ctx.screen, [
          { opacity: 1, transform: 'translateY(0cqw) scale(1)', filter: 'blur(0cqw)' },
          { opacity: 0, transform: 'translateY(9cqw) scale(0.93)', filter: 'blur(2.6cqw)' },
        ], { duration: 760, easing: 'cubic-bezier(.5,0,.85,.55)', fill: 'forwards' }));
        await ctx.sleep(1300);
      },
    },
  });
})();
