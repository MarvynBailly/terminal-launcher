/*
 * ANSI: a dial-up bulletin board from 1994. Two things move, and both are what a 14.4k modem and
 * a text screen do:
 *
 *   text arrives      every new line is held back and then received one character at a time, in
 *                     order, down one serial line (the queue below), each in a bright burst colour
 *   the screen is     a 44 x 38 text screen laid over the phone (a canvas in the fx layer), which
 *   overwritten       transitions fill with block and shade characters and then clear
 */
(function () {
  'use strict';
  const COLS = 44, ROWS = 38, N = COLS * ROWS;
  const VGA = ['#000000', '#0000AA', '#00AA00', '#00AAAA', '#AA0000', '#AA00AA', '#AA5500', '#AAAAAA',
    '#555555', '#5555FF', '#55FF55', '#55FFFF', '#FF5555', '#FF55FF', '#FFFF55', '#FFFFFF'];
  const BRIGHT = [11, 14, 13, 10, 15];        // cyan, yellow, magenta, green, white
  const NOISE = [11, 14, 13, 10, 15, 9, 12];
  const SP = 1, S1 = 2, S2 = 3, S3 = 4, FB = 5;   // a space, light / medium / dark shade, a full block
  const cell = (glyph, fg, bg) => (bg || 0) << 8 | glyph << 4 | fg;
  const MS = 2.3;                             // per character: fast, and still one at a time
  const states = new WeakMap();

  /* ---------------- the text screen over the phone ---------------- */

  /** One row of a shade character in `fg` on `bg`, as wide as the screen: cells are cut out of it. */
  function strip(st, glyph, fg, bg) {
    const key = cell(glyph, fg, bg);
    let s = st.strips[key];
    if (s) return s;
    s = st.strips[key] = document.createElement('canvas');
    const rh = st.h / ROWS, dx = st.w / (COLS * 4), dy = rh / 10;
    s.width = st.w;
    s.height = Math.ceil(rh) + 1;
    const g = s.getContext('2d');
    g.fillStyle = VGA[bg];
    g.fillRect(0, 0, s.width, s.height);
    g.fillStyle = VGA[fg];
    for (let j = 0; j < 11; j++) {
      const y = Math.round(j * dy), hh = Math.round((j + 1) * dy) - y;
      for (let i = 0; i < COLS * 4; i++) {
        const light = (i & 3) === (j & 1 ? 2 : 0), hole = (i & 3) === (j & 1 ? 3 : 1);
        if (glyph === S1 ? light : glyph === S2 ? ((i + j) & 1) === 0 : !hole) {
          const x = Math.round(i * dx);
          g.fillRect(x, y, Math.round((i + 1) * dx) - x, hh);
        }
      }
    }
    return s;
  }

  function paint(st) {
    const { g, w, h } = st.cv;
    if (st.w !== w || st.h !== h) {
      st.w = w; st.h = h; st.strips = {};
      st.xs = Array.from({ length: COLS + 1 }, (_, c) => Math.round((c * w) / COLS));
      st.ys = Array.from({ length: ROWS + 1 }, (_, r) => Math.round((r * h) / ROWS));
    }
    g.clearRect(0, 0, w, h);
    const { buf, xs, ys } = st;
    for (let r = 0, n = 0; r < ROWS; r++, n += COLS) {
      const y = ys[r], hh = ys[r + 1] - y;
      for (let c = 0; c < COLS;) {
        const v = buf[n + c];
        let e = c + 1;
        while (e < COLS && buf[n + e] === v) e++;
        if (v) {
          const x = xs[c], ww = xs[e] - x, glyph = (v >> 4) & 15;
          if (glyph === SP || glyph === FB) {
            g.fillStyle = VGA[glyph === FB ? v & 15 : v >> 8];
            g.fillRect(x, y, ww, hh);
          } else {
            g.drawImage(strip(st, glyph, v & 15, v >> 8), x, 0, ww, hh, x, y, ww, hh);
          }
        }
        c = e;
      }
    }
  }

  function wipeOff(st) {
    st.fx = null;
    st.buf.fill(0);
    st.cv.g.clearRect(0, 0, st.cv.w, st.cv.h);
  }

  /** Waits while an effect runs. If the demo is cancelled meanwhile, the screen is wiped clean first. */
  async function hold(ctx, st, ms) {
    try { await ctx.sleep(ms); } catch (e) { wipeOff(st); throw e; }
  }

  /*
   * Waking: the dark screen is written over from the top left, cell by cell, by a head of white
   * shade trailing bright cyan, cyan and blue, and is then cleared the same way to the page.
   */
  function wake(t0) {
    const T1 = 280, GAP = 50, T2 = 300;
    return (buf, now) => {
      const t = Math.max(0, now - t0);
      const head = (N * t) / T1, tail = N * Math.min(1, Math.max(0, (t - T1 - GAP) / T2));
      for (let n = 0; n < N; n++) {
        let v = 0;
        if (n >= head) v = cell(SP, 0);
        else if (n >= tail) {
          const age = (head - n) / COLS, left = (n - tail) / COLS;
          if (age < 0.25) v = cell(S1, 15);
          else if (age < 0.5) v = cell(S2, 15);
          else if (age < 0.8) v = cell(S3, 11);
          else if (age < 3) v = cell(FB, 11);
          else if (age < 5) v = cell(S3, 11, 3);
          else if (age < 8) v = cell(FB, 3);
          else if (age < 11) v = cell(S2, 3, 1);
          else v = cell(S2, 9, 1);
          if (tail > 0) {
            const fg = age < 5 ? 11 : age < 11 ? 3 : 9;
            if (left < 0.4) v = cell(S1, fg);
            else if (left < 0.9) v = cell(S2, fg);
            else if (left < 1.5) v = cell(S3, fg);
          }
        }
        buf[n] = v;
      }
      return t < T1 + GAP + T2;
    };
  }

  /*
   * Unlocking: the page between the two bars is overwritten row by row with bands of shade, three
   * rows to a colour, and the colours keep cycling up through the bands. When the hook lets go
   * (`fx.rel`), the bands are cleared from the top.
   */
  function bands(t0) {
    const T1 = 300, T2 = 260, AREA = (ROWS - 2) * COLS, SHADES = [S3, FB, S2];
    const fx = (buf, now) => {
      const t = Math.max(0, now - t0), rel = fx.rel ? Math.max(0, now - fx.rel) : -1;
      const head = AREA * Math.min(1, t / T1), tail = rel < 0 ? 0 : AREA * Math.min(1, rel / T2);
      const shift = (t / 45) | 0;
      for (let r = 1; r < ROWS - 1; r++) {
        const fg = BRIGHT[(((r + shift) / 3) | 0) % BRIGHT.length], glyph = SHADES[(r + shift) % 3];
        for (let c = 0; c < COLS; c++) {
          const m = (r - 1) * COLS + c;
          let v = 0;
          if (m >= tail && m < head) {
            v = cell(glyph, fg);
            if (head < AREA && head - m < 10) v = cell(head - m < 5 ? S1 : S2, 15);
            else if (rel >= 0 && m - tail < COLS * 1.5) v = cell(m - tail < COLS * 0.6 ? S1 : S2, fg);
          }
          buf[r * COLS + c] = v;
        }
      }
      return rel < 0 || rel < T2;
    };
    return fx;
  }

  /*
   * Launching and coming back: random block and shade characters take the screen over, thicken
   * until nothing else is left and, once the hook lets go, thin out again. `row` is the line the
   * launch was typed on: the noise spreads from it, or closes in on it.
   */
  function dissolve(t0, row, spread) {
    const T1 = 300, T2 = 400, STEP = 0.0625;
    const on = new Float32Array(N), off = new Float32Array(N), seed = new Uint8Array(N), ink = new Uint8Array(N);
    for (let n = 0, run = 0, fg = 0; n < N; n++) {
      const d = Math.min(1, Math.abs(((n / COLS) | 0) - row) / (ROWS * 0.7));
      on[n] = spread ? 0.5 * Math.random() + 0.5 * d : Math.random();
      off[n] = spread ? Math.random() : 0.5 * Math.random() + 0.5 * (1 - d);
      seed[n] = (Math.random() * 251) | 0;
      // line noise keeps the colour it was last told: the colours come in runs along the rows
      if (--run < 0) { run = (Math.random() * Math.random() * 30) | 0; fg = NOISE[(Math.random() * NOISE.length) | 0]; }
      ink[n] = fg;
    }
    const fx = (buf, now) => {
      const t = Math.max(0, now - t0), rel = fx.rel ? Math.max(0, now - fx.rel) : -1;
      const p = Math.min(1, t / T1) * 1.25, q = (rel / T2) * 1.25, beat = (t / 100) | 0;
      for (let n = 0; n < N; n++) {
        const lv = Math.ceil(rel < 0 ? (p - on[n]) / STEP : (off[n] + 0.25 - q) / STEP);
        if (lv <= 0) { buf[n] = 0; continue; }
        const roll = (seed[n] + (((beat + seed[n]) / 3) | 0) * 7) % 21;
        buf[n] = cell(S1 - 1 + Math.min(lv, 2 + (roll % 3), 4), ink[n]);
      }
      return rel < 0 || q < 1.25;
    };
    return fx;
  }

  /* ---------------- text arriving, one character at a time ---------------- */

  function enqueue(ctx, st, node, n, cost) {
    if (n <= 0) return;
    node.classList.add('bbs-hold');
    st.queue.push({ node, n, cost, k: 0 });
    if (!st.busy) { st.busy = true; ctx.root.classList.add('bbs-busy'); }
  }

  function settle(seg) {
    seg.node.classList.remove('bbs-hold', 'bbs-rx');
    seg.node.style.removeProperty('--k');
  }

  /** Everything still on the line arrives at once. */
  function flush(st) {
    if (st.cur) settle(st.cur);
    st.queue.forEach(settle);
    st.hot.forEach((h) => h.node.classList.remove('bbs-hot'));
    st.cur = null; st.queue.length = 0; st.hot.length = 0; st.waitUntil = 0;
  }

  function receive(ctx, st, now) {
    const dt = Math.min(50, now - (st.last || now));
    st.last = now;
    while (st.hot.length && st.hot[0].until <= now) {
      const node = st.hot.shift().node;
      node.classList.remove('bbs-hot');
      node.style.removeProperty('--burst');
    }
    if (!st.cur && !st.queue.length) {
      st.spare = 0;
      if (st.busy) { st.busy = false; ctx.root.classList.remove('bbs-busy'); }
      return;
    }
    if (now < st.waitUntil) return;
    st.spare += dt;
    for (;;) {
      if (!st.cur) {
        const seg = st.queue.shift();
        if (!seg) break;
        if (!seg.node.isConnected) continue;
        st.cur = seg;
        seg.node.classList.remove('bbs-hold');
        seg.node.classList.add('bbs-rx');
        seg.node.style.setProperty('--burst', VGA[BRIGHT[st.turn++ % BRIGHT.length]]);
      }
      const seg = st.cur, cost = seg.cost ? seg.cost(seg.k) : MS;
      if (!seg.node.isConnected) { st.cur = null; continue; }      // the page was changed under it
      if (st.spare < cost) break;
      st.spare -= cost;
      if (++seg.k >= seg.n) {
        settle(seg);
        seg.node.classList.add('bbs-hot');
        st.hot.push({ node: seg.node, until: now + 130 });
        st.cur = null;
      }
    }
    if (st.cur) st.cur.node.style.setProperty('--k', st.cur.k);
  }

  /** Resolves when the line is quiet. */
  async function drain(ctx, st) {
    while (st.cur || st.queue.length) await ctx.sleep(16);
  }

  const ready = (ctx) => (ctx.reduced ? null : states.get(ctx));

  /* ---------------- the look ---------------- */

  TL.register({
    id: 'bbs',
    name: 'ANSI',
    tagline: 'A 1994 dial-up board in sixteen colours',
    mood: 'A bulletin board at 14.4k: cyan bars, blue panels and box frames on black, every mark a character cell.',
    motion: [
      'Text arrives one character at a time, in bright bursts',
      'Unlocking overwrites the page with cycling colour bands',
      'Apps dissolve into block and shade characters',
    ],
    palette: { bg: '#000000', fg: '#AAAAAA', dim: '#00AAAA', accent: '#FFFF55', app: '#55FFFF', ok: '#55FF55', err: '#FF5555' },
    light: false,
    fonts: ['VT323'],
    hooks: {
      mount(ctx) {
        if (ctx.still) return;
        const st = {
          cv: ctx.canvas(ctx.fx), buf: new Uint16Array(N), strips: {}, w: 0, h: 0, fx: null,
          queue: [], hot: [], cur: null, busy: false, spare: 0, turn: 0, last: 0, waitUntil: 0,
        };
        states.set(ctx, st);
        ctx.onFrame((now) => {
          receive(ctx, st, now);
          if (!st.fx) return;
          const alive = st.fx(st.buf, now);
          if (alive) paint(st); else wipeOff(st);
        });
      },

      async powerOn(ctx) {
        const st = ready(ctx);
        if (!st) return TL.defaults.powerOn(ctx);
        flush(st);
        st.fx = wake(performance.now());
        st.fx(st.buf, performance.now());
        paint(st);                                  // dark before the page under it is ever shown
        await hold(ctx, st, 640);
        wipeOff(st);
      },

      line(ctx, node, d) {
        const st = ready(ctx);
        if (!st) return;
        if (d.t === 'text' || d.t === 'launch') {
          enqueue(ctx, st, node, node.textContent.length + (d.t === 'launch' ? 2 : 0));
        } else if (d.t === 'gauge') {
          const filled = node.querySelectorAll('.tl-gauge-bar i.on').length;
          // the label and the note at the modem's pace, the blocks one cell at a time, slower
          enqueue(ctx, st, node, 21 + d.note.length, (k) => (k >= 6 && k < 6 + filled ? 9 : MS));
        } else if (d.t === 'grid') {
          const apps = [...node.children], rows = Math.ceil(apps.length / 3);
          for (let r = 0; r < rows; r++) {
            for (let i = r; i < apps.length; i += rows) enqueue(ctx, st, apps[i], apps[i].textContent.length);
          }
        } else if (d.t === 'note') {
          node.classList.add('bbs-open');
          node.style.setProperty('--ix', 14);
          node.style.setProperty('--iy', 1);
        }
      },

      key(ctx) {
        const st = ready(ctx);
        if (!st) return;
        if (st.cur || st.queue.length) flush(st);
        // a cursor does not blink while it is being typed with
        const cursor = ctx.prompt.querySelector('.tl-cursor');
        if (cursor) ctx.animate(cursor, [{ opacity: 1 }, { opacity: 1 }], 400).catch(() => {});
      },

      async granted(ctx) {
        const st = ready(ctx);
        if (!st) return TL.defaults.granted(ctx);
        const word = ctx.say('access granted', 'ok');
        word.dataset.role = 'granted';
        enqueue(ctx, st, word, 14);
        await drain(ctx, st);
        await ctx.sleep(170);
        const fx = (st.fx = bands(performance.now()));
        await hold(ctx, st, 340);
        fx.rel = performance.now();
        // the summary's frames are drawn first; what is in them follows a moment behind
        st.waitUntil = performance.now() + 300;
      },

      async notify(ctx, node) {
        const st = ready(ctx);
        if (!st) return;
        // the box opens from its middle, a few cells at a time, with its shadow
        for (const [ix, iy] of [[14, 1], [9, 0], [4, 0]]) {
          node.style.setProperty('--ix', ix);
          node.style.setProperty('--iy', iy);
          await ctx.sleep(55);
        }
        node.style.removeProperty('--ix');
        node.style.removeProperty('--iy');
        const text = node.querySelector('.tl-note-text');
        enqueue(ctx, st, text, text.textContent.length);
        node.classList.remove('bbs-open');
        await drain(ctx, st);
      },

      async launch(ctx, line) {
        const st = ready(ctx);
        if (!st) return TL.defaults.launch(ctx, line);
        await drain(ctx, st);
        await ctx.sleep(280);
        const r = ctx.rect(line);
        const fx = (st.fx = dissolve(performance.now(), Math.round(((r.y + r.h / 2) / 100) * ROWS - 0.5), true));
        await hold(ctx, st, 330);
        fx.rel = performance.now();
      },

      async back(ctx) {
        const st = ready(ctx);
        if (!st) return TL.defaults.back(ctx);
        const last = ctx.lines.lastElementChild, r = last ? ctx.rect(last) : { y: 50, h: 0 };
        const fx = (st.fx = dissolve(performance.now(), Math.round(((r.y + r.h / 2) / 100) * ROWS - 0.5), false));
        await hold(ctx, st, 330);
        fx.rel = performance.now();
      },

      async powerOff(ctx) {
        const st = ready(ctx);
        if (!st) return TL.defaults.powerOff(ctx);
        flush(st);
        const last = ctx.lines.lastElementChild, r = last ? ctx.rect(last) : { y: 0, h: 0 };
        const cursor = ctx.el('i', 'bbs-cur');
        // the cursor is left on the row under the last line: on a full page, the bottom row
        cursor.style.setProperty('--r', Math.max(1, Math.min(ROWS - 1, Math.round(((r.y + r.h) / 100) * ROWS))));
        await ctx.sleep(140);
        ctx.fx.append(cursor);
        // the page scrolls away two rows at a time, bars and all
        await ctx.animate(ctx.screen, [{ transform: 'translateY(0)' }, { transform: 'translateY(-100%)' }],
          { duration: 330, easing: 'steps(19, start)', fill: 'forwards' });
        for (let i = 0; i < 2; i++) {
          await ctx.sleep(240);
          cursor.style.visibility = 'hidden';
          await ctx.sleep(170);
          if (!i) cursor.style.visibility = '';
        }
      },
    },
  });
})();
