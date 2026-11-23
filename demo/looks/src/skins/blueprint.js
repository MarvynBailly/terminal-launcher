/*
 * Blueprint: an engineer's cyanotype. The sheet, its border and the clock are linework, and every
 * transition is something a plotter or a draughtsman does to a drawing.
 */
(function () {
  'use strict';
  const SVG = 'http://www.w3.org/2000/svg';
  const DRAW = 'cubic-bezier(.45,0,.15,1)';            // a pen that starts and stops without a jerk
  const FX = 3, FY = 3.05, COLS = 25, ROWS = 56;       // the border's inset in cqw, and the grid cells inside it
  const FW = 94, FH = 210.567;                         // so the border is this big, in cqw
  const TALL = 216.667;                                // the phone's height in cqw

  /* The numerals as the path of one fat pen, in a box 3 wide and 5 high. l and r: where the ink ends. */
  const NUM = {
    0: { d: 'M.4 1.5a1.1 1.1 0 0 1 2.2 0v2a1.1 1.1 0 0 1-2.2 0z' },
    1: { d: 'M.9 1.4l1-1v4.2', l: 0.5, r: 2.3 },
    2: { d: 'M.4 1.5a1.1 1.1 0 0 1 2.2 0c0 .9-2.2 1.9-2.2 3.1h2.2' },
    3: { d: 'M.4 .4h2.2l-1.3 1.7a1.25 1.25 0 1 1-.9 2.1' },
    4: { d: 'M2 4.6v-4.2l-1.6 2.9h2.2' },
    5: { d: 'M2.5 .4h-1.9l-.2 1.8a1.3 1.3 0 1 1 0 2.1' },
    6: { d: 'M2.3 .4c-1.1.3-1.9 1.5-1.9 3.1a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0-2.2 0' },
    7: { d: 'M.4 .4h2.2l-1.5 4.2' },
    8: { d: 'M1.5 2.4a1 1 0 1 1 0-2a1 1 0 1 1 0 2a1.1 1.1 0 1 1 0 2.2a1.1 1.1 0 1 1 0-2.2' },
    9: { d: 'M.7 4.6c1.1-.3 1.9-1.5 1.9-3.1a1.1 1.1 0 1 0-2.2 0a1.1 1.1 0 1 0 2.2 0' },
    ':': { d: 'M.5 1.6v.01M.5 3.4v.01', w: 1 },
  };
  let serial = 0;

  function svg(tag, attrs, parent) {
    const node = document.createElementNS(SVG, tag);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    if (parent) parent.append(node);
    return node;
  }
  /* An animation nobody waits for: from an offset state to rest, unless the options say otherwise. */
  function fire(c, node, keyframes, options) {
    if (node) c.animate(node, keyframes, Object.assign({ easing: 'linear', fill: 'backwards' }, options)).catch(() => {});
  }
  /* The same for something that only exists while it moves. */
  function pass(c, node, keyframes, options) {
    const lift = () => node.remove();
    c.animate(node, keyframes, options).then(lift, lift);
  }
  const fade = (from, to) => [{ opacity: from }, { opacity: to }];
  const stroke = (from, to) => [{ strokeDasharray: '1 2', strokeDashoffset: from }, { strokeDasharray: '1 2', strokeDashoffset: to }];
  const wipe = [{ clipPath: 'inset(-0.3em 100% -0.3em -1em)' }, { clipPath: 'inset(-0.3em 0% -0.3em -1em)' }];

  /*
   * The grid inside the border, a zone tick outside it at every heavy line and a cross where heavy
   * lines meet. On a canvas so that every line lands on whole pixels at any size and any scaling;
   * a line thinner than a pixel is drawn one pixel wide and fainter.
   */
  function drawGrid(c, canvas) {
    const box = c.root.getBoundingClientRect();
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    const w = Math.round(box.width * dpr), h = Math.round(box.height * dpr);
    if (w < 8 || h < 8) return;
    canvas.width = w; canvas.height = h;
    const g = canvas.getContext('2d');
    const u = w / 100, pen = 0.25 * u, lw = Math.max(1, Math.round(pen)), faint = Math.min(1, pen);
    const x0 = Math.round(FX * u), x1 = w - x0, y0 = Math.round(FY * u), y1 = h - y0;
    const xs = [], ys = [];
    for (let i = 0; i <= COLS; i++) xs.push(Math.round(x0 + ((x1 - x0) * i) / COLS));
    for (let j = 0; j <= ROWS; j++) ys.push(Math.round(y0 + ((y1 - y0) * j) / ROWS));
    const ink = (a, x, y, ww, hh) => { g.fillStyle = `rgba(190,220,255,${(a * faint).toFixed(3)})`; g.fillRect(x, y, ww, hh); };
    const tick = Math.round(1.1 * u), arm = Math.round(0.9 * u);
    for (let i = 1; i < COLS; i++) ink(i % 5 ? 0.095 : 0.2, xs[i], y0, lw, y1 - y0);
    for (let j = 1; j < ROWS; j++) ink(j % 5 ? 0.095 : 0.2, x0, ys[j], x1 - x0, lw);
    for (let i = 5; i < COLS; i += 5) {
      ink(0.6, xs[i], y0 - tick, lw, tick); ink(0.6, xs[i], y1, lw, tick);
      for (let j = 5; j < ROWS; j += 5) { ink(0.3, xs[i] - arm, ys[j], 2 * arm + lw, lw); ink(0.3, xs[i], ys[j] - arm, lw, 2 * arm + lw); }
    }
    for (let j = 5; j < ROWS; j += 5) { ink(0.6, x0 - tick, ys[j], tick, lw); ink(0.6, x1, ys[j], tick, lw); }
  }

  /* A drawing sheet: paper, grid, border and a registration mark on each corner. `like` is a sheet to copy the grid from. */
  function makeSheet(c, like) {
    const sheet = c.el('div', 'bp-sheet');
    const grid = c.el('canvas', 'bp-grid');
    const frame = c.el('div', 'bp-frame');
    for (const k of 'trbl') frame.append(c.el('i', 'bp-edge bp-e' + k));
    for (let i = 0; i < 4; i++) frame.append(c.el('i', 'bp-reg bp-r' + i));
    sheet.append(c.el('i', 'bp-paper'), grid, frame);
    const from = like && like.querySelector('canvas');
    if (from && from.width) {
      grid.width = from.width; grid.height = from.height;
      grid.getContext('2d').drawImage(from, 0, 0);
    }
    return sheet;
  }
  const sheetOf = (c) => c.bg.querySelector('.bp-sheet');

  /*
   * The border, drawn clockwise from the top left corner by one pen in `ms`, starting `t0` ms from now.
   * With `undo` the same pen goes back the way it came and takes the border with it.
   */
  function border(c, t0, ms, undo) {
    const sheet = sheetOf(c);
    const round = 2 * (FW + FH);
    const fill = undo ? 'both' : 'backwards', direction = undo ? 'reverse' : 'normal';
    let gone = 0;
    [['t', 'X', FW], ['r', 'Y', FH], ['b', 'X', FW], ['l', 'Y', FH]].forEach(([k, axis, len], i) => {
      const d = (ms * len) / round;
      fire(c, sheet.querySelector('.bp-e' + k), [{ transform: `scale${axis}(0)` }, { transform: `scale${axis}(1)` }],
        { duration: d, delay: undo ? t0 + ms - gone - d : t0 + gone, fill, direction });
      // the mark on the corner this side starts from
      fire(c, sheet.querySelector('.bp-r' + i), [{ transform: 'scale(0)', opacity: 0 }, { transform: 'scale(1)', opacity: 1 }],
        { duration: 200, delay: undo ? Math.max(0, t0 + ms - gone - 200) : t0 + gone, easing: 'ease-out', fill, direction });
      gone += d;
    });
    const nib = c.el('i', 'bp-nib');
    c.fx.append(nib);
    const at = (x, y, offset) => ({ transform: `translate(${x}cqw, ${y}cqw)`, opacity: 1, offset });
    pass(c, nib, [at(0, 0, 0), at(FW, 0, FW / round), at(FW, FH, 0.5), at(0, FH, 0.5 + FW / round), at(0, 0, 1)],
      { duration: ms, delay: t0, easing: 'linear', direction });
  }

  /* How wide a line's lettering is, in em. The stylesheet plots a line from --w and --dur: one pen speed for all. */
  function measure(node) {
    const size = parseFloat(getComputedStyle(node).fontSize) || 14;
    const left = node.getBoundingClientRect().left;
    let right = left;
    for (const part of node.children) right = Math.max(right, part.getBoundingClientRect().right);
    const em = +((right - left) / size).toFixed(2);
    node.style.setProperty('--w', em + 'em');
    node.style.setProperty('--dur', Math.round(150 + em * 15) + 'ms');
    return em;
  }
  /* A line that is already on the page, plotted `delay` ms from now as the lines the engine prints are. */
  function plot(node, delay) {
    if (!node) return;
    measure(node);
    node.style.setProperty('--dl', delay + 'ms');
    node.classList.add('tl-in');
  }

  /* The detail circle of a launch: where it sits, and how far the view must zoom for it to be all there is. */
  function detail(c, line) {
    const r = c.rect(line.querySelector('.tl-launch-text') || line);
    const cx = r.x + r.w / 2, cy = r.y + r.h / 2;
    const radius = Math.max(12, r.w / 2 + 3);
    const y = (cy * TALL) / 100;
    const far = Math.max(...[[0, 0], [100, 0], [0, TALL], [100, TALL]].map(([px, py]) => Math.hypot(px - cx, py - y)));
    const layer = c.el('div', 'bp-detail');
    const pic = svg('svg', {}, layer);
    const disc = svg('circle', { class: 'bp-disc', cx: cx + '%', cy: cy + '%' }, pic);
    const ring = svg('circle', { class: 'bp-ring', cx: cx + '%', cy: cy + '%', pathLength: 1 }, pic);
    disc.style.r = ring.style.r = radius + 'cqw';
    const origin = `${cx}% ${cy}%`, scale = ((far / radius) * 1.05).toFixed(2);
    c.fx.append(layer);
    return {
      disc, ring,
      zoom: [{ transform: 'scale(1)', transformOrigin: origin }, { transform: `scale(${scale})`, transformOrigin: origin }],
      parts: [sheetOf(c), c.screen, layer],
    };
  }

  TL.register({
    id: 'blueprint',
    name: 'Blueprint',
    tagline: 'A cyanotype drawing, plotted line by line',
    mood: 'A Prussian blue drawing sheet on which every line, bar and numeral is put down by an exact, unhurried pen.',
    motion: [
      'The border draws itself round the sheet and the clock is plotted',
      'Unlocking strikes the clock through and pulls the sheet off the stack',
      'A launch rings its line as a detail and the view zooms into it',
    ],
    palette: { bg: '#0d3769', fg: '#eef5ff', dim: '#8fb5e0', accent: '#ffd166', app: '#86e1f5', ok: '#a8ecb9', err: '#ff7d6e' },
    light: false,
    fonts: ['Share+Tech+Mono'],
    hooks: {
      mount(c) {
        const sheet = makeSheet(c);
        c.bg.append(sheet);
        const draw = () => drawGrid(c, sheet.querySelector('canvas'));
        draw();
        if (!window.ResizeObserver) return undefined;
        const watch = new ResizeObserver(draw);
        watch.observe(c.root);
        return () => watch.disconnect();
      },

      /* Outlined numerals: one mask keeps the rim of a fat stroke, a second one hatches its inside. */
      clock(c, node, time) {
        const id = 'bp' + (++serial);
        const box = c.el('span', 'bp-clock');
        const cells = [];
        let x = 0;
        for (const ch of String(time)) {
          const g = NUM[ch];
          if (!g) continue;
          if (cells.length) x += 1;
          cells.push({ g, x });
          x += g.w || 3;
        }
        const m = 0.5, view = { x: -m, y: -m, width: x + 2 * m, height: 5 + 2 * m };
        const pic = svg('svg', { class: 'bp-num', viewBox: `${view.x} ${view.y} ${view.width} ${view.height}`, 'aria-hidden': 'true' });
        const defs = svg('defs', {}, pic);
        const hatch = svg('pattern', { id: id + 'h', width: 0.36, height: 0.36, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(-45)' }, defs);
        svg('line', { class: 'bp-hl', x1: 0, y1: 0.18, x2: 0.36, y2: 0.18 }, hatch);
        const rim = svg('mask', Object.assign({ id: id + 'o', maskUnits: 'userSpaceOnUse' }, view), defs);
        const inside = svg('mask', Object.assign({ id: id + 'i', maskUnits: 'userSpaceOnUse' }, view), defs);
        for (const [mask, cls] of [[rim, 'bp-o'], [rim, 'bp-i'], [inside, 'bp-f']]) {
          cells.forEach((cell, k) => svg('path', { class: cls, d: cell.g.d, transform: `translate(${cell.x} 0)`, pathLength: 1, 'data-k': k }, mask));
        }
        svg('rect', Object.assign({ class: 'bp-hatch', fill: `url(#${id}h)`, mask: `url(#${id}i)` }, view), pic);
        svg('rect', Object.assign({ class: 'bp-line', mask: `url(#${id}o)` }, view), pic);

        // construction lines: cap line, centre line, base line, and the two sides of every numeral
        const rule = (cls, v) => { const n = c.el('i', 'bp-cl ' + cls); n.style.setProperty('--v', v); box.append(n); };
        rule('bp-h', 0); rule('bp-h bp-c', 2.5); rule('bp-h', 5);
        for (const cell of cells) {
          if (cell.g.w) continue;
          rule('bp-v', cell.x + (cell.g.l || 0)); rule('bp-v', cell.x + (cell.g.r || 3));
        }
        box.style.setProperty('--n', x);
        box.append(pic);
        node.dataset.time = time;
        node.setAttribute('aria-label', time);
        node.append(box);
      },

      /* The light comes up under the sheet, the grid fades in, the border draws itself, the clock is plotted. */
      async powerOn(c) {
        const sheet = sheetOf(c);
        if (c.reduced) return c.animate(c.root, fade(0, 1), 240);
        fire(c, sheet.querySelector('.bp-paper'), fade(0, 1), { duration: 340, easing: 'ease-out' });
        fire(c, sheet.querySelector('.bp-grid'), fade(0, 1), { duration: 480, delay: 120 });
        border(c, 160, 720);
        fire(c, c.status, wipe, { duration: 340, delay: 280 });
        fire(c, c.chips, wipe, { duration: 340, delay: 640 });

        const clock = c.lines.querySelector('.bp-clock');
        const t0 = 330;
        if (clock) {
          clock.querySelectorAll('.bp-cl').forEach((n, i) => {
            const flat = n.classList.contains('bp-h');
            fire(c, n, [{ transform: flat ? 'scaleX(0)' : 'scaleY(0)' }, { transform: 'none' }], { duration: flat ? 420 : 240, delay: t0 + i * 26, easing: DRAW });
          });
          // each numeral is one stroke of the pen: its three copies in the masks are drawn together
          clock.querySelectorAll('path').forEach((p) => fire(c, p, stroke(1.02, 0), { duration: 470, delay: t0 + 130 + p.dataset.k * 75, easing: DRAW }));
          fire(c, clock.querySelector('.bp-hatch'), fade(0, 0.5), { duration: 260, delay: t0 + 800 });
        }
        plot(c.lines.querySelector('[data-role="lock-title"]'), t0 - 60);
        plot(c.lines.querySelector('[data-role="lock-date"]'), t0 + 480);
        plot(c.lines.querySelector('[data-role="lock-info"]'), t0 + 570);
        return c.sleep(t0 + 900);
      },

      line(c, node, d) {
        if (d.t === 'text' || d.t === 'launch') measure(node);
      },

      /* The pen at the prompt dips with every key. */
      key(c) {
        const cursor = c.prompt.querySelector('.tl-cursor');
        if (cursor) c.animate(cursor, [{ transform: 'translateY(0.14em)' }, { transform: 'none' }], { duration: 110, easing: 'ease-out' }).catch(() => {});
      },

      /* Four lines close on the clock, a pencil strikes it through, the sheet is stamped and pulled off the stack. */
      async granted(c) {
        if (c.reduced) return c.animate(c.lines, fade(1, 0), { duration: 180, fill: 'forwards' });
        const over = c.el('div', 'bp-over');
        c.fx.append(over);
        const clock = c.lines.querySelector('.bp-clock');
        if (clock) {
          const r = c.rect(clock);
          const put = (cls, css) => { const n = c.el('i', cls); n.style.cssText = css || ''; over.append(n); return n; };
          const edge = (100 * FY) / TALL;
          await Promise.all([
            ['bp-con bp-ch', 'top', edge, r.y], ['bp-con bp-ch', 'top', 100 - edge, r.y + r.h],
            ['bp-con bp-cv', 'left', FX, r.x], ['bp-con bp-cv', 'left', 100 - FX, r.x + r.w],
          ].map(([cls, side, from, to]) => c.animate(put(cls), [{ [side]: from + '%' }, { [side]: to + '%' }], { duration: 380, easing: DRAW, fill: 'both' })));
          await c.sleep(50);
          const strike = put('bp-strike', `left:${r.x - 2.5}%;top:${r.y + r.h / 2}%;width:${r.w + 5}%`);
          await c.animate(strike, [{ clipPath: 'inset(-1cqw 100% -1cqw 0)' }, { clipPath: 'inset(-1cqw 0% -1cqw 0)' }], { duration: 200, easing: DRAW, fill: 'both' });
        }
        c.say('access granted', 'ok').dataset.role = 'granted';
        await c.sleep(340);

        // the sheet is lifted by its bottom left corner and drawn off to the left; the next one lies under it
        const top = makeSheet(c, sheetOf(c)), shade = c.el('i', 'bp-shade');
        top.classList.add('bp-top');
        c.bg.append(shade, top);
        const pivot = '0% 100%';
        const pull = [
          { transform: 'translate(0, 0) rotate(0deg)', transformOrigin: pivot, easing: 'cubic-bezier(.3,0,.5,1)' },
          { transform: 'translate(-2.5%, -0.5%) rotate(-1.2deg)', transformOrigin: pivot, offset: 0.26, easing: 'cubic-bezier(.55,0,.6,.9)' },
          { transform: 'translate(-122%, -2%) rotate(-10deg)', transformOrigin: pivot },
        ];
        const time = { duration: 600, fill: 'forwards' };
        return Promise.all([
          c.animate(top, pull, time), c.animate(c.screen, pull, time), c.animate(over, pull, time),
          c.animate(shade, fade(0.55, 0), Object.assign({ easing: 'ease-in' }, time)),
        ]);
      },

      /* A ring spreads once from the dot the callout's leader starts at. */
      notify(c, node) {
        if (c.reduced) return;
        const r = c.rect(node);
        const ping = c.el('i', 'bp-ping');
        ping.style.cssText = `left:calc(${r.x.toFixed(2)}% + 0.86cqw);top:calc(${(r.y + r.h).toFixed(2)}% - 5.98cqw)`;
        c.fx.append(ping);
        pass(c, ping, [{ transform: 'scale(0.15)', opacity: 1 }, { opacity: 0.9, offset: 0.4 }, { transform: 'scale(1)', opacity: 0 }], { duration: 720, delay: 60, easing: 'cubic-bezier(.2,.6,.3,1)' });
      },

      /* A detail circle is drawn round the line, and the view goes into the detail until it is all there is. */
      async launch(c, line) {
        if (c.reduced) return TL.defaults.launch(c, line);
        const d = detail(c, line);
        const dark = c.animate(d.disc, [{ opacity: 0 }, { opacity: 0.25, offset: 0.4 }, { opacity: 1, offset: 0.92 }, { opacity: 1 }], { duration: 540, delay: 470, fill: 'both' });
        await c.animate(d.ring, stroke(1, 0), { duration: 370, delay: 60, easing: DRAW, fill: 'both' });
        await c.sleep(40);
        const time = { duration: 540, easing: 'cubic-bezier(.6,0,.9,.55)', fill: 'forwards' };
        return Promise.all([dark, ...d.parts.map((part) => c.animate(part, d.zoom, time))]);
      },

      /* The same, backwards: out of the detail, and the circle is taken up again. */
      async back(c) {
        const line = [...c.lines.querySelectorAll('.tl-launch')].pop();
        if (!line || c.reduced) return TL.defaults.back(c);
        const cover = c.el('i', 'bp-cover');
        c.fx.append(cover);
        await c.animate(cover, fade(0, 1), { duration: 110, fill: 'forwards' });
        const d = detail(c, line);
        const time = { duration: 560, fill: 'both', direction: 'reverse' };
        const out = [
          ...d.parts.map((part) => c.animate(part, d.zoom, Object.assign({ easing: 'cubic-bezier(.75,0,.88,.45)' }, time))),
          c.animate(d.disc, [{ opacity: 0 }, { opacity: 0, offset: 0.3 }, { opacity: 1, offset: 0.85 }, { opacity: 1 }], time),
        ];
        c.appview.style.opacity = '0';
        cover.remove();
        out.push(c.animate(d.ring, stroke(0, 1), { duration: 260, delay: 460, easing: DRAW, fill: 'both' }));
        return Promise.all(out);
      },

      /* The linework is taken up in reverse, last line first, then the border; the bare sheet goes dark. */
      async powerOff(c) {
        const sheet = sheetOf(c);
        if (c.reduced) return c.animate(c.root, fade(1, 0), { duration: 240, fill: 'forwards' });
        const box = c.lines.getBoundingClientRect();
        const shown = [...c.lines.children].filter((n) => {
          const b = n.getBoundingClientRect();
          return b.bottom > box.top && b.top < box.bottom;
        }).reverse();
        const step = Math.min(24, 400 / Math.max(1, shown.length));
        shown.forEach((n, i) => {
          const lettered = n.matches('.tl-text:not(.is-blank), .tl-echo, .tl-launch');
          if (!lettered) return fire(c, n, wipe, { duration: 260, delay: i * step, fill: 'both', direction: 'reverse' });
          // the pen goes back along the line, and the line goes with it
          const w = measure(n), time = { duration: 110 + w * 9, delay: i * step, fill: 'both' };
          fire(c, n, [
            { clipPath: `inset(-0.25em calc(100% - ${w}em - 0.55em) -0.25em -0.6em)` },
            { clipPath: 'inset(-0.25em calc(100% - 0.55em) -0.25em -0.6em)', offset: 0.96 },
            { clipPath: 'inset(-0.25em 110% -0.25em -0.6em)' },
          ], time);
          return fire(c, n.appendChild(c.el('i', 'bp-pen')), [{ opacity: 1, transform: `translateX(${w}em)` }, { opacity: 1, transform: 'translateX(0)' }], Object.assign({}, time, { fill: 'none' }));
        });
        fire(c, c.chips, wipe, { duration: 300, delay: 120, fill: 'both', direction: 'reverse' });
        fire(c, c.status, wipe, { duration: 320, delay: 420, fill: 'both', direction: 'reverse' });
        border(c, 380, 640, true);
        fire(c, sheet.querySelector('.bp-grid'), fade(1, 0), { duration: 420, delay: 760, fill: 'both' });
        fire(c, sheet.querySelector('.bp-paper'), fade(1, 0), { duration: 360, delay: 1020, fill: 'both', easing: 'ease-in' });
        return c.sleep(1400);
      },
    },
  });
})();
