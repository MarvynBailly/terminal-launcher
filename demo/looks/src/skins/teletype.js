/*
 * Teletype: a teleprinter typing onto a continuous roll of paper.
 *
 * The page hangs from the bottom of the screen: a line is struck where the prompt is and the paper
 * feeds it upward. Two things a stylesheet cannot do are done from `mount`, so that they hold in a
 * still as well: every character is wrapped as its own strike (uneven ink), and every time the page
 * grows the paper is fed by exactly that much.
 */
(function () {
  'use strict';

  const P = 5.19;             // one line of paper in cqw: 1.5em, and the pitch of the tractor holes
  const T = P * 8;            // the paper's grain repeats every eight lines
  const AWAY = T * 5;         // a full page of feed that lands the holes and the grain where they were
  const COLS = 41;
  const SVG = 'http://www.w3.org/2000/svg';

  const noop = () => {};
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const phones = new WeakMap();

  function hash(text) {
    let h = 2166136261;
    for (let i = 0; i < text.length; i++) { h ^= text.charCodeAt(i); h = Math.imul(h, 16777619); }
    return h >>> 0;
  }
  function rng(a) {
    return function () {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /** The stamp's face is first needed in the middle of a transition: ask for it ahead of time. */
  let faced = false;
  function face() {
    if (faced || !document.fonts || !document.fonts.load) return;
    document.fonts.load('400 1em "Special Elite"').then((got) => { if (got.length) faced = true; }, noop);
  }

  /** A ragged edge across the sheet at `y` percent of its height: points from left to right. */
  function ragged(y, amp, seed) {
    const r = rng(seed), pts = [];
    for (let x = 0; x < 100; x += 1.4 + r() * 3.4) pts.push([x, y + (r() - 0.5) * amp * (r() < 0.22 ? 2.4 : 1)]);
    pts.push([100, y + (r() - 0.5) * amp]);
    return pts;
  }
  const polygon = (pts) => 'polygon(' + pts.map((p) => p[0].toFixed(2) + '% ' + p[1].toFixed(2) + '%').join(',') + ')';

  /* ---- ink: each character is its own strike, the same every time for the same line ---- */

  function ink(line, el) {
    if (line.classList.contains('tl-clock')) return;
    const rand = rng(hash(line.textContent || ''));
    const walk = document.createTreeWalker(line, NodeFilter.SHOW_TEXT);
    const texts = [];
    while (walk.nextNode()) texts.push(walk.currentNode);
    let n = 0;
    for (const text of texts) {
      const host = text.parentNode;
      if (host.classList.contains('tl-app')) n = 0;
      if (host.classList.contains('tl-gauge-note')) n = 21;
      const out = document.createDocumentFragment();
      let gap = '';
      for (const ch of text.data) {
        if (ch === ' ') { gap += ch; n++; continue; }
        if (gap) { out.append(gap); gap = ''; }
        const r = rand();
        const strike = el('span', r < 0.07 ? 'c h' : r < 0.17 ? 'c l' : r < 0.25 ? 'c u' : r < 0.33 ? 'c d' : r < 0.37 ? 'c r' : 'c', ch);
        strike.style.setProperty('--n', n++);
        out.append(strike);
      }
      if (gap) out.append(gap);
      text.replaceWith(out);
    }
  }

  /* ---- the paper feed ---- */

  /** How far the page still is from where it belongs, in cqw, while a feed is running. */
  function behind(s, now) {
    const p = s.track;
    if (!p || now >= p[p.length - 1].t) return 0;
    for (let i = 1; i < p.length; i++) {
      if (now <= p[i].t) {
        const a = p[i - 1], b = p[i];
        return b.t === a.t ? b.y : a.y + ((b.y - a.y) * (now - a.t)) / (b.t - a.t);
      }
    }
    return 0;
  }

  /**
   * The page has grown: what was on it is already further up. Put it back where it was and feed it
   * up, a step at a time. steps: [{ d: cqw, dwell: ms at rest first, move: ms }].
   */
  function feed(ctx, s, steps) {
    const total = steps.reduce((sum, st) => sum + st.d, 0);
    s.off = (((s.off - total) % T) + T) % T;
    s.sheet.style.transform = 'translateY(' + s.off.toFixed(3) + 'cqw)';
    if (ctx.still || ctx.fast) return;
    const now = performance.now();
    let y = behind(s, now) + total, t = 0, left = total;
    const pts = [{ t: 0, y }];
    for (const st of steps) {
      if (st.dwell) { t += st.dwell; pts.push({ t, y }); }
      left -= st.d;
      y = Math.max(0, left);
      t += st.move;
      pts.push({ t, y });
    }
    s.track = pts.map((p) => ({ t: now + p.t, y: p.y }));
    const at = (p) => (p.t / t);
    const opts = { duration: t, easing: 'linear' };
    ctx.animate(s.body, pts.map((p) => ({ offset: at(p), transform: 'translateY(' + p.y.toFixed(3) + 'cqw)' })), opts).catch(noop);
    ctx.animate(s.sheet, pts.map((p) => ({ offset: at(p), transform: 'translateY(' + (s.off + p.y).toFixed(3) + 'cqw)' })), opts).catch(noop);
  }

  /** The carriage takes a knock: a key, a pin, a stamp. */
  function thump(ctx, s, k, ms) {
    const o = s.off;
    const opts = { duration: ms || 90, easing: 'cubic-bezier(.2,.8,.3,1)' };
    ctx.animate(s.body, [{ transform: 'translate(' + (-0.14 * k) + 'cqw,' + (0.34 * k) + 'cqw)' }, { transform: 'translate(0,0)' }], opts).catch(noop);
    ctx.animate(s.sheet, [{ transform: 'translate(' + (-0.14 * k) + 'cqw,' + (o + 0.34 * k).toFixed(3) + 'cqw)' }, { transform: 'translate(0,' + o.toFixed(3) + 'cqw)' }], opts).catch(noop);
  }

  /** The platen turned by hand: `d` cqw of travel in `n` clicks. */
  function ratchet(d, n, at) {
    const frames = [];
    for (let i = 0; i <= n; i++) frames.push({ offset: i / n, transform: 'translateY(' + (at + d * (1 - i / n)).toFixed(3) + 'cqw)', easing: 'cubic-bezier(.7,0,.25,1)' });
    return frames;
  }

  TL.register({
    id: 'teletype',
    name: 'Teletype',
    tagline: 'A teleprinter on a roll of paper',
    mood: 'Warm paper under a lamp, every line struck in typewriter ink at the bottom and fed upward past the tractor holes.',
    motion: [
      'The lamp flickers on and the platen ratchets the paper up',
      'Lines are struck key by key while the paper feeds a line',
      'A red stamp thumps down, a launch tears the sheet away',
    ],
    palette: { bg: '#efe7d5', fg: '#25211d', dim: '#5f584c', accent: '#b1271f', app: '#2a3d63', ok: '#2c6249', err: '#c21d16' },
    light: true,
    fonts: ['Courier+Prime:wght@400;700', 'Special+Elite'],
    hooks: {
      mount(ctx) {
        const paper = ctx.el('div', 'tt-paper');
        const sheet = ctx.el('div', 'tt-sheet');
        paper.append(sheet, ctx.el('div', 'tt-end'));
        ctx.bg.append(ctx.el('div', 'tt-platen'), paper);
        const s = { paper, sheet, body: ctx.lines.parentNode, off: 0, h: 0, track: null };
        phones.set(ctx, s);
        const lines = ctx.lines, root = ctx.root, prompt = ctx.prompt;

        const grew = new MutationObserver((records) => {
          const fresh = [];
          let middle = false;
          for (const r of records) {
            for (const node of r.addedNodes) {
              if (node.nodeType !== 1) continue;
              ink(node, ctx.el);
              if (!node.isConnected) continue;
              if (r.nextSibling) middle = true; else fresh.push(node);
            }
          }
          const h = lines.scrollHeight;
          const was = lines.children.length === fresh.length ? 0 : s.h;      // a page begun afresh starts from nothing
          s.h = lines.firstChild ? h : 0;
          const grown = h - was;
          const scene = root.dataset.scene;
          if (!fresh.length || middle || grown < 0.5) return;
          if (scene !== 'lock' && scene !== 'home' && scene !== 'launch') return;
          const cq = 100 / (root.clientWidth || 1);
          const grid = fresh.find((node) => node.classList.contains('tl-grid'));
          const steps = [];
          if (grid) {
            // the command's own line goes up at once; then each row is struck at the print line and fed
            const rows = Math.max(1, +grid.style.getPropertyValue('--rows') || 6);
            const tall = Math.min(grown, grid.getBoundingClientRect().height);
            if (grown - tall > 0.5) steps.push({ d: (grown - tall) * cq, dwell: 0, move: 70 });
            for (let i = 0; i < rows; i++) steps.push({ d: (tall / rows) * cq, dwell: 95, move: 40 });
          } else {
            const d = grown * cq;
            steps.push({ d, dwell: 0, move: clamp(58 + (d - P) * 7, 58, 170) });
          }
          feed(ctx, s, steps);
        });
        grew.observe(lines, { childList: true });

        // the pointer on the scale follows the column the next key will land in
        const column = () => root.style.setProperty('--tt-col', prompt.hidden ? 0 : Math.min(COLS, prompt.textContent.length));
        const typed = new MutationObserver(column);
        typed.observe(prompt, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['hidden'] });
        column();

        face();

        return () => { grew.disconnect(); typed.disconnect(); };
      },

      /* The clock as typewriter art: the engine's own cells, over one shape of soaked-in ink. */
      clock(ctx, node, time) {
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        const grid = ctx.el('span', 'tl-clock-grid');
        const rand = rng(hash(time));
        const bleed = () => (0.01 + rand() * 0.045).toFixed(3);
        let d = '';
        TL.util.clockCells(time).forEach((on, i) => {
          const cell = ctx.el('i', on ? 'on' : ''), x = i % 17, y = (i / 17) | 0;
          cell.style.setProperty('--i', i);
          cell.style.setProperty('--col', x);
          cell.style.setProperty('--row', y);
          grid.append(cell);
          if (on) d += 'M' + (x - bleed()) + ' ' + (y - bleed()) + 'H' + (x + 1 + +bleed()) + 'V' + (y + 1 + +bleed()) + 'H' + (x - bleed()) + 'z';
        });
        const svg = document.createElementNS(SVG, 'svg'), path = document.createElementNS(SVG, 'path');
        svg.setAttribute('class', 'tt-soak');
        svg.setAttribute('viewBox', '0 0 17 5');
        svg.setAttribute('preserveAspectRatio', 'none');
        path.setAttribute('d', d);
        svg.append(path);
        grid.prepend(svg);
        node.append(grid);
      },

      /* The lamp flickers on over the machine and the platen is turned until the page is up. */
      async powerOn(ctx) {
        const s = phones.get(ctx);
        face();
        const dark = ctx.el('div', 'tt-dark'), glow = ctx.el('div', 'tt-glow'), black = ctx.el('div', 'tt-black');
        ctx.fx.append(dark, glow, black);
        const roll = { duration: 700, delay: 300, fill: 'backwards' };
        await Promise.all([
          ctx.animate(black, [{ opacity: 1 }, { opacity: 0 }], { duration: 110, fill: 'forwards' }),
          ctx.animate(glow, [
            { opacity: 0 }, { opacity: 0, offset: 0.12 }, { opacity: 0.7, offset: 0.17 }, { opacity: 0.05, offset: 0.25 },
            { opacity: 0.8, offset: 0.4 }, { opacity: 0.5, offset: 0.5 }, { opacity: 0 },
          ], { duration: 760, fill: 'forwards' }),
          ctx.animate(dark, [
            { opacity: 1 }, { opacity: 1, offset: 0.12 }, { opacity: 0.42, offset: 0.17 }, { opacity: 0.9, offset: 0.25 },
            { opacity: 0.25, offset: 0.4 }, { opacity: 0.36, offset: 0.5 }, { opacity: 0 },
          ], { duration: 640, fill: 'forwards' }),
          ctx.animate(s.body, ratchet(62, 9, 0), roll),
          ctx.animate(s.sheet, ratchet(62, 9, s.off), roll),
        ]);
        await ctx.sleep(140);
      },

      key(ctx) {
        const s = phones.get(ctx);
        face();
        thump(ctx, s, 1);
        const cursor = ctx.prompt.querySelector('.tl-cursor');
        if (cursor) ctx.animate(cursor, [
          { transform: 'translateX(-1ch) scale(1.25, 1.12)', opacity: 1 },
          { transform: 'translateX(-1ch) scale(1.25, 1.12)', opacity: 1, offset: 0.3 },
          { transform: 'none', opacity: 1 },
        ], { duration: 85, easing: 'cubic-bezier(.3,0,.2,1)' }).catch(noop);
      },

      /* The stamp comes down across the boot log, lifts off its ink, and the page is fed away. */
      async granted(ctx) {
        const s = phones.get(ctx);
        const first = ctx.lines.firstElementChild, title = ctx.lines.querySelector('[data-role="lock-title"]');
        const y = first && title ? Math.max(24, (ctx.rect(first).y + ctx.rect(title).y) / 2) : 52;
        const carry = ctx.el('div', 'tt-carry');
        const stamp = ctx.el('div', 'tt-stamp');
        const inked = ctx.el('span', 'tt-stamp-ink', 'access granted');
        const die = ctx.el('i', 'tt-stamp-die');
        const mist = ctx.el('i', 'tt-stamp-mist');
        stamp.append(mist, inked, die);
        carry.append(stamp);
        carry.style.top = y.toFixed(2) + '%';
        ctx.fx.append(carry);

        const at = 'translate(-50%, -50%) ';
        const hit = 0.3, ms = 640;
        const once = { duration: ms, fill: 'both' };
        ctx.animate(stamp, [
          { transform: at + 'rotate(-14deg) scale(1.9)', easing: 'cubic-bezier(.6,0,.9,.5)' },
          { transform: at + 'rotate(-7deg) scale(0.95)', offset: hit, easing: 'ease-out' },
          { transform: at + 'rotate(-7deg) scale(1.015)', offset: hit + 0.12 },
          { transform: at + 'rotate(-7deg) scale(1)', offset: hit + 0.26 },
          { transform: at + 'rotate(-7deg) scale(1)' },
        ], once).catch(noop);
        ctx.animate(die, [
          { opacity: 0, transform: 'none' },
          { opacity: 0.97, transform: 'none', offset: hit * 0.7 },
          { opacity: 0.97, transform: 'none', offset: hit + 0.16, easing: 'cubic-bezier(.3,0,.6,1)' },
          { opacity: 0, transform: 'translate(1.5cqw, -5cqw) scale(1.22)', offset: hit + 0.52 },
          { opacity: 0, transform: 'translate(1.5cqw, -5cqw) scale(1.22)' },
        ], once).catch(noop);
        ctx.animate(inked, [
          { opacity: 0 }, { opacity: 0, offset: hit }, { opacity: 0.9, offset: hit + 0.01 }, { opacity: 0.9 },
        ], once).catch(noop);
        // the ink spreads into the paper as the rubber lifts
        ctx.animate(mist, [
          { opacity: 0, transform: 'scale(0.7)' }, { opacity: 0, transform: 'scale(0.7)', offset: hit },
          { opacity: 0.9, transform: 'scale(0.86)', offset: hit + 0.1 }, { opacity: 0, transform: 'scale(1.12)' },
        ], once).catch(noop);
        await ctx.sleep(ms * hit);
        thump(ctx, s, 3.4, 190);
        ctx.animate(carry, [{ transform: 'translate(-0.5cqw, 1.2cqw)' }, { transform: 'none' }], { duration: 190, easing: 'cubic-bezier(.2,.8,.3,1)' }).catch(noop);
        await ctx.sleep(ms * (1 - hit) + 170);

        const up = { duration: 500, easing: 'cubic-bezier(.6,0,.75,.45)', fill: 'forwards' };
        await Promise.all([
          ctx.animate(s.body, [{ transform: 'translateY(0)' }, { transform: 'translateY(' + (-AWAY) + 'cqw)' }], up),
          ctx.animate(s.sheet, [{ transform: 'translateY(' + s.off + 'cqw)' }, { transform: 'translateY(' + (s.off - AWAY) + 'cqw)' }], up),
          ctx.animate(carry, [{ transform: 'translateY(0)' }, { transform: 'translateY(' + (-AWAY) + 'cqw)' }], up),
        ]);
        await ctx.sleep(70);
      },

      /* The slip drops onto the page (the stylesheet does that) and the pin goes in with a knock. */
      async notify(ctx) {
        const s = phones.get(ctx);
        await ctx.sleep(520);
        thump(ctx, s, 1.8, 140);
        await ctx.sleep(160);
      },

      /* The sheet is torn off against the tear bar, from the left, and whisked away. */
      async launch(ctx) {
        const s = phones.get(ctx);
        await ctx.sleep(300);
        const b = ctx.rect(s.body);
        const y = b.y + b.h;
        const edge = ragged(y - 0.75, 0.8, 23);
        const upper = polygon([[0, -20], [100, -20]].concat(edge.slice().reverse()));
        const lower = polygon(edge.concat([[100, 120], [0, 120]]));
        const stub = s.paper.cloneNode(true);
        stub.style.clipPath = lower;
        ctx.bg.append(stub);

        const pose = [
          { offset: 0, transform: 'translate(0cqw, 0cqh) rotate(0deg)' },
          { offset: 0.16, transform: 'translate(0cqw, -0.25cqh) rotate(0deg)', easing: 'cubic-bezier(.5,0,.6,1)' },
          { offset: 0.46, transform: 'translate(0cqw, -0.4cqh) rotate(3.2deg)', easing: 'cubic-bezier(.6,0,.9,.55)' },
          { offset: 1, transform: 'translate(34cqw, -122cqh) rotate(15deg)' },
        ];
        const opts = { duration: 660, fill: 'forwards' };
        const fade = ctx.el('div', 'tt-fade');
        ctx.fx.append(fade);
        await Promise.all([
          ctx.animate(s.paper, pose.map((k) => Object.assign({ clipPath: upper, transformOrigin: '100% ' + y.toFixed(2) + '%' }, k)), opts),
          ctx.animate(s.body, pose.map((k) => Object.assign({ transformOrigin: (((100 - b.x) / b.w) * 100).toFixed(2) + '% 100%' }, k)), opts),
          ctx.animate(fade, [{ opacity: 0 }, { opacity: 0, offset: 0.7 }, { opacity: 1 }], { duration: 780, fill: 'both' }),
        ]);
      },

      /* The app lets go and the paper is fed back up from the platen, torn edge first. */
      async back(ctx) {
        const s = phones.get(ctx);
        const top = polygon(ragged(0.6, 0.8, 23).concat([[100, 120], [0, 120]]));
        const opts = { duration: 520, delay: 150, easing: 'cubic-bezier(.2,.75,.25,1)', fill: 'backwards' };
        await Promise.all([
          ctx.animate(ctx.appview, [{ opacity: 1 }, { opacity: 0 }], { duration: 230, fill: 'forwards' }),
          ctx.animate(s.paper, [{ transform: 'translateY(92cqh)', clipPath: top }, { transform: 'translateY(0cqh)', clipPath: top }], opts),
          ctx.animate(s.body, [{ transform: 'translateY(92cqh)' }, { transform: 'translateY(0cqh)' }], opts),
        ]);
      },

      /* The paper feeds out past its end, and the lamp goes off. */
      async powerOff(ctx) {
        const s = phones.get(ctx);
        await ctx.sleep(140);
        const out = { duration: 620, easing: 'cubic-bezier(.5,0,.8,.5)', fill: 'forwards' };
        await Promise.all([
          ctx.animate(s.paper, [{ transform: 'translateY(0cqh)', clipPath: 'inset(-20% 0 0 0)' }, { transform: 'translateY(-106cqh)', clipPath: 'inset(-20% 0 0 0)' }], out),
          ctx.animate(s.body, [{ transform: 'translateY(0cqh)' }, { transform: 'translateY(-106cqh)' }], out),
        ]);
        await ctx.sleep(70);
        const dark = ctx.el('div', 'tt-dark');
        ctx.fx.append(dark);
        await ctx.animate(dark, [
          { opacity: 0 }, { opacity: 0.7, offset: 0.16 }, { opacity: 0.45, offset: 0.3 }, { opacity: 0.86, offset: 0.55 }, { opacity: 1 },
        ], { duration: 400, fill: 'forwards' });
        const black = ctx.el('div', 'tt-black');
        ctx.fx.append(black);
        await ctx.animate(black, [{ opacity: 0 }, { opacity: 1 }], { duration: 150, fill: 'forwards' });
        await ctx.sleep(60);
      },
    },
  });
})();
