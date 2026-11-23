/*
 * Dot Matrix: a four-shade reflective LCD. It moves the way that hardware could: a few frames a
 * second, palette changes in hard steps, and dots that are slow to respond, so whatever moves
 * leaves a paler ghost of itself behind.
 */
(function () {
  'use strict';

  const S = ['#c5cf8a', '#98a85c', '#465c26', '#16250f'];     // the four shades, pale to dark
  const W = 195, H = 423;                                     // the panel in dots
  const DOTS_Y = 422.5;                                       // the phone's height in dots
  const states = new WeakMap();

  /* ---- the palette ---- */

  /** A keyframe that points paper and the three inks at shades a, b, c, d and holds them. */
  const shades = (a, b, c, d) => ({ '--p0': S[a], '--p1': S[b], '--p2': S[c], '--p3': S[d], easing: 'step-end' });

  /** Steps the whole panel through a list of palettes, each held for `ms`. With `keep` the last one stays. */
  function palette(ctx, list, ms, keep) {
    const frames = list.map((p, i) => Object.assign(shades(...p), { offset: i / list.length }));
    frames.push(Object.assign(shades(...list[list.length - 1]), { offset: 1 }));
    return ctx.animate(ctx.root, frames, { duration: list.length * ms, fill: keep ? 'forwards' : 'none' });
  }

  /* ---- columns ---- */

  /**
   * The face is not monospaced, and the demo lines its columns up with runs of spaces. Each run of
   * two or more becomes a tab stop: what follows it starts at its column, four dots a column.
   */
  function tabify(node) {
    if (!node || node.querySelector('.dm-tab')) return;
    const gap = [];
    for (const m of node.textContent.matchAll(/ {2,}/g)) for (let i = 0; i < m[0].length; i++) gap[m.index + i] = true;
    // a part that opens with spaces and then a figure is a number set to the right: that is a stop too
    let at = 0;
    for (const kid of node.childNodes) {
      const lead = /^ +(?=\d)/.exec(kid.textContent);
      if (lead && at) for (let i = 0; i < lead[0].length; i++) gap[at + i] = true;
      at += kid.textContent.length;
    }
    if (!gap.length) return;
    const out = [];
    let box = null, start = 0, col = 0, inGap = false;
    const open = () => {
      if (box) box.style.minWidth = (col - start) / 2 + 'em';
      box = TL.util.el('span', 'dm-tab');
      start = col;
      out.push(box);
    };
    open();
    for (const kid of [...node.childNodes]) {
      const cls = kid.nodeType === 1 ? kid.className : '';
      let piece = '';
      const flush = () => { if (piece) box.append(TL.util.el('span', cls, piece)); piece = ''; };
      for (const ch of kid.textContent) {
        if (gap[col]) { flush(); inGap = true; }
        else { if (inGap) { flush(); open(); inGap = false; } piece += ch; }
        col += ch.length;
      }
      flush();
    }
    node.replaceChildren(...out);
  }

  /** The face's percent sign is hard to read. Each one is wrapped so the stylesheet can draw its own. */
  function percents(node) {
    if (!node || !node.textContent.includes('%')) return;
    const walk = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), hits = [];
    for (let t = walk.nextNode(); t; t = walk.nextNode()) {
      if (t.data.includes('%') && !t.parentNode.classList.contains('dm-pct')) hits.push(t);
    }
    for (const t of hits) {
      const parts = t.data.split('%'), frag = document.createDocumentFragment();
      parts.forEach((p, i) => { if (i) frag.append(TL.util.el('span', 'dm-pct', '%')); if (p) frag.append(p); });
      t.replaceWith(frag);
    }
  }

  /* ---- wipes: drawn on a canvas of one pixel a dot, a step at a time ---- */

  /** An iris opening around (cx, cy), in chunks of three dots. Its edge trails two paler bands. */
  function iris(cx, cy) {
    const B = 3, cols = Math.ceil(W / B), rows = Math.ceil(H / B), steps = 9;
    const band = (Math.hypot(Math.max(cx, W - cx), Math.max(cy, H - cy)) + B) / steps;
    return {
      steps: steps + 2, ms: 40,
      paint(g, i) {
        const front = band * (i - 2);
        g.clearRect(0, 0, W, H);
        for (let by = 0; by < rows; by++) {
          const dy = (by + 0.5) * B - cy;
          let run = -1, from = 0;
          for (let bx = 0; bx <= cols; bx++) {
            const lv = bx < cols ? Math.max(-1, Math.min(2, Math.floor((Math.hypot((bx + 0.5) * B - cx, dy) - front) / band))) : -2;
            if (lv === run) continue;
            if (run >= 0) { g.fillStyle = S[run + 1]; g.fillRect(from * B, by * B, (bx - from) * B, B); }
            run = lv; from = bx;
          }
        }
      },
    };
  }

  /**
   * A shutter of twelve horizontal bars. Each closes downwards a slice at a time, its leading edge
   * two paler slices; the bar at `focus` (a row, in dots) goes first and the others follow outwards.
   * Opening is the same film backwards.
   */
  function shutter(focus, closing, quick) {
    const n = 12, bh = Math.ceil(H / n), slices = quick ? 3 : 6, slice = bh / slices;
    const k0 = Math.max(0, Math.min(n - 1, Math.floor(focus / bh)));
    const lag = (k) => (quick ? 0 : Math.round(Math.abs(k - k0) * 0.55));
    const total = slices + 2 + Math.max(lag(0), lag(n - 1));
    const part = (g, y0, a, b, shade) => {
      a = Math.max(0, a); b = Math.min(bh, b);
      if (b > a) { g.fillStyle = S[shade]; g.fillRect(0, y0 + a, W, b - a); }
    };
    return {
      steps: total, ms: 34,
      paint(g, i) {
        const t = closing ? i : total - i;
        g.clearRect(0, 0, W, H);
        for (let k = 0; k < n; k++) {
          const f = Math.max(0, Math.min(slices + 2, t - lag(k))) * slice;
          part(g, k * bh, 0, f - 2 * slice, 3);
          part(g, k * bh, f - 2 * slice, f - slice, 2);
          part(g, k * bh, f - slice, f, 1);
        }
      },
    };
  }

  /** The dark line an LCD holds across its middle for a moment after the picture has gone. */
  function lastLine() {
    return {
      steps: 5, ms: 130,
      paint(g, i) {
        const shade = [0, 2, 3, 2, 1, 0][i];
        g.clearRect(0, 0, W, H);
        if (shade) { g.fillStyle = S[shade]; g.fillRect(0, 186, W, 1); }
      },
    };
  }

  function start(st, job) { job.at = -1; job.t0 = null; st.job = job; }

  /** A marker the engine sweeps away when it clears a transition. While it is there the wipe is wanted. */
  function guard(ctx, st) {
    if (st.guard) st.guard.remove();
    st.guard = ctx.el('i');
    ctx.fx.append(st.guard);
  }

  function tick(ctx, st, ms) {
    if (st.guard && !st.guard.isConnected) {
      // The engine has cleared the transition: what waited for that starts now, anything else is over.
      const next = st.after;
      st.guard = st.after = st.job = null;
      st.g.clearRect(0, 0, W, H);
      if (next && ctx.root.dataset.scene === 'home') { start(st, next); next.paint(st.g, 0); }
    }
    const job = st.job;
    if (!job) return;
    if (job.t0 == null) job.t0 = ms;
    const i = Math.min(job.steps, Math.floor((ms - job.t0) / job.ms));
    if (i !== job.at) { job.at = i; job.paint(st.g, i); }
    if (i >= job.steps) st.job = null;
  }

  /** A ghost of `node` where it stood a moment ago, `dy` em away, for about a frame of this screen. */
  function ghost(ctx, node, dy, clip) {
    const f = { filter: 'drop-shadow(0 ' + dy + 'em 0 ' + S[1] + ')' };
    if (clip) f.clipPath = 'inset(0)';
    ctx.animate(node, [f, f], { duration: 90 }).catch(() => {});
  }

  const dotsAt = (r) => ({ x: ((r.x + r.w / 2) / 100) * W, y: ((r.y + r.h / 2) / 100) * DOTS_Y });

  TL.register({
    id: 'dotmatrix',
    name: 'Dot Matrix',
    tagline: 'Four shades of olive on a handheld LCD',
    mood: 'A pocket screen from 1990: chunky dots on pea-green glass, calm to read and slow enough to watch.',
    motion: [
      'Contrast climbs or drains through the four shades in hard steps',
      'Unlock flashes the palette, then a blocky iris opens on the summary',
      'A shutter of bars shuts over a launch; moving things leave ghosts',
    ],
    palette: { bg: S[0], fg: S[3], dim: S[2], accent: S[3], app: S[3], ok: S[3], err: S[3] },
    light: true,
    fonts: ['Tiny5'],
    hooks: {
      mount(ctx) {
        ctx.bg.append(ctx.el('div', 'dm-sheen'));
        const lcd = ctx.el('div', 'dm-lcd');
        lcd.append(ctx.el('div', 'dm-grid'), ctx.el('div', 'dm-shade'));
        const st = { lcd, g: null, job: null, guard: null, after: null };
        states.set(ctx, st);
        if (!ctx.still) {
          const cv = ctx.el('canvas', 'dm-wipe');
          cv.width = W; cv.height = H;
          st.g = cv.getContext('2d');
          ctx.fx.append(cv);
          ctx.onFrame((ms) => tick(ctx, st, ms));
        }
        ctx.fx.append(lcd);

        const fix = (node) => {
          if (node.nodeType !== 1) return;
          if (node.classList.contains('tl-text')) tabify(node);
          else if (node.classList.contains('tl-gauge')) tabify(node.querySelector('.tl-gauge-note'));
          percents(node);
        };
        percents(ctx.status);
        const watch = new MutationObserver((list) => { for (const m of list) m.addedNodes.forEach(fix); });
        watch.observe(ctx.lines, { childList: true });
        return () => watch.disconnect();
      },

      /* The contrast comes up through the shades: blank, pale, mid, full. */
      powerOn: (ctx) => palette(ctx, [[0, 0, 0, 0], [0, 0, 0, 1], [0, 0, 1, 2]], 140),

      line(ctx, node, d) {
        if (d.t === 'note') return;
        const em = parseFloat(getComputedStyle(ctx.lines).fontSize) || 16;
        if (node.nextElementSibling) {
          // the boot log prints above the lock header, which steps down a line and leaves itself behind
          const dy = -node.offsetHeight / em;
          for (let n = node.nextElementSibling; n; n = n.nextElementSibling) ghost(ctx, n, dy);
        } else {
          const dy = ctx.lines.scrollHeight - ctx.lines.clientHeight - ctx.lines.scrollTop;   // about to scroll this far
          if (dy > 0) ghost(ctx, ctx.lines, dy / em, true);
        }
      },

      key(ctx) {
        if (ctx.prompt.dataset.kind !== 'shell') return;
        // where the cursor stood a moment ago is still fading
        const input = ctx.prompt.querySelector('.tl-input');
        const f = { background: 'linear-gradient(' + S[1] + ',' + S[1] + ') right 0 top 0.125em / 0.5em 0.875em no-repeat' };
        ctx.animate(input, [f, f], { duration: 80 }).catch(() => {});
      },

      async granted(ctx) {
        const st = states.get(ctx);
        const word = ctx.say('access granted', 'ok');
        word.dataset.role = 'granted';
        await ctx.sleep(320);
        const at = dotsAt(ctx.rect(ctx.lines.querySelector('.tl-clock-grid') || word));
        // the picture inverts twice, then its light parts sink into the dark
        await palette(ctx, [[3, 2, 1, 0], [0, 1, 2, 3], [3, 2, 1, 0], [3, 3, 2, 1], [3, 3, 3, 2], [3, 3, 3, 3]], 60, true);
        st.g.fillStyle = S[3];
        st.g.fillRect(0, 0, W, H);
        guard(ctx, st);
        st.after = iris(at.x, at.y);          // it opens once the engine has put the summary on the page
      },

      async notify(ctx, node) {
        const a = ctx.rect(ctx.lines), b = ctx.rect(node);
        const jumps = 8;
        const hop = Math.max(4, Math.round((((b.y - a.y) / 100) * DOTS_Y + 16) / jumps));     // dots a jump
        const trail = { boxShadow: '0 ' + -hop / 8 + 'em 0 0 ' + S[2] + ', 0 ' + (-hop * 2) / 8 + 'em 0 0 ' + S[1] };
        await ctx.animate(node, [
          Object.assign({ transform: 'translateY(' + (-hop * jumps) / 8 + 'em)' }, trail),
          Object.assign({ transform: 'translateY(0)' }, trail),
        ], { duration: jumps * 46, easing: 'steps(' + jumps + ', end)' });
        const off = { '--p3': S[0], '--p0': S[3], easing: 'step-end' }, on = { '--p3': S[3], '--p0': S[0], easing: 'step-end' };
        await ctx.animate(node, [
          Object.assign({ offset: 0 }, on), Object.assign({ offset: 0.2 }, off), Object.assign({ offset: 0.4 }, on),
          Object.assign({ offset: 0.6 }, off), Object.assign({ offset: 0.8 }, on), Object.assign({ offset: 1 }, on),
        ], { duration: 520 });
      },

      async launch(ctx, line) {
        const st = states.get(ctx);
        await ctx.sleep(220);
        const job = shutter(dotsAt(ctx.rect(line)).y, true);
        guard(ctx, st);
        start(st, job);
        await ctx.sleep(job.steps * job.ms + 80);
      },

      async back(ctx) {
        const st = states.get(ctx);
        const lines = ctx.lines.querySelectorAll('.tl-launch'), line = lines[lines.length - 1];
        const row = line ? dotsAt(ctx.rect(line)).y : DOTS_Y / 2;
        // the panel is ours again: its grid comes back, the shutter snaps shut over the app and then opens
        ctx.animate(st.lcd, [{ opacity: 1 }, { opacity: 1 }], { duration: 1, fill: 'forwards' }).catch(() => {});
        guard(ctx, st);
        const shut = shutter(row, true, true);
        start(st, shut);
        await ctx.sleep(shut.steps * shut.ms + 60);
        ctx.appview.style.visibility = 'hidden';
        const open = shutter(row, false);
        start(st, open);
        await ctx.sleep(open.steps * open.ms + 60);
      },

      /* The picture drains away through the shades to the palest, and one line lingers. */
      async powerOff(ctx) {
        const st = states.get(ctx);
        guard(ctx, st);
        start(st, lastLine());
        await palette(ctx, [[0, 0, 1, 2], [0, 0, 0, 1], [0, 0, 0, 0]], 130, true);
        await ctx.sleep(330);
      },
    },
  });
})();
