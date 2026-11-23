/*
 * The scripted demo every look is a skin of: the launcher waking, the password, the login summary,
 * a few commands, an app launch and the lock. One player runs on one phone-shaped element.
 * A look changes how all of it is drawn and how it moves, and never what it says.
 *
 * TL.register(look)            a look announces itself (see CONTRACT.md)
 * TL.create(host, id, opts)    builds a phone in `host` and returns a player
 *   player.play(scene)         run from a scene on, in a loop: wake, lock, granted, use, launch, off
 *   player.still(scene)        lay a scene out at rest, with nothing moving: lock, home, use
 *   player.destroy()
 */
(function () {
  'use strict';
  const TL = (window.TL = window.TL || {});
  const looks = (TL.looks = TL.looks || []);

  TL.register = function (look) {
    if (!look || !/^[a-z][a-z0-9-]*$/.test(look.id || '')) throw new Error('a look needs an id of lower-case letters');
    const at = looks.findIndex((l) => l.id === look.id);
    if (at >= 0) looks[at] = look; else looks.push(look);
    return look;
  };
  TL.get = (id) => looks.find((l) => l.id === id) || null;

  const CANCEL = { cancelled: true };
  const ORDER = ['wake', 'lock', 'granted', 'use', 'launch', 'off'];
  const STILLS = { lock: 1, home: 3, use: 4 };       // how many scenes to lay out for each still

  /* ---------------- what the demo says: the same in every look ---------------- */

  const APPS = [
    'browser', 'calculator', 'calendar', 'camera', 'clock', 'contacts',
    'files', 'mail', 'maps', 'messages', 'music', 'notes',
    'phone', 'photos', 'podcasts', 'settings', 'signal', 'spotify',
  ];
  const T = (...parts) => ({ t: 'text', parts });
  const G = (label, pct, note, alert) => ({ t: 'gauge', label, pct, note, alert: !!alert });
  const ok = (name, value) => T(['[ ', 'dim'], ['ok', 'ok'], [' ] ', 'dim'], name.padEnd(15), [value, 'dim']);

  const CONTENT = {
    time: '11:06',
    bootLog: () => [
      T(['[ 0.000] tty1: waking', 'dim']),
      ok('display', '1080x2424'),
      ok('keyboard', 'ready'),
      ok('apps', '42 found'),
      ok('notifications', '2 waiting'),
      ok('battery', '82%'),
      T(''),
    ],
    lockHeader: () => [
      Object.assign(T(['phone login · tty1', 'dim']), { role: 'lock-title' }),
      { t: 'clock', time: '11:06' },
      Object.assign(T('Wed 18 Nov'), { role: 'lock-date' }),
      Object.assign(T(['2 notifications · battery 82%', 'dim']), { role: 'lock-info' }),
    ],
    summary: () => [
      T(['Last login: Wed 18 Nov 08:42 on tty1', 'dim']),
      T(''),
      G('day', 46, '46%'),
      G('week', 43, 'wed'),
      G('year', 88, '88%'),
      G('phone', 60, '1h 12m of 2h'),
      T(''),
      Object.assign(T(['9°C light rain', 'app'], [' · 11°/6° · rain 70%', 'dim']), { tap: true }),
      Object.assign(T(['23 unlocks', 'app'], [' today', 'dim']), { tap: true }),
      Object.assign(T(['2 new', 'app'], [' · wifi · torch off', 'dim']), { tap: true }),
      T(['tip: ↑ recalls your last command', 'dim']),
      T(''),
    ],
    forecast: () => [
      T(['berlin, germany', 'accent']),
      T('9°C  light rain'),
      T(['feels 6° · wind 14 km/h · humidity 81%', 'dim']),
      T(''),
      T(['today ', 'dim'], ' 11°', ['   6°', 'dim'], '  light rain   ', [' 70%', 'app']),
      T(['thu   ', 'dim'], '  8°', ['   3°', 'dim'], '  overcast     ', [' 20%', 'dim']),
      T(['fri   ', 'dim'], '  7°', ['   1°', 'dim'], '  partly cloudy', [' 10%', 'dim']),
      T(['open-meteo.com · 11:06', 'dim']),
    ],
    week: () => [
      G('sun', 100, ' 2h 31m   44', true),
      G('mon', 79, ' 1h 35m   38'),
      G('tue', 54, ' 1h 05m   31'),
      G('today', 60, ' 1h 12m   23'),
      T(['average: 1h 44m, 38 unlocks a day', 'dim']),
      T(['budget 2h a day · 48m left today', 'dim']),
    ],
    note: () => ({ t: 'note', app: 'messages', text: 'Mom: are you coming on Sunday?' }),
  };
  TL.content = CONTENT;

  /* ---------------- small tools, also for looks to use ---------------- */

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  const GLYPH = {
    0: '111101101101111', 1: '010110010010111', 2: '111001111100111', 3: '111001111001111',
    4: '101101111001001', 5: '111100111001111', 6: '111100111101111', 7: '111001001001001',
    8: '111101111101111', 9: '111101111001111', ':': '01010',
  };
  /** The lock clock as 5 rows of 17 cells, row by row: true where a cell is lit. */
  function clockCells(text) {
    const rows = [[], [], [], [], []];
    [...text].forEach((ch, i) => {
      const g = GLYPH[ch], w = g.length / 5;
      for (let r = 0; r < 5; r++) {
        if (i) rows[r].push(false);
        for (let c = 0; c < w; c++) rows[r].push(g[r * w + c] === '1');
      }
    });
    return rows.flat();
  }

  const NOISE = '!<>-_\\/[]{}=+*^?#';
  /** What `text` looks like `p` (0 to 1) of the way through resolving out of noise, left to right. */
  function scrambled(text, p) {
    const head = p * (text.length + 4);
    let out = '';
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      out += i < head - 3 || ch === ' ' ? ch : i < head + 3 ? NOISE[(Math.random() * NOISE.length) | 0] : ' ';
    }
    return out;
  }

  TL.util = { el, clockCells, scrambled, GLYPH };

  /* ---------------- drawing a line ---------------- */

  function fillParts(node, parts) {
    for (const part of parts) {
      if (typeof part === 'string') node.append(el('span', '', part));
      else node.append(el('span', 's-' + part[1], part[0]));
    }
  }

  function drawClock(node, time) {
    node.dataset.time = time;
    node.setAttribute('aria-label', time);
    const grid = el('span', 'tl-clock-grid');
    clockCells(time).forEach((on, i) => {
      const cell = el('i', on ? 'on' : '');
      cell.style.setProperty('--i', i);
      cell.style.setProperty('--col', i % 17);
      cell.style.setProperty('--row', (i / 17) | 0);
      grid.append(cell);
    });
    node.append(grid);
  }

  const GAUGE_CELLS = 14;
  function drawGauge(node, d) {
    const filled = Math.max(0, Math.min(GAUGE_CELLS, Math.round((d.pct * GAUGE_CELLS) / 100)));
    node.style.setProperty('--pct', d.pct);
    node.style.setProperty('--filled', filled);
    if (d.alert) node.classList.add('is-alert');
    node.append(el('span', 'tl-gauge-label', d.label));
    const bar = el('span', 'tl-gauge-bar');
    for (let i = 0; i < GAUGE_CELLS; i++) {
      const cell = el('i', i < filled ? 'on' : '');
      cell.style.setProperty('--i', i);
      bar.append(cell);
    }
    node.append(bar, el('span', 'tl-gauge-note', d.note));
  }

  /* ---------------- what happens when a look says nothing: the launcher as it moves today ---------------- */

  const defaults = {
    powerOn: (c) => c.animate(c.screen, [
      { transform: 'scale(0, 0.004)', filter: 'brightness(8)' },
      { transform: 'scale(1, 0.004)', filter: 'brightness(8)', offset: 0.35 },
      { transform: 'none', filter: 'none' },
    ], { duration: 650, easing: 'cubic-bezier(.2,.8,.2,1)' }),
    powerOff: (c) => c.animate(c.screen, [
      { transform: 'none', filter: 'none' },
      { transform: 'scale(1, 0.004)', filter: 'brightness(8)', offset: 0.55 },
      { transform: 'scale(0, 0.004)', filter: 'brightness(8)' },
    ], { duration: 520, easing: 'cubic-bezier(.42,0,1,1)', fill: 'forwards' }),
    async granted(c) {
      const word = c.say('', 'ok');
      word.dataset.role = 'granted';
      const cells = [...c.lines.querySelectorAll('.tl-clock i.on')].sort(() => Math.random() - 0.5);
      cells.forEach((cell, i) => c.animate(cell, [{ opacity: 1 }, { opacity: 0 }], { duration: 140, delay: i * 9, fill: 'forwards' }).catch(() => {}));
      await c.scramble(word.firstChild, 'access granted', 520);
      await c.sleep(180);
      await c.animate(c.lines, [{ opacity: 1 }, { opacity: 0 }], { duration: 160, fill: 'forwards' });
    },
    async launch(c, line) {
      const r = c.rect(line);
      const sheet = el('div', 'tl-launch-sheet');
      c.fx.append(sheet);
      await c.sleep(150);
      await c.animate(sheet, [
        { clipPath: `inset(${r.y}% 0 ${100 - r.y - r.h}% 0)`, opacity: 0.9 },
        { clipPath: 'inset(0 0 0 0)', opacity: 1 },
      ], { duration: 340, easing: 'cubic-bezier(.3,.8,.2,1)', fill: 'forwards' });
    },
    back: (c) => c.animate(c.appview, [{ opacity: 1 }, { opacity: 0 }], { duration: 220, fill: 'forwards' }),
  };
  TL.defaults = defaults;

  /* ---------------- the player ---------------- */

  TL.create = function (host, lookId, opts) {
    opts = opts || {};
    const look = TL.get(lookId) || looks[0];
    if (!look) throw new Error('no looks are registered');
    const hooks = look.hooks || {};
    const still = !!opts.still;
    const reduced = !!opts.reduced;

    const root = el('div', 'tl' + (still ? ' tl-still' : ''));
    root.dataset.look = look.id;
    root.dataset.scene = 'off';
    const bg = el('div', 'tl-bg');
    const screen = el('div', 'tl-screen');
    const fx = el('div', 'tl-fx');
    const status = el('div', 'tl-status');
    const statusLeft = el('span', 'tl-status-l');
    const statusRight = el('span', 'tl-status-r', 'wifi 2 new 82% ' + CONTENT.time);
    status.append(statusLeft, statusRight);
    const body = el('div', 'tl-body');
    const lines = el('div', 'tl-lines');
    const prompt = el('div', 'tl-prompt');
    const ps = el('span', 'tl-ps');
    const input = el('span', 'tl-input');
    const cursor = el('span', 'tl-cursor');
    prompt.append(ps, input, cursor);
    body.append(lines, prompt);
    const chips = el('div', 'tl-chips');
    screen.append(status, body, chips);
    /* The app that was launched. It is another app, so it looks the same in every look. */
    const appview = el('div', 'tl-appview');
    appview.hidden = true;
    root.append(bg, screen, appview, fx);
    host.append(root);

    let run = 0;                // goes up when a run is cancelled: what is still waiting then gives up
    let fast = false;           // laying a scene out: nothing waits and nothing moves
    let dead = false;
    const frames = new Set();
    let raf = 0;

    function sleep(ms) {
      const mine = run;
      if (mine !== run || dead) return Promise.reject(CANCEL);
      if (fast) return Promise.resolve();
      return new Promise((done, fail) => setTimeout(() => (mine === run && !dead ? done() : fail(CANCEL)), ms));
    }

    function tick(now) {
      raf = 0;
      if (dead) return;
      for (const fn of frames) {
        try { fn(now); } catch (e) { console.error('look ' + look.id + ': frame', e); frames.delete(fn); }
      }
      if (frames.size && !still) raf = requestAnimationFrame(tick);
    }

    const ctx = {
      root, bg, screen, fx, lines, prompt, status, chips, appview, look, el,
      still, reduced,
      get fast() { return fast; },
      sleep,
      /** Runs `fn(ms)` on every frame until the phone is gone. On a still it runs once, so a canvas has its picture. */
      onFrame(fn) {
        frames.add(fn);
        if (still) { try { fn(performance.now()); } catch (e) { console.error('look ' + look.id + ': frame', e); } }
        else if (!raf) raf = requestAnimationFrame(tick);
        return () => frames.delete(fn);
      },
      /** An animation of the Web Animations kind, as a promise. It does nothing while a scene is being laid out. */
      animate(node, keyframes, options) {
        if (fast || still || !node.animate) return Promise.resolve();
        const mine = run;
        const anim = node.animate(keyframes, typeof options === 'number' ? { duration: options } : options);
        return anim.finished.then(() => { if (mine !== run || dead) throw CANCEL; }, () => { throw CANCEL; });
      },
      /** A canvas filling a layer (ctx.bg or ctx.fx), sized in device pixels. `c.w` and `c.h` follow the phone's size. */
      canvas(layer) {
        const canvas = el('canvas');
        canvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block';
        (layer || bg).append(canvas);
        const c = { canvas, g: canvas.getContext('2d'), w: 0, h: 0, dpr: 1 };
        // On a still nothing draws again by itself, so whatever empties the canvas has to bring the picture back.
        const redraw = () => {
          if (!still || dead) return;
          for (const fn of frames) { try { fn(performance.now()); } catch (e) { console.error('look ' + look.id + ': frame', e); } }
        };
        const fit = () => {
          const box = root.getBoundingClientRect();
          c.dpr = Math.min(2, window.devicePixelRatio || 1);
          c.w = Math.max(1, Math.round(box.width * c.dpr));
          c.h = Math.max(1, Math.round(box.height * c.dpr));
          if (canvas.width === c.w && canvas.height === c.h) return false;
          canvas.width = c.w;
          canvas.height = c.h;
          return true;
        };
        fit();
        if (window.ResizeObserver) {
          const ro = new ResizeObserver(() => { if (fit()) redraw(); });
          ro.observe(root);
          cleanups.push(() => ro.disconnect());
        }
        canvas.addEventListener('contextrestored', redraw);
        return c;
      },
      /** Where a node is on the phone, in percent of the phone's width and height. */
      rect(node) {
        const a = root.getBoundingClientRect(), b = node.getBoundingClientRect();
        const w = a.width || 1, h = a.height || 1;
        return { x: ((b.left - a.left) / w) * 100, y: ((b.top - a.top) / h) * 100, w: (b.width / w) * 100, h: (b.height / h) * 100 };
      },
      /** Adds a line of text that is simply there, in a style: dim, accent, app, ok or err. Returns its element. */
      say(text, style) {
        return add({ t: 'text', parts: [style ? [text, style] : text], instant: true });
      },
      /** Resolves `text` out of noise in `node` over `ms`. */
      async scramble(node, text, ms) {
        if (fast || still) { node.textContent = text; return; }
        const steps = Math.max(1, Math.round(ms / 22));
        for (let i = 0; i <= steps; i++) {
          node.textContent = scrambled(text, i / steps);
          await sleep(22);
        }
        node.textContent = text;
      },
    };
    const cleanups = [];
    const kept = new Set();     // what the look's mount put in the layers: it stays through every scene

    async function hook(name, fallback, ...args) {
      if (fast) return;
      const fn = hooks[name] || fallback;
      if (!fn) return;
      const mine = run;
      try {
        await fn(ctx, ...args);
      } catch (e) {
        if (e !== CANCEL) console.error('look ' + look.id + ': ' + name, e);
      }
      if (mine !== run || dead) throw CANCEL;
    }

    /* ---- lines ---- */

    function render(d) {
      const node = el('div', 'tl-line tl-' + d.t);
      if (d.role) node.dataset.role = d.role;
      if (d.t === 'text') {
        if (d.tap) node.classList.add('is-tap');
        fillParts(node, d.parts);
        if (!node.textContent) node.classList.add('is-blank');
      } else if (d.t === 'echo') {
        node.append(el('span', 'tl-ps', d.ps), el('span', 'tl-typed', d.text));
      } else if (d.t === 'gauge') {
        drawGauge(node, d);
      } else if (d.t === 'clock') {
        if (hooks.clock) hooks.clock(ctx, node, d.time); else drawClock(node, d.time);
      } else if (d.t === 'grid') {
        const rows = Math.ceil(d.names.length / 3);
        node.style.setProperty('--rows', rows);
        d.names.forEach((name, i) => {
          const cell = el('span', 'tl-app', name);
          cell.style.setProperty('--i', i);
          cell.style.setProperty('--col', (i / rows) | 0);
          cell.style.setProperty('--row', i % rows);
          node.append(cell);
        });
      } else if (d.t === 'note') {
        node.append(el('span', 'tl-note-app', d.app), el('span', 'tl-note-text', d.text));
      } else if (d.t === 'launch') {
        node.append(el('span', 'tl-launch-text', 'opening ' + d.name), el('span', 'tl-spin'));
      }
      return node;
    }

    function add(d, before) {
      const node = render(d);
      if (before) lines.insertBefore(node, before); else lines.append(node);
      // An echo is what was just typed: it is already on screen, so it does not print itself out again.
      if (!fast && !still && d.t !== 'echo' && !d.instant) {
        node.classList.add('tl-in');
        if (hooks.line) { try { hooks.line(ctx, node, d); } catch (e) { console.error('look ' + look.id + ': line', e); } }
      }
      lines.scrollTop = lines.scrollHeight;
      return node;
    }

    async function print(list, gap, before) {
      for (const d of list) {
        add(d, before);
        await sleep(gap);
      }
    }

    function setScene(name) { root.dataset.scene = name; }
    function setStatus(where) { statusLeft.textContent = 'tty1 · ' + where; }
    function setPrompt(text, kind) {
      prompt.hidden = text == null;
      prompt.dataset.kind = kind || 'shell';
      ps.textContent = text || '';
      input.textContent = '';
    }
    function setChips(list) {
      chips.replaceChildren(...list.map(([text, kind]) => el('span', 'tl-chip' + (kind ? ' is-' + kind : ''), text)));
    }
    const HOME_CHIPS = [['clock', 'app'], ['signal', 'app'], ['maps', 'app'], ['camera', 'app']];

    async function type(text, o) {
      o = o || {};
      let n = 0;
      for (const ch of text) {
        input.textContent += o.mask ? '•' : ch;
        if (++n === 2 && o.chips) setChips([['↑', 'key'], ...o.chips]);
        if (!fast && hooks.key) { try { hooks.key(ctx, ch); } catch (e) { console.error('look ' + look.id + ': key', e); } }
        await sleep(62 + Math.random() * 58);
      }
    }
    function enter() {
      const node = add({ t: 'echo', ps: ps.textContent, text: input.textContent });
      input.textContent = '';
      return node;
    }
    async function command(text, chipList, body) {
      await sleep(650);
      await type(text, { chips: chipList });
      await sleep(190);
      enter();
      setChips([['↑', 'key']]);
      await body();
      setChips([['↑', 'key'], ...HOME_CHIPS]);
    }

    function showApp(name) {
      appview.replaceChildren(el('div', 'tl-appview-icon', name[0].toUpperCase()), el('div', 'tl-appview-name', name), el('div', 'tl-appview-sub', 'another app, not part of the look'));
      appview.hidden = false;
    }

    /* ---- the scenes ---- */

    const scenes = {
      /** The screen comes on and the lock page is there: boot log above, clock and password prompt below. */
      async wake() {
        setScene('off');
        clearTransition();
        lines.replaceChildren();
        setPrompt(null);
        setChips([]);
        setStatus('locked');
        await sleep(600);
        const header = CONTENT.lockHeader().map((d) => add(Object.assign(d, { instant: true })));
        setScene('boot');
        await hook('powerOn', defaults.powerOn);
        setScene('lock');
        await print(CONTENT.bootLog(), 60, header[0]);
        setPrompt('password: ', 'login');
      },
      async lock() {
        await sleep(800);
        await type('hunter2', { mask: true });
        await sleep(260);
      },
      /** The right password: the lock page gives way and the login summary prints. */
      async granted() {
        prompt.hidden = true;
        setScene('granted');
        await hook('granted', defaults.granted);
        clearTransition();
        lines.replaceChildren();
        setScene('home');
        setStatus('home');
        await print(CONTENT.summary(), 60);
        setPrompt('$ ', 'shell');
        setChips([['↑', 'key'], ...HOME_CHIPS]);
        await sleep(1700);
      },
      async use() {
        await command('ls', [['ls', 'cmd'], ['lock', 'cmd']], async () => {
          add({ t: 'grid', names: APPS });
          await sleep(1300);
        });
        await command('weather', [['weather', 'cmd'], ['wifi', 'cmd']], async () => {
          prompt.hidden = true;
          const wait = add({ t: 'text', parts: [['asking open-meteo.com', 'dim']], instant: true });
          wait.dataset.role = 'wait';
          await sleep(750);
          wait.remove();
          await print(CONTENT.forecast(), 55);
          prompt.hidden = false;
        });
        await sleep(900);
        const note = add(CONTENT.note());
        await hook('notify', null, note);
        await sleep(1500);
        await command('screen', [['screen', 'cmd'], ['setup', 'cmd']], async () => {
          await print(CONTENT.week(), 75);
        });
        await sleep(1900);
      },
      /** An app's name alone opens it. The launcher hands over to the app and comes back. */
      async launch() {
        await sleep(300);
        await type('spotify', { chips: [['spotify', 'app']] });
        await sleep(190);
        enter();
        const line = add({ t: 'launch', name: 'spotify' });
        setScene('launch');
        await hook('launch', defaults.launch, line, 'spotify');
        showApp('spotify');
        setScene('app');
        clearTransition();
        await sleep(1500);
        // The terminal is as it was left, and the launch has stopped spinning.
        line.querySelectorAll('.tl-spin').forEach((spin) => spin.remove());
        await hook('back', defaults.back);
        appview.hidden = true;
        clearTransition();
        setScene('home');
        await sleep(900);
      },
      async off() {
        await type('lock', { chips: [['lock', 'cmd'], ['ls', 'cmd']] });
        await sleep(190);
        enter();
        prompt.hidden = true;
        setChips([]);
        await sleep(160);
        setScene('poweroff');
        await hook('powerOff', defaults.powerOff);
        setScene('off');
        clearTransition();
        lines.replaceChildren();
        await sleep(900);
      },
    };

    /**
     * Whatever a transition left behind goes, so the next scene starts clean: animations a script
     * started, styles written on the phone's own parts, and anything put in the layers since mount.
     */
    function clearTransition() {
      const scripted = (a) => !(window.CSSAnimation && a instanceof CSSAnimation) && !(window.CSSTransition && a instanceof CSSTransition);
      if (root.getAnimations) root.getAnimations({ subtree: true }).filter(scripted).forEach((a) => a.cancel());
      for (const node of [screen, status, body, lines, prompt, chips, appview]) node.style.cssText = '';
      for (const layer of [fx, bg]) [...layer.children].forEach((node) => { if (!kept.has(node)) node.remove(); });
    }

    async function layOut(count) {
      fast = true;
      try {
        for (let i = 0; i < count; i++) await scenes[ORDER[i]]();
      } finally {
        fast = false;
      }
    }

    const player = {
      root, look,
      /** Plays from `scene` on and keeps going round. Earlier scenes are laid out at once. */
      async play(scene) {
        if (still || dead) return;
        const mine = ++run;
        let i = Math.max(0, ORDER.indexOf(scene || 'wake'));
        try {
          await layOut(i);
          for (;;) {
            await scenes[ORDER[i]]();
            i = (i + 1) % ORDER.length;
            if (mine !== run) return;
          }
        } catch (e) {
          if (e !== CANCEL) throw e;
        }
      },
      /** Lays `scene` out at rest: lock, home or use. */
      async still(scene) {
        ++run;
        appview.hidden = true;
        try {
          await layOut(STILLS[scene] || STILLS.home);
        } catch (e) {
          if (e !== CANCEL) throw e;
        }
        return player;
      },
      destroy() {
        dead = true;
        run++;
        if (raf) cancelAnimationFrame(raf);
        frames.clear();
        cleanups.splice(0).forEach((fn) => { try { fn(); } catch (e) { /* a look's own mess */ } });
        root.remove();
      },
    };

    if (window.ResizeObserver) {
      const keepEnd = new ResizeObserver(() => { lines.scrollTop = lines.scrollHeight; });
      keepEnd.observe(lines);
      cleanups.push(() => keepEnd.disconnect());
    }

    if (hooks.mount) {
      try {
        const undo = hooks.mount(ctx);
        if (typeof undo === 'function') cleanups.push(undo);
      } catch (e) {
        console.error('look ' + look.id + ': mount', e);
      }
    }
    for (const layer of [fx, bg]) for (const node of layer.children) kept.add(node);
    return player;
  };
})();
