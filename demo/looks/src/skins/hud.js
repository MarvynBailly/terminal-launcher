/*
 * Flight Deck. The frame, the two scales and their carets are built once in mount and drawn by hud.css;
 * the hooks only move them. The carets follow the text cursor (its row on the left scale, its column on
 * the bottom one), and the bar on the frame's right edge is the part of the scrollback that is on screen.
 */
(function () {
  'use strict';
  const F = { l: 7, t: 13, r: 97.4, b: 197.47, c: 4.2 };    // the frame in cqw; the phone is 100 by 216.67
  const EDGE = 'rgba(85,216,255,.34)', HOT = '#e6f9ff';
  const quiet = () => {};

  const svg = (tag, attrs, parent) => {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    parent.append(node);
    return node;
  };
  /* On a circle 0 degrees is up and angles run clockwise. */
  const pol = (r, deg) => {
    const a = ((deg - 90) * Math.PI) / 180;
    return [+(r * Math.cos(a)).toFixed(2), +(r * Math.sin(a)).toFixed(2)];
  };
  const arc = (r, a0, a1) => `M${pol(r, a0)}A${r} ${r} 0 0 1 ${pol(r, a1)}`;
  const spoke = (r0, r1, deg) => `M${pol(r0, deg)}L${pol(r1, deg)}`;
  const tri = (tip, base, deg, half) => `M${pol(tip, deg)}L${pol(base, deg - half)}L${pol(base, deg + half)}Z`;

  /* Coming online: a flash, a dark beat, then on. Going dark is the same, backwards. */
  const ON = [[0, 0], [0.08, 0.85], [0.16, 0.1], [0.4, 0.1], [0.48, 1], [0.6, 0.4], [0.72, 1], [1, 1]];
  const steps = (list) => list.map(([offset, opacity]) => ({ offset, opacity, easing: 'steps(1, end)' }));
  const FLICK_ON = steps(ON), FLICK_OFF = steps(ON.map(([o, v]) => [1 - o, v]).reverse());
  const BLINK = steps([[0, 1], [0.2, 0.15], [0.4, 1], [0.6, 0.15], [0.8, 1], [1, 1]]);
  const on = (c, node, delay) => c.animate(node, FLICK_ON, { duration: 340, delay, fill: 'backwards' });
  const off = (c, node, delay) => c.animate(node, FLICK_OFF, { duration: 260, delay, fill: 'forwards' });

  /* A clip that shows the frame's four corners out to `a` across and down: at 50% it shows everything. */
  const corners = (a) => {
    const o = '-3cqw', O = 'calc(100% + 3cqw)', A = `calc(100% - ${a})`, m = '50% 50%';
    return `polygon(${m},${o} ${o},${a} ${o},${a} ${a},${o} ${a},${o} ${o},${m},${O} ${o},${O} ${a},${A} ${a},${A} ${o},${O} ${o},` +
      `${m},${O} ${O},${A} ${O},${A} ${A},${O} ${A},${O} ${O},${m},${o} ${O},${o} ${A},${a} ${A},${a} ${O},${o} ${O})`;
  };
  const SHUT = corners('0cqw'), BRACKETS = corners('8.6cqw'), OPEN = corners('50%');
  /* A scale populates or retracts a tick at a time: `hide` is the percentage hidden, from its far end. */
  const wipe = (c, node, down, hide, ticks, options) => {
    const clip = (p) => ({ clipPath: down ? `inset(-1cqw -1cqw ${p}% -1cqw)` : `inset(-1cqw ${p}% -1cqw -1cqw)` });
    return c.animate(node, hide.map(clip), Object.assign({ easing: `steps(${ticks}, end)` }, options));
  };

  const setDigits = (digits, minutes) => {
    const s = String((minutes / 60) | 0).padStart(2, '0') + String(minutes % 60).padStart(2, '0');
    digits.querySelectorAll('b').forEach((b, i) => { b.textContent = s[i]; });
  };
  const hourTurn = (h, m) => ((h % 12) + m / 60) * 30;

  function mount(c) {
    const e = c.el, H = (c.hud = { thumb: e('i') });
    for (const k of ['deck', 'frame', 'rim', 'track', 'sy', 'sx', 'cy', 'cx', 'eye']) H[k] = e('i', 'hud-' + k);
    H.frame.append(H.rim, H.track, ...['k hud-k1', 'k hud-k2', 'k hud-k3', 'k hud-k4', 'n hud-n1', 'n hud-n2'].map((k) => e('i', 'hud-' + k)));
    H.track.append(H.thumb);
    /* A scale: a tick every `step` cqw, a longer one every `mid`, the longest with a numeral every `major`. */
    const scale = (host, count, step, side, mid, major, value) => {
      for (let i = 0; i <= count; i++) {
        const tick = e('i', i % major ? (i % mid ? '' : 'r') : 'm');
        tick.style[side] = (i * step).toFixed(2) + 'cqw';
        host.append(tick);
        if (i % major === 0) host.appendChild(e('b', '', String(value(i)).padStart(2, '0'))).style[side] = tick.style[side];
      }
    };
    scale(H.sy, 66, 2.7, 'top', 2, 10, (i) => i / 2);       // half rows of text
    scale(H.sx, 43, 1.944, 'left', 5, 10, (i) => i);        // columns of text
    H.deck.append(H.frame, H.sy, H.sx, H.cy, H.cx, H.eye);
    c.bg.append(H.deck);

    const cursor = c.prompt.querySelector('.tl-cursor');
    const track = () => {
      const high = c.lines.scrollHeight, over = high > c.lines.clientHeight + 2;
      H.thumb.classList.toggle('on', over);
      if (over) {
        H.thumb.style.top = ((c.lines.scrollTop / high) * 100).toFixed(1) + '%';
        H.thumb.style.height = ((c.lines.clientHeight / high) * 100).toFixed(1) + '%';
      }
      const r = c.rect(cursor);
      if (c.prompt.hidden || !r.h) return;
      H.cy.style.top = (r.y + r.h / 2).toFixed(2) + '%';
      H.cx.style.left = (r.x + r.w / 2).toFixed(2) + '%';
    };
    const seen = new MutationObserver(track);
    seen.observe(c.screen, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['hidden', 'data-kind'] });
    const sized = new ResizeObserver(track);
    sized.observe(c.root);
    document.fonts.addEventListener('loadingdone', track);     // the faces can arrive after the page is drawn
    return () => { seen.disconnect(); sized.disconnect(); document.fonts.removeEventListener('loadingdone', track); };
  }

  /* Thin numerals in a ring of sixty ticks, an amber caret at the minute, a white one at the hour. */
  function clock(c, node, time) {
    node.dataset.time = time;
    node.setAttribute('aria-label', time);
    const [h, m] = time.split(':').map(Number);
    const s = svg('svg', { class: 'hud-ring', viewBox: '-50 -50 100 100' }, node);
    const ticks = svg('g', { class: 'hud-rt' }, s), rest = svg('g', { class: 'hud-rs' }, s);
    for (let i = 0; i < 60; i++) svg('path', i % 5 ? { d: spoke(44.7, 46.9, i * 6), opacity: 0.5 } : { d: spoke(42.9, 46.9, i * 6), 'stroke-width': 0.55 }, ticks);
    svg('circle', { r: 40.4, opacity: 0.32 }, rest);
    for (let q = 0; q < 360; q += 90) {
      svg('path', { d: arc(40.4, q + 33, q + 57), 'stroke-width': 1.15 }, rest);
      svg('path', { d: spoke(36.8, 40.4, q) }, rest);
    }
    svg('path', { d: tri(47.7, 51.2, m * 6, 2.6), class: 'hud-min', fill: '#ffb23e', stroke: 'none' }, rest);
    svg('path', { d: tri(39.3, 35.9, hourTurn(h, m), 3.6), class: 'hud-hour', fill: '#eafaff', stroke: 'none' }, rest);
    const digits = node.appendChild(c.el('span', 'hud-digits'));
    for (const ch of time) digits.append(c.el(ch === ':' ? 'i' : 'b', '', ch));
  }

  /* The frame draws itself (brackets, edges, the scales a tick at a time), then the instruments come on in turn. */
  async function powerOn(c) {
    const H = c.hud, q = (sel) => c.lines.querySelector(sel);
    const face = q('.tl-clock'), digits = q('.hud-digits');
    const [h, m] = face.dataset.time.split(':').map(Number);
    setDigits(digits, 0);
    const jobs = [
      c.animate(c.bg, [{ opacity: 0 }, { opacity: 1 }], { duration: 360, easing: 'ease-out' }),
      c.animate(H.frame, [
        { clipPath: SHUT, easing: 'cubic-bezier(.2,.8,.2,1)' },
        { clipPath: BRACKETS, offset: 0.3 },
        { clipPath: BRACKETS, offset: 0.4, easing: 'cubic-bezier(.6,0,.2,1)' },
        { clipPath: OPEN },
      ], { duration: 700, delay: 40, fill: 'backwards' }),
      wipe(c, H.sy, 1, [100, 0], 66, { duration: 520, delay: 240, fill: 'backwards' }),
      wipe(c, H.sx, 0, [100, 0], 43, { duration: 520, delay: 300, fill: 'backwards' }),
      on(c, H.eye, 380), on(c, c.status, 440), on(c, H.cy, 520), on(c, H.cx, 560),
      on(c, q('[data-role="lock-title"]'), 600), on(c, q('.hud-rs'), 660), on(c, digits, 720),
      on(c, q('[data-role="lock-date"]'), 860), on(c, q('[data-role="lock-info"]'), 930),
    ];
    face.querySelectorAll('.hud-rt path').forEach((tick, i) => {
      jobs.push(c.animate(tick, [{ opacity: 0 }, {}], { duration: 40, delay: 620 + i * 6, fill: 'backwards' }));
    });
    // the ring's two carets sweep round from twelve o'clock to the time, like a gauge's
    for (const [sel, turn] of [['.hud-min', m * 6], ['.hud-hour', hourTurn(h, m)]]) {
      jobs.push(c.animate(q(sel), [{ transform: `rotate(${-turn}deg)` }, { transform: 'none' }], { duration: 560, delay: 700, easing: 'cubic-bezier(.3,1.3,.5,1)', fill: 'backwards' }));
    }
    jobs.push((async () => {                               // and the time counts up
      await c.sleep(740);
      for (let i = 1; i <= 14; i++) {
        setDigits(digits, Math.round((h * 60 + m) * (1 - Math.pow(1 - i / 14, 2))));
        await c.sleep(30);
      }
    })());
    await Promise.all(jobs);
  }

  /* A gauge's numbers count up while its caret sweeps. */
  function line(c, node, d) {
    if (d.t !== 'gauge') return;
    const note = node.querySelector('.tl-gauge-note'), text = d.note;
    const frames = Math.max(5, Math.round((320 + Math.round((d.pct * 14) / 100) * 28) / 34));
    (async () => {
      for (let i = 0; i < frames; i++) {
        note.textContent = text.replace(/\d+/g, (n) => String(Math.round((n * i) / frames)).padStart(n.length, '0'));
        await c.sleep(34);
      }
    })().catch(quiet).then(() => { note.textContent = text; });
  }

  /* The unlock's targeting reticle: two rings of marks around a point, in the top layer. */
  function reticle(c, x, y) {
    const box = c.el('div', 'hud-reticle');
    box.style.cssText = `left:${x}%;top:${y}%`;
    const s = svg('svg', { viewBox: '-80 -80 160 160' }, box);
    const a = svg('g', {}, s), b = svg('g', {}, s);
    for (let q = 0; q < 360; q += 90) {
      svg('path', { d: arc(54.5, q + 18, q + 72), pathLength: 1, class: 'hud-draw' }, a);
      svg('path', { d: tri(51.5, 57.5, q, 3.4), class: 'hud-fill' }, a);
      svg('path', { d: spoke(60, 73, q + 45), pathLength: 1, class: 'hud-draw' }, b);
    }
    svg('circle', { r: 64, class: 'hud-dash' }, b);
    c.fx.append(box);
    return { box, a, b };
  }

  /* A reticle draws around the clock, spins down onto it and locks; then the page parts along a seam through the clock. */
  async function granted(c) {
    const H = c.hud, face = c.lines.querySelector('.tl-clock');
    const r = c.rect(face), x = r.x + r.w / 2, y = r.y + r.h / 2;
    const word = c.say('', 'ok');
    word.classList.remove('is-blank');
    word.dataset.role = 'granted';
    const R = reticle(c, x, y), spin = { duration: 540, easing: 'cubic-bezier(.5,0,.15,1)' };
    R.box.querySelectorAll('.hud-draw').forEach((p) => c.animate(p, [{ strokeDashoffset: 1 }, { strokeDashoffset: 0 }], { duration: 260, easing: 'ease-out' }).catch(quiet));
    c.animate(R.a, [
      { transform: 'rotate(-160deg) scale(1.75)', opacity: 0 },
      { opacity: 1, offset: 0.2 },
      { transform: 'rotate(7deg) scale(0.95)', offset: 0.86 },
      { transform: 'none', opacity: 1 },
    ], spin).catch(quiet);
    c.animate(R.b, [{ transform: 'rotate(130deg) scale(2.2)', opacity: 0 }, { opacity: 1, offset: 0.25 }, { transform: 'none', opacity: 1 }], spin).catch(quiet);
    await c.sleep(160);
    await c.scramble(word.firstChild, 'access granted', 340);
    // lock-on: the frame and the clock flash, the reticle blinks twice
    c.animate(H.rim, [{ borderColor: HOT }, { borderColor: EDGE }], { duration: 420, easing: 'ease-out' }).catch(quiet);
    c.animate(face, [{ filter: 'brightness(1.9)' }, { filter: 'brightness(1)' }], { duration: 360 }).catch(quiet);
    await c.animate(R.box, BLINK, { duration: 200 });
    await c.sleep(90);

    // the page, twice: a copy clipped to the half above the seam and one to the half below
    const yc = (y / 100) * (1950 / 9) - F.t;             // the seam, in cqw below the frame's top
    const wrap = c.el('div', 'hud-split');
    const halves = [0, 1].map((i) => {
      const half = wrap.appendChild(c.el('div', 'hud-half'));
      half.style.cssText = i ? `top:${yc}cqw;bottom:0;border-top-width:var(--h)` : `top:0;height:${yc}cqw;border-bottom-width:var(--h)`;
      const ghost = half.appendChild(c.screen.cloneNode(true));
      ghost.querySelectorAll('.tl-in').forEach((n) => n.classList.remove('tl-in'));
      ghost.style.cssText = `inset:auto;left:${-F.l}cqw;top:${-(F.t + (i ? yc : 0))}cqw;width:100cqw;height:100cqh`;
      return half;
    });
    c.fx.append(wrap);
    c.animate(R.box, [{ transform: 'none' }, { transform: 'scale(1.3, 0)' }], { duration: 120, easing: 'ease-in', fill: 'forwards' }).catch(quiet);
    await c.animate(wrap, [{ clipPath: 'inset(0 50% 0 50%)' }, { clipPath: 'inset(0 0% 0 0%)' }], { duration: 130, easing: 'ease-out' });
    c.lines.style.opacity = 0;
    const slide = { duration: 420, easing: 'cubic-bezier(.7,0,.8,.35)', fill: 'forwards' };
    await Promise.all([
      c.animate(halves[0], [{ transform: 'none' }, { transform: `translateY(${-yc - 1}cqw)` }], slide),
      c.animate(halves[1], [{ transform: 'none' }, { transform: `translateY(${F.b - F.t - yc + 1}cqw)` }], slide),
      c.animate(H.rim, [{ borderColor: HOT }, { borderColor: EDGE }], { duration: 200, delay: 340, easing: 'ease-out' }),
    ]);
  }

  /*
   * The tunnel of a launch: the frame's outline repeated into the distance, converging on the launched
   * line, with the app as the far wall. The page flies past as the wall arrives. `dir` -1 runs it backwards.
   */
  function tunnel(c, f, dur, dir) {
    const H = c.hud, cv = c.el('canvas', 'hud-tunnel'), g = cv.getContext('2d');
    c.fx.append(cv);
    const box = c.root.getBoundingClientRect(), dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = (cv.width = Math.max(1, Math.round(box.width * dpr))), h = (cv.height = Math.max(1, Math.round(box.height * dpr)));
    const u = w / 100, fx = f.x * u, fy = (f.y / 100) * h, hair = Math.max(1, u * 0.26);
    const N = 12, RHO = 0.66, SMIN = Math.pow(RHO, N);       // ring N is the frame itself; the far wall starts this small
    for (const node of [c.screen, H.deck, c.appview]) {
      node.style.transformOrigin = f.x.toFixed(2) + '% ' + f.y.toFixed(2) + '%';
      node.style.willChange = 'transform, opacity';
    }
    const X = (v, s) => fx + (v * u - fx) * s, Y = (v, s) => fy + (v * u - fy) * s;
    let t0 = 0, done = false;
    const draw = (now) => {
      t0 = t0 || now;
      let p = done ? 1 : Math.min(1, Math.max(0, (now - t0) / dur));
      if (dir < 0) p = 1 - p;
      const far = Math.pow(SMIN, 1 - p * p), zoom = far / SMIN, ramp = Math.min(1, p * 6);
      c.screen.style.transform = H.deck.style.transform = 'scale(' + Math.min(zoom, 4).toFixed(4) + ')';
      c.screen.style.opacity = H.deck.style.opacity = Math.max(0, Math.min(1, 1 - (zoom - 1) / 2.2)).toFixed(3);
      if (dir < 0) c.appview.style.transform = 'scale(' + far.toFixed(4) + ')';
      g.clearRect(0, 0, w, h);
      g.strokeStyle = '#55d8ff';
      g.globalAlpha = 0.22 * ramp;
      g.lineWidth = hair;
      g.beginPath();                                       // four rails along the tunnel's edges
      for (const [vx, vy] of [[F.l, F.t], [F.r, F.t], [F.r, F.b], [F.l, F.b]]) {
        g.moveTo(X(vx, far / RHO), Y(vy, far / RHO));
        g.lineTo(X(vx, 16), Y(vy, 16));
      }
      g.stroke();
      for (let i = 1; i < N; i++) {                        // the frame, again and again: the nearer, the bigger
        const s = far / Math.pow(RHO, i), k = F.c * u * s;
        if (s < 0.02 || s > 9) continue;
        const a = ramp * Math.min(1, (s - 0.02) / 0.1) * Math.min(1, (9 - s) / 6);
        g.beginPath();
        g.moveTo(X(F.l, s) + k, Y(F.t, s)); g.lineTo(X(F.r, s), Y(F.t, s)); g.lineTo(X(F.r, s), Y(F.b, s) - k);
        g.lineTo(X(F.r, s) - k, Y(F.b, s)); g.lineTo(X(F.l, s), Y(F.b, s)); g.lineTo(X(F.l, s), Y(F.t, s) + k);
        g.closePath();
        g.globalAlpha = a * 0.16; g.lineWidth = hair * (3 + s); g.stroke();
        g.globalAlpha = a; g.lineWidth = hair * (1 + s * 0.2); g.stroke();
      }
      const x = fx * (1 - far), y = fy * (1 - far);      // the far wall: the app, the size of the phone
      if (dir > 0) { g.globalAlpha = 1; g.fillStyle = '#101216'; g.fillRect(x, y, w * far, h * far); }
      g.globalAlpha = Math.min(1, (1 - far) * 5) * ramp;
      g.strokeStyle = HOT;
      g.lineWidth = hair * 1.5;
      g.strokeRect(x, y, w * far, h * far);
    };
    const stop = c.onFrame(draw);
    const end = () => { stop(); H.deck.style.cssText = ''; };
    return c.sleep(dur + 30).then(() => { done = true; draw(0); end(); }, (e) => { end(); throw e; });
  }

  /* Brackets close in on the launched line and lock; then the frame rushes past, a tunnel down to the app. */
  async function launch(c, line) {
    const a = c.rect(line.firstChild), b = c.rect(line.lastChild);
    const x0 = a.x - 2.4, x1 = b.x + b.w + 2.6, y0 = a.y - 0.5, y1 = a.y + a.h + 0.5;
    const box = c.el('div', 'hud-lockon');
    c.fx.append(box);
    await c.animate(box, [
      { left: F.l + 'cqw', top: F.t + 'cqw', width: F.r - F.l + 'cqw', height: F.b - F.t + 'cqw', opacity: 0 },
      { opacity: 1, offset: 0.15 },
      { left: x0 + '%', top: y0 + '%', width: x1 - x0 + '%', height: y1 - y0 + '%', opacity: 1 },
    ], { duration: 320, easing: 'cubic-bezier(.3,1.22,.4,1)', fill: 'forwards' });
    await c.animate(box, BLINK, { duration: 170 });
    c.animate(box, [{ transform: 'none', opacity: 1 }, { transform: 'scale(4)', opacity: 0 }], { duration: 280, easing: 'ease-in', fill: 'forwards' }).catch(quiet);
    await tunnel(c, { x: (x0 + x1) / 2, y: (y0 + y1) / 2 }, 700, 1);
  }

  /* The tunnel in reverse: the app recedes down it and the page settles back into the frame. */
  async function back(c) {
    const text = [...c.lines.querySelectorAll('.tl-launch-text')].pop();
    const r = text ? c.rect(text) : { x: 50, y: 50, w: 0, h: 0 };
    await tunnel(c, { x: r.x + r.w / 2 + 1, y: r.y + r.h / 2 }, 600, -1);
  }

  /* Systems down in turn: lines go dark, scales retract, the frame folds into its corners, a last line shrinks to a point. */
  async function powerOff(c) {
    const H = c.hud, last = c.el('div', 'hud-last');
    c.fx.append(last);
    await Promise.all([
      ...[...c.lines.children].slice(-28).reverse().map((node, i) => off(c, node, i * 15)),
      off(c, c.status, 140), off(c, H.track, 170), off(c, H.cy, 200), off(c, H.cx, 250), off(c, H.eye, 320),
      wipe(c, H.sy, 1, [0, 100], 66, { duration: 380, delay: 260, fill: 'forwards' }),
      wipe(c, H.sx, 0, [0, 100], 43, { duration: 380, delay: 320, fill: 'forwards' }),
      c.animate(H.frame, [
        { clipPath: OPEN, easing: 'cubic-bezier(.5,0,.3,1)' },
        { clipPath: BRACKETS, offset: 0.5 },
        { clipPath: BRACKETS, offset: 0.68, easing: 'cubic-bezier(.6,0,.9,.4)' },
        { clipPath: SHUT },
      ], { duration: 560, delay: 480, fill: 'forwards' }),
      c.animate(c.bg, [{ opacity: 1 }, { opacity: 0 }], { duration: 400, delay: 860, fill: 'forwards' }),
      c.animate(last, [
        { transform: 'scaleX(0)', opacity: 1, easing: 'cubic-bezier(.2,.8,.2,1)' },
        { transform: 'scaleX(1)', opacity: 1, offset: 0.22 },
        { transform: 'scaleX(1)', opacity: 1, offset: 0.5, easing: 'cubic-bezier(.6,0,.4,1)' },
        { transform: 'scaleX(0.012)', opacity: 1, offset: 0.84 },
        { transform: 'scaleX(0.012)', opacity: 0 },
      ], { duration: 820, delay: 440, fill: 'both' }),
    ]);
  }

  TL.register({
    id: 'hud',
    name: 'Flight Deck',
    tagline: 'A head-up display on a cockpit canopy',
    mood: 'Fine cyan linework on near-black glass: every reading sits on a scale, and amber carets on the row and column rulers follow your cursor.',
    motion: [
      'The frame draws itself from its corners, then each instrument flickers online',
      'A reticle spins down onto the clock, locks, and the page splits along its seam',
      'Brackets close on the launched line and the frame rushes past as a tunnel',
    ],
    palette: { bg: '#060a0f', fg: '#cfe9f4', dim: '#6c90a2', accent: '#ffb23e', app: '#55d8ff', ok: '#7cf0dc', err: '#ff5145' },
    light: false,
    fonts: ['Rajdhani:wght@300;600', 'Share+Tech+Mono'],
    hooks: { mount, clock, powerOn, line, granted, launch, back, powerOff },
  });
})();
