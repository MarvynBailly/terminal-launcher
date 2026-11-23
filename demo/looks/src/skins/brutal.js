/*
 * Brutal: Swiss poster meets brutalist web. Paper, black, one red; an enormous grotesque for the
 * clock and a sturdy mono for the rest. Every movement is a block moving in a straight line and
 * stopping dead: nothing fades and nothing eases softly.
 */
(function () {
  'use strict';
  const INK = '#000000', PAPER = '#f5f4ef';
  const SNAP = 'cubic-bezier(.85,0,.1,1)';     // out of nowhere, dead stop
  const SLAM = 'cubic-bezier(.5,0,.9,.45)';    // gathers speed and hits
  const OUT = 'cubic-bezier(.6,0,1,.7)';       // leaves faster and faster
  const quiet = (p) => p.catch(() => {});
  const glyphsOf = (c) => [...c.lines.querySelectorAll('.brutal-clock b')];

  /** A solid block in the top layer, placed in percent of the phone. */
  function block(c, x, y, w, h) {
    const b = c.el('i', 'brutal-block');
    b.style.cssText = `left:${x}%;top:${y}%;width:${w}%;height:${h}%`;
    c.fx.append(b);
    return b;
  }

  /** Keyframes that go from value to value in hard moves: each one snaps over, then holds. */
  function stepped(prop, values) {
    const seg = 1 / (values.length - 1), frames = [];
    values.forEach((v, i) => {
      if (i) frames.push({ [prop]: v, offset: (i - 1 + 0.45) * seg });
      if (i < values.length - 1) frames.push({ [prop]: v, offset: i * seg, easing: SNAP });
    });
    frames.push({ [prop]: values[values.length - 1], offset: 1 });
    return frames;
  }

  /** The clock is set in type, one numeral to a box, spread to the full measure. */
  function clock(c, node, time) {
    node.dataset.time = time;
    node.setAttribute('aria-label', time);
    const row = c.el('span', 'brutal-clock');
    for (const ch of time) row.append(c.el('b', ch === ':' ? 'is-colon' : '', ch));
    node.append(row);
  }

  /**
   * The screen is a grid of black panels. They slide off row by row, each row out of the other
   * side, the panel by the edge first and the rest after it like wagons. The numerals drop into
   * place one at a time.
   */
  async function powerOn(c) {
    const COLS = 3, ROWS = 4, jobs = [];
    for (let r = 0; r < ROWS; r++) {
      const dir = r % 2 ? 1 : -1;
      for (let k = 0; k < COLS; k++) {                       // k = 0 is the panel by the edge it leaves through
        const col = dir < 0 ? k : COLS - 1 - k;
        const p = block(c, (col * 100) / COLS - 0.2, (r * 100) / ROWS - 0.2, 100 / COLS + 0.4, 100 / ROWS + 0.4);
        jobs.push(c.animate(p, [{ transform: 'none' }, { transform: `translateX(${dir * (k + 1) * 101}%)` }],
          { duration: 150 + k * 40, delay: 90 + (r * COLS + k) * 48, easing: OUT, fill: 'both' }));
      }
    }
    glyphsOf(c).forEach((g, i) => jobs.push(c.animate(g, [{ transform: 'translateY(-112%)' }, { transform: 'none' }],
      { duration: 170, delay: 340 + i * 95, easing: SLAM, fill: 'backwards' })));
    await Promise.all(jobs);
    // the last numeral lands and the sheet takes the knock
    await c.animate(c.lines, [{ transform: 'translateY(0.9cqw)' }, { transform: 'none' }], { duration: 90, easing: 'steps(1, end)' });
  }

  /**
   * The password was right. The numerals are shoved off the sheet by solid blocks: the middle ones
   * up and down by pistons that cross the whole screen, the outer ones sideways. Then a bar of red
   * crosses, and the summary is behind it.
   */
  async function granted(c) {
    const word = c.say('access granted', 'ok');
    word.dataset.role = 'granted';
    quiet(c.animate(word, [{ backgroundColor: PAPER, color: INK }, { backgroundColor: INK, color: PAPER }], { duration: 130, easing: 'steps(2, jump-none)', iterations: 2 }));
    await c.sleep(280);

    const glyphs = glyphsOf(c);
    const n = glyphs.length;
    if (n > 1) {
      const R = glyphs.map((g) => c.rect(g));
      const edge = [0];                                     // where one numeral's column ends and the next begins
      for (let i = 1; i < n; i++) edge.push((R[i - 1].x + R[i - 1].w + R[i].x) / 2);
      edge.push(100);
      const ghosts = glyphs.map((g, i) => {
        const ghost = c.el('b', 'brutal-ghost', g.textContent);
        ghost.style.cssText = `left:${R[i].x}%;top:${R[i].y}%`;
        c.fx.append(ghost);
        g.style.visibility = 'hidden';
        return ghost;
      });
      const jobs = [];
      const time = (k) => ({ duration: 230, delay: k * 58, easing: SLAM, fill: 'both' });
      let k = 0;
      for (let i = 1; i < n - 1; i++, k++) {                 // the pistons
        const down = k % 2 === 1;
        const bar = block(c, edge[i], 0, edge[i + 1] - edge[i], 100);
        const top = R[i].y, bottom = R[i].y + R[i].h;
        const meet = down ? top / 100 : (100 - bottom) / 100;
        jobs.push(c.animate(bar, [{ transform: `translateY(${down ? -100 : 100}%)` }, { transform: 'none' }], time(k)));
        jobs.push(c.animate(ghosts[i], [
          { transform: 'none', offset: 0 }, { transform: 'none', offset: meet },
          { transform: `translateY(${down ? 100 - top : -bottom}cqh)`, offset: 1 },
        ], time(k)));
      }
      const y = R[0].y, h = R[0].h;                          // the outer two go sideways, pushed from the middle
      const left = block(c, 0, y, edge[1], h), right = block(c, edge[n - 1], y, 100 - edge[n - 1], h);
      jobs.push(c.animate(left, [{ clipPath: 'inset(0 0 0 100%)' }, { clipPath: 'inset(0 0 0 0)' }], time(k)));
      jobs.push(c.animate(ghosts[0], [{ transform: 'none' }, { transform: `translateX(${-edge[1]}cqw)` }], time(k)));
      k++;
      jobs.push(c.animate(right, [{ clipPath: 'inset(0 100% 0 0)' }, { clipPath: 'inset(0 0 0 0)' }], time(k)));
      jobs.push(c.animate(ghosts[n - 1], [{ transform: 'none' }, { transform: `translateX(${100 - edge[n - 1]}cqw)` }], time(k)));
      await Promise.all(jobs);
      await c.sleep(50);
    }

    // the red bar runs by itself (see the stylesheet): the page is handed back while it covers everything
    const sweep = c.fx.querySelector('.brutal-sweep');
    if (sweep) {
      sweep.classList.remove('is-on');
      void sweep.offsetWidth;
      sweep.classList.add('is-on');
      await c.sleep(215);
    }
  }

  /** The line turns black, and its block grows to the whole screen in three hard steps. */
  async function launch(c, line) {
    await c.animate(line, [{ backgroundColor: PAPER, color: INK }, { backgroundColor: INK, color: PAPER }], { duration: 220, easing: 'steps(2, jump-none)' });
    const r = c.rect(line), under = 100 - r.y - r.h;
    const text = line.querySelector('.tl-launch-text');
    const at = text ? c.rect(text) : r;
    const sheet = c.el('div', 'brutal-sheet');
    const word = c.el('span', '', text ? text.textContent : '');
    word.style.cssText = `left:${at.x}%;top:${at.y}%`;
    sheet.append(word);
    const cut = (f) => `inset(${(r.y * (1 - f)).toFixed(2)}% 0 ${(under * (1 - f)).toFixed(2)}% 0)`;
    sheet.style.clipPath = cut(0);
    c.fx.append(sheet);
    quiet(c.animate(word, stepped('transform', ['none', 'scale(1.5)', 'scale(2.1)', 'scale(2.7)']), { duration: 640, fill: 'forwards' }));
    await c.animate(sheet, stepped('clipPath', [cut(0), cut(0.18), cut(0.5), cut(1)]), { duration: 640, fill: 'forwards' });
    await c.sleep(120);
  }

  /** The app folds back down into the line it came from, by the same steps. */
  async function back(c) {
    const line = [...c.lines.querySelectorAll('.tl-launch')].pop();
    const r = line ? c.rect(line) : { y: 48, h: 4 };
    const under = 100 - r.y - r.h;
    const cut = (f) => `inset(${(r.y * (1 - f)).toFixed(2)}% 0 ${(under * (1 - f)).toFixed(2)}% 0)`;
    await c.animate(c.appview, stepped('clipPath', [cut(1), cut(0.5), cut(0.18), cut(0)]), { duration: 480, fill: 'forwards' });
    await c.sleep(60);
  }

  /** Shutters: black bars close from alternating sides, top to bottom. */
  async function powerOff(c) {
    const N = 8, jobs = [];
    for (let i = 0; i < N; i++) {
      const bar = block(c, -0.2, (i * 100) / N - 0.2, 100.4, 100 / N + 0.4);
      jobs.push(c.animate(bar, [{ transform: `translateX(${i % 2 ? 101 : -101}%)` }, { transform: 'none' }],
        { duration: 190, delay: i * 48, easing: SLAM, fill: 'both' }));
    }
    await Promise.all(jobs);
    await c.sleep(140);
  }

  TL.register({
    id: 'brutal',
    name: 'Brutal',
    tagline: 'Swiss poster in black, white and one red',
    mood: 'A printed sheet of rules and slabs: heavy numerals, sturdy mono, and every move ends in a hard stop.',
    motion: [
      'Black panels slide off in rows, the numerals drop in',
      'Pistons shove the clock away, then a red bar crosses',
      'Bars wipe lines in; an app steps out of its own line',
    ],
    palette: { bg: PAPER, fg: INK, dim: '#000000', accent: '#e8230f', app: '#000000', ok: '#000000', err: '#e8230f' },
    light: true,
    fonts: ['Anton', 'Space+Mono:wght@400;700'],
    hooks: {
      mount(c) {
        c.fx.append(c.el('div', 'brutal-sweep'));
      },
      clock,
      powerOn,
      granted,
      launch,
      back,
      powerOff,
      /** The wiping bar is as long as the words it leaves behind. */
      line(c, node, d) {
        if (d.t === 'text') node.style.setProperty('--w', Math.max(1, node.textContent.length));
      },
      /** Each key knocks the cursor. */
      key(c) {
        const cursor = c.prompt.querySelector('.tl-cursor');
        if (cursor) quiet(c.animate(cursor, [{ transform: 'scale(1.5, 1.25)' }, { transform: 'none' }], { duration: 80, easing: 'steps(2, end)' }));
      },
      /** The slab hits the left edge and the sheet takes the knock. */
      async notify(c) {
        await c.sleep(200);
        await c.animate(c.lines, [{ transform: 'translateX(-1.2cqw)' }, { transform: 'none' }], { duration: 80, easing: 'steps(1, end)' });
      },
    },
  });
})();
