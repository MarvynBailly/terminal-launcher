/*
 * Phosphor: a P3 amber tube. The script owns the glass (scanlines, static) and the five things the
 * tube does: warm up, roll, change channel, ring its bell, and die to a dot.
 */
(function () {
  'use strict';
  const states = new WeakMap();   // per phone: what mount built, for the other hooks
  const ROLL = 109;               // one picture and its blanking bar, in cqh
  const TILE = 128;               // the side of a tile of snow

  /** A tile of snow: amber sparks, mostly dark. */
  function snowTile() {
    const n = TILE, tile = document.createElement('canvas');
    tile.width = tile.height = n;
    const g = tile.getContext('2d'), img = g.createImageData(n, n), d = img.data;
    for (let i = 0; i < d.length; i += 4) {
      const v = Math.random(), a = v * v;
      d[i] = 255; d[i + 1] = 140 + 80 * v; d[i + 2] = 150 * a * a; d[i + 3] = 255 * a;
    }
    g.putImageData(img, 0, 0);
    return tile;
  }

  /** The page as it stands, for a ghost or a rolling copy: nothing in it arrives again. */
  function copyOf(c, blank) {
    const page = c.screen.cloneNode(true);
    page.removeAttribute('style');
    page.classList.add('ph-copy');
    page.querySelectorAll('.tl-in').forEach((n) => n.classList.remove('tl-in'));
    if (blank) page.querySelector('.tl-lines').replaceChildren();
    return page;
  }

  // text at rest and overdriven, for the moment the tube overshoots (the resting values mirror the stylesheet)
  const COOL_TEXT = '0 0 0.05em #ffb000, 0 0 0.4em rgba(255,176,0,0.52), 0 0 0 rgba(255,176,0,0)';
  const HOT_TEXT = '0 0 0.1em #fff3d6, 0 0 0.6em #ffb000, 0 0 1.5em rgba(255,176,0,0.8)';
  const COOL_FACE = '0 0 0.02em #ffb000, 0 0 0.13em rgba(255,186,60,0.7), 0 0 0.5em rgba(255,176,0,0.28)';
  const HOT_FACE = '0 0 0.04em #fff3d6, 0 0 0.22em #ffc24a, 0 0 0.8em rgba(255,176,0,0.75)';

  /** Reduced motion: every transition is a short fade instead. */
  const fade = (c, node, from, to) => c.animate(node, [{ opacity: from }, { opacity: to }], { duration: 220, fill: to ? 'none' : 'forwards' });

  const tf = (x, skew, sx, sy) => `translateX(${x}cqw) skewX(${skew}deg) scale(${sx}, ${sy})`;

  TL.register({
    id: 'phosphor',
    name: 'Phosphor',
    tagline: 'An amber tube from 1983',
    mood: 'A warm monochrome monitor behind deep glass: every line is drawn by the beam and cools as you read it.',
    motion: [
      'Wakes from a hot line that blooms open, then degausses',
      'Unlocking loses vertical hold: the page rolls away, burnt in',
      'Apps cut in through static; locking dies to a lingering dot',
    ],
    palette: { bg: '#0a0602', fg: '#ffb000', dim: '#c08219', accent: '#ffe6b3', app: '#ffcd68', ok: '#ffcd68', err: '#ffb000' },
    light: false,
    fonts: ['VT323'],
    hooks: {
      mount(c) {
        const raster = c.el('div', 'ph-raster');
        c.bg.append(raster);
        const ghost = c.el('div', 'ph-ghost'), band = c.el('div', 'ph-band');
        c.fx.append(ghost, band);
        const cv = c.canvas(c.fx);
        cv.canvas.classList.add('ph-scan');
        const glass = c.el('div', 'ph-glass'), flick = c.el('div', 'ph-flick');
        c.fx.append(glass, flick);

        const st = { ghost, keys: [], env: null, back: false, away: false, w: 0, h: 0, lines: null, snow: null, noisy: false };
        states.set(c, st);

        /** Static comes up over `a` ms to `peak`, holds for `h`, and clears over `d`. */
        st.burst = (peak, a, h, d) => {
          if (!st.snow) st.snow = [snowTile(), snowTile(), snowTile()].map((t) => cv.g.createPattern(t, 'repeat'));
          st.env = { t0: performance.now(), peak, a, h, d };
          st.env0 = st.env.t0;
        };
        function level(now) {
          const e = st.env;
          if (!e) return 0;
          const t = now - e.t0;
          if (t < e.a) return e.peak * Math.max(0, t / e.a);
          if (t < e.a + e.h) return e.peak;
          if (t < e.a + e.h + e.d) return e.peak * (1 - (t - e.a - e.h) / e.d);
          st.env = null;
          return 0;
        }

        /** Scanlines as a tile one pixel wide: a whole number of device pixels each, so they never beat. */
        function scanlines() {
          const pitch = Math.max(2, Math.round(cv.w * 0.0077));
          const tile = document.createElement('canvas');
          tile.width = 1; tile.height = pitch;
          const g = tile.getContext('2d');
          for (let r = 0; r < pitch; r++) {
            const a = 0.4 * Math.max(0, 1 - Math.abs((r + 0.5) / pitch - 5 / 6) * 6);
            if (a > 0) { g.fillStyle = `rgba(0,0,0,${a})`; g.fillRect(0, r, 1, 1); }
          }
          return cv.g.createPattern(tile, 'repeat');
        }

        function snow(g, w, h, lv, now) {
          const k = Math.max(2, Math.round(w / 160));
          g.globalAlpha = Math.min(1, lv * 1.25);
          g.fillStyle = '#070400';
          g.fillRect(0, 0, w, h);                      // the picture drops out behind the snow
          g.imageSmoothingEnabled = false;
          const first = (Math.random() * st.snow.length) | 0;
          for (let i = 0; i < 2; i++) {                // two tiles over each other at odd offsets: no pattern shows
            const p = st.snow[(first + i) % st.snow.length];
            p.setTransform(new DOMMatrix([k, 0, 0, k, -Math.random() * TILE * k, -Math.random() * TILE * k]));
            g.globalAlpha = Math.min(1, lv) * (i ? 0.6 : 0.85);
            g.fillStyle = p;
            g.fillRect(0, 0, w, h);
          }
          g.fillStyle = '#ffb840';                     // a set between stations: bright tears, and the dark bar rolling through
          for (let i = 0; i < 3; i++) {
            g.globalAlpha = lv * (0.05 + Math.random() * 0.2);
            g.fillRect(0, Math.random() * h, w, h * (0.004 + Math.random() * 0.035));
          }
          g.globalAlpha = Math.min(1, lv) * 0.82;
          g.fillStyle = '#050300';
          g.fillRect(0, (((now - st.env0) * 0.0021) % 1.25 - 0.14) * h, w, h * 0.11);
          g.globalAlpha = 1;
        }

        /** Runs every frame and, unless static is up or something changed, does nothing. */
        function draw(now) {
          const g = cv.g, w = cv.w, h = cv.h;
          const scene = c.root.dataset.scene;
          if (scene !== 'app') st.back = false;
          const away = scene === 'app' && !st.back;    // another app has the screen: the glass is not ours
          const lv = level(now);
          if (w === st.w && h === st.h && away === st.away && !lv && !st.noisy) return;
          if (w !== st.w || h !== st.h) { st.w = w; st.h = h; st.lines = scanlines(); }
          if (away !== st.away) {
            st.away = away;
            for (const node of [band, glass, flick]) node.classList.toggle('is-away', away);
          }
          st.noisy = lv > 0;
          g.clearRect(0, 0, w, h);
          if (lv > 0) snow(g, w, h, lv, now);
          if (!away) { g.fillStyle = st.lines; g.fillRect(0, 0, w, h); }
        }

        // A still is drawn once, so if its phone is resized later the canvas must be drawn again.
        let ro = null;
        if (c.still && window.ResizeObserver) {
          ro = new ResizeObserver(() => { st.w = 0; draw(performance.now()); });
          ro.observe(c.root);
        }
        c.onFrame(draw);
        return () => { if (ro) ro.disconnect(); };
      },

      /** The time in double-size characters, each in its own cell. */
      clock(c, node, time) {
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        const face = c.el('span', 'ph-time');
        for (const ch of time) face.append(c.el('b', '', ch));
        node.append(face);
      },

      /** The beam travels at one speed: a line takes as long as it is wide. */
      line(c, node, d) {
        if (d.t === 'text' || d.t === 'launch') node.style.setProperty('--ph-n', node.textContent.length);
      },

      /** The newest characters stand where the cursor just was, and are still cooling. */
      key(c) {
        const st = states.get(c), input = c.prompt.querySelector('.tl-input');
        if (!st || !input || c.reduced) return;
        const chars = [...input.textContent], now = performance.now();
        if (chars.length <= 1) st.keys = [];
        st.keys.push(now);
        if (st.keys.length > 4) st.keys.shift();
        const k = Math.min(st.keys.length, chars.length);
        input.textContent = chars.slice(0, chars.length - k).join('');
        for (let i = k; i > 0; i--) {
          const cell = c.el('span', 'ph-key', chars[chars.length - i]);
          cell.style.animationDelay = Math.round(st.keys[st.keys.length - i] - now) + 'ms';
          input.append(cell);
        }
      },

      /** Warming up: a hot line across the middle, the picture blooming open too big and too bright, then the degauss. */
      async powerOn(c) {
        if (c.reduced) return fade(c, c.screen, 0, 1);
        const beam = c.el('div', 'ph-beam'), flash = c.el('div', 'ph-flash');
        c.fx.append(flash, beam);
        const T = 1100;
        c.animate(beam, [
          { transform: 'scale(0, 1)', opacity: 1, easing: 'cubic-bezier(.3,.7,.3,1)' },
          { transform: 'scale(1, 1)', opacity: 1, offset: 0.15 },
          { transform: 'scale(1, 2)', opacity: 1, offset: 0.19, easing: 'ease-out' },
          { transform: 'scale(1.03, 4)', opacity: 0, offset: 0.26 },
          { transform: 'scale(1.03, 4)', opacity: 0 },
        ], { duration: T, fill: 'both' }).catch(() => {});
        c.animate(flash, [
          { opacity: 0 }, { opacity: 0, offset: 0.17 }, { opacity: 1, offset: 0.36 },
          { opacity: 0.3, offset: 0.52 }, { opacity: 0.12, offset: 0.7 }, { opacity: 0 },
        ], { duration: T, fill: 'both' }).catch(() => {});
        const face = c.lines.querySelector('.ph-time');
        if (face) c.animate(face, [
          { textShadow: HOT_FACE }, { textShadow: HOT_FACE, offset: 0.4, easing: 'ease-out' }, { textShadow: COOL_FACE, offset: 0.72 }, { textShadow: COOL_FACE },
        ], { duration: T }).catch(() => {});
        c.animate(c.screen, [
          { color: '#fff3d6', textShadow: HOT_TEXT }, { color: '#fff3d6', textShadow: HOT_TEXT, offset: 0.4, easing: 'ease-out' },
          { color: '#ffb000', textShadow: COOL_TEXT, offset: 0.72 }, { color: '#ffb000', textShadow: COOL_TEXT },
        ], { duration: T }).catch(() => {});
        const io = 'ease-in-out';
        await c.animate(c.screen, [
          { transform: tf(0, 0, 1, 0.004), opacity: 0, easing: 'steps(1, end)' },
          { transform: tf(0, 0, 1, 0.004), opacity: 1, offset: 0.17, easing: 'cubic-bezier(.2,.7,.2,1)' },
          { transform: tf(0, 0, 1.035, 1.05), offset: 0.43, easing: io },
          { transform: tf(1.3, -0.9, 0.985, 0.99), offset: 0.53, easing: io },
          { transform: tf(-1, 0.7, 1.01, 1.008), offset: 0.63, easing: io },
          { transform: tf(0.7, -0.45, 0.996, 0.997), offset: 0.73, easing: io },
          { transform: tf(-0.4, 0.25, 1.002, 1.002), offset: 0.82, easing: io },
          { transform: tf(0.18, -0.1, 1, 1), offset: 0.91, easing: io },
          { transform: tf(0, 0, 1, 1), opacity: 1 },
        ], { duration: T });
        beam.remove();
        flash.remove();
      },

      /** Vertical hold lets go: the lock page rolls up and away, and what it burnt into the phosphor fades under the new page. */
      async granted(c) {
        const st = states.get(c);
        if (c.reduced) return fade(c, c.lines, 1, 0);
        const word = c.say('access granted', 'ok');
        word.dataset.role = 'granted';
        word.style.setProperty('--ph-n', 14);
        word.classList.add('tl-in');
        await c.sleep(340);

        const burn = copyOf(c);
        st.ghost.replaceChildren(burn);
        const gone = () => burn.remove();
        c.sleep(2600).then(gone, gone);                // it outlives this hook, so it lives in a node mount made

        const roll = c.el('div', 'ph-roll');
        [1, 0.7, 0.4, 1].forEach((opacity, i) => {
          const page = copyOf(c, i === 3);             // the last copy is the empty page the hold catches on
          page.style.top = i * ROLL + 'cqh';
          page.style.opacity = opacity;
          roll.append(page);
          if (i) {
            const bar = c.el('div', 'ph-vbi');
            bar.style.top = i * ROLL - 9 + 'cqh';
            roll.append(bar);
          }
        });
        c.fx.append(roll);
        c.screen.style.visibility = 'hidden';
        const at = (y, skew) => `translateY(${-y}cqh) skewX(${skew}deg)`;
        const end = 3 * ROLL;
        await c.animate(roll, [
          { transform: at(0, 0), easing: 'ease-in-out' },
          { transform: at(5, -0.5), offset: 0.14, easing: 'ease-in-out' },
          { transform: at(3, 0.3), offset: 0.22, easing: 'cubic-bezier(.6,0,.25,1)' },
          { transform: at(end + 1.4, -0.4), offset: 0.86, easing: 'ease-in-out' },
          { transform: at(end - 0.6, 0.15), offset: 0.94, easing: 'ease-in-out' },
          { transform: at(end, 0) },
        ], { duration: 900, fill: 'forwards' });
      },

      /** The visual bell: the whole tube lifts for an instant. */
      async notify(c) {
        if (c.reduced) return;
        const bell = c.el('div', 'ph-bell');
        c.fx.append(bell);
        await c.animate(bell, [{ opacity: 0 }, { opacity: 0.2, offset: 0.14 }, { opacity: 0.05, offset: 0.5 }, { opacity: 0 }], { duration: 260 });
        bell.remove();
      },

      /** A channel change: horizontal hold slips, snow, and the snow clears on the other station. */
      async launch(c) {
        const st = states.get(c);
        await c.sleep(300);
        if (c.reduced) return fade(c, c.screen, 1, 0);
        st.burst(1, 110, 260, 300);
        await c.animate(c.screen, [
          { transform: tf(0, 0, 1, 1), opacity: 1 },
          { transform: tf(-2.5, 5, 1, 1), opacity: 1, offset: 0.25 },
          { transform: tf(4, -12, 1, 0.96), opacity: 0.8, offset: 0.5 },
          { transform: tf(-7, 20, 1, 0.9), opacity: 0.5, offset: 0.75 },
          { transform: tf(10, -30, 1, 0.8), opacity: 0 },
        ], { duration: 210, easing: 'steps(6, jump-none)', fill: 'forwards' });
        await c.sleep(140);
      },

      /** Back to our station: snow again, and the picture drops into place. */
      async back(c) {
        const st = states.get(c);
        st.back = true;
        if (c.reduced) return fade(c, c.appview, 1, 0);
        st.burst(1, 90, 110, 260);
        await c.sleep(110);
        await c.animate(c.appview, [{ opacity: 1 }, { opacity: 0 }], { duration: 50, fill: 'forwards' });
        await c.animate(c.screen, [
          { transform: 'translateY(-10cqh) scaleY(1.05)', opacity: 0.5 },
          { transform: 'translateY(1.6cqh) scaleY(1)', opacity: 1, offset: 0.55 },
          { transform: 'translateY(-0.5cqh) scaleY(1)', offset: 0.8 },
          { transform: 'translateY(0) scaleY(1)', opacity: 1 },
        ], { duration: 330, easing: 'ease-out' });
      },

      /** Switched off: the picture falls shut to a line, the line to a spot, and the spot takes its time. */
      async powerOff(c) {
        if (c.reduced) return fade(c, c.screen, 1, 0);
        const beam = c.el('div', 'ph-beam'), dot = c.el('div', 'ph-dot');
        c.fx.append(beam, dot);
        c.animate(beam, [
          { opacity: 0, transform: 'scale(1, 5)' }, { opacity: 0, transform: 'scale(1, 5)', offset: 0.5 }, { opacity: 1, transform: 'scale(1, 1)' },
        ], { duration: 190, fill: 'both' }).catch(() => {});
        await c.animate(c.screen, [
          { transform: 'scale(1, 1)', opacity: 1 },
          { transform: 'scale(1, 0.004)', opacity: 1, offset: 0.96 },
          { transform: 'scale(1, 0.004)', opacity: 0 },
        ], { duration: 190, easing: 'cubic-bezier(.6,0,1,.5)', fill: 'forwards' });
        c.animate(dot, [{ opacity: 0 }, { opacity: 1 }], { duration: 200, delay: 60, fill: 'both' }).catch(() => {});
        await c.animate(beam, [
          { transform: 'scale(1, 1)', opacity: 1 },
          { transform: 'scale(0.02, 1.6)', opacity: 1, offset: 0.92 },
          { transform: 'scale(0.02, 1.6)', opacity: 0 },
        ], { duration: 260, easing: 'cubic-bezier(.5,0,.9,.4)', fill: 'forwards' });
        await c.animate(dot, [
          { opacity: 1, transform: 'scale(1)' },
          { opacity: 0.9, transform: 'scale(0.8)', offset: 0.3 },
          { opacity: 0.45, transform: 'scale(0.5)', offset: 0.7 },
          { opacity: 0, transform: 'scale(0.25)' },
        ], { duration: 950, easing: 'ease-out', fill: 'forwards' });
      },
    },
  });
})();
