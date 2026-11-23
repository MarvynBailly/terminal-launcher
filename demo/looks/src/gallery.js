/* The page around the phones: one phone playing the chosen look, and a still of every look to choose from. */
(function () {
  'use strict';
  const looks = TL.looks;
  const $ = (sel) => document.querySelector(sel);
  const el = TL.util.el;
  const two = (n) => String(n).padStart(2, '0');

  const stage = $('#stage');
  const cards = $('#cards');
  const sceneRow = $('#scenes');
  const motionNote = $('#motion-note');
  const prefersLess = window.matchMedia('(prefers-reduced-motion: reduce)');

  /* With motion: play from a scene on. Without: the scenes at rest. */
  const MOVING = [['Wake', 'wake'], ['Unlock', 'lock'], ['Use', 'use'], ['Launch', 'launch'], ['Lock', 'off']];
  const RESTING = [['Lock page', 'lock'], ['Summary', 'home'], ['In use', 'use']];

  let moving = !prefersLess.matches;
  let chosen = null;
  let player = null;

  /* Every face any look needs, in one request. A look falls back to a system face until its own arrives. */
  function loadFonts() {
    const families = new Set(['IBM+Plex+Sans:wght@400;500;600', 'IBM+Plex+Mono:wght@400;500']);
    looks.forEach((look) => (look.fonts || []).forEach((f) => families.add(f)));
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://fonts.googleapis.com/css2?' + [...families].map((f) => 'family=' + f).join('&') + '&display=swap';
    document.head.append(link);
  }

  function buildCards() {
    looks.forEach((look, i) => {
      const card = el('button', 'card');
      card.type = 'button';
      card.dataset.id = look.id;
      card.setAttribute('aria-pressed', 'false');
      card.setAttribute('aria-label', look.name + ': ' + look.tagline);
      const phone = el('span', 'card-phone');
      const cap = el('span', 'card-cap');
      cap.append(el('span', 'card-no', two(i + 1)), el('span', 'card-name', look.name));
      card.append(phone, cap);
      card.addEventListener('click', () => {
        choose(look.id, 'wake');
        // On a narrow page the phone is above the cards: bring it back into view.
        if (window.matchMedia('(max-width: 760px)').matches) $('#stage-wrap').scrollIntoView({ behavior: moving ? 'smooth' : 'auto', block: 'start' });
      });
      cards.append(card);
      try {
        TL.create(phone, look.id, { still: true }).still('home');
      } catch (e) {
        console.error('look ' + look.id + ' could not be drawn', e);
      }
    });
  }

  function buildSceneButtons() {
    sceneRow.replaceChildren();
    for (const [label, scene] of moving ? MOVING : RESTING) {
      const button = el('button', '', label);
      button.type = 'button';
      button.dataset.scene = scene;
      button.addEventListener('click', () => show(scene));
      sceneRow.append(button);
    }
    motionNote.replaceChildren();
    if (prefersLess.matches) {
      motionNote.append(moving ? 'Playing with motion. ' : 'Your device asks for less motion, so these are stills. ');
      const toggle = el('button', 'copy', moving ? 'Show stills' : 'Play the animations');
      toggle.type = 'button';
      toggle.addEventListener('click', () => { moving = !moving; buildSceneButtons(); show(moving ? 'wake' : 'home'); });
      motionNote.append(toggle);
    }
  }

  /* Puts the chosen look on the phone, playing from a scene or resting on one. */
  function show(scene) {
    if (player) player.destroy();
    stage.replaceChildren();
    player = TL.create(stage, chosen.id, { still: !moving });
    if (moving) player.play(scene); else player.still(scene);
    sceneRow.querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(!moving && b.dataset.scene === scene)));
  }

  function choose(id, scene) {
    const look = TL.get(id) || looks[0];
    chosen = look;
    const at = looks.indexOf(look);
    document.documentElement.style.setProperty('--look-accent', look.palette.accent);
    document.documentElement.style.setProperty('--look-bg', look.palette.bg);
    $('#count').textContent = two(at + 1) + ' / ' + two(looks.length);
    $('#name').textContent = look.name;
    $('#tagline').textContent = look.tagline;
    $('#mood').textContent = look.mood || '';
    $('#moves').replaceChildren(...(look.motion || []).map((m) => el('li', '', m)));
    const swatches = $('#swatches');
    swatches.replaceChildren();
    for (const key of ['bg', 'fg', 'dim', 'accent', 'app', 'ok', 'err']) {
      const chip = el('i');
      chip.style.background = look.palette[key];
      chip.title = key + ' ' + look.palette[key];
      swatches.append(chip);
    }
    swatches.append(el('span', '', look.light ? 'light' : 'dark'));
    $('#copy-done').textContent = '';
    cards.querySelectorAll('.card').forEach((c) => c.setAttribute('aria-pressed', String(c.dataset.id === look.id)));
    show(scene || (moving ? 'wake' : 'home'));
    // The address follows the look, so a link to the page can point at one.
    if (location.hash.slice(1) !== look.id) {
      try { location.hash = look.id; } catch (e) { /* a frame that will not have it */ }
    }
  }

  function step(by) {
    const at = looks.indexOf(chosen);
    choose(looks[(at + by + looks.length) % looks.length].id, 'wake');
  }

  $('#prev').addEventListener('click', () => step(-1));
  $('#next').addEventListener('click', () => step(1));
  document.addEventListener('keydown', (e) => {
    if (e.altKey || e.ctrlKey || e.metaKey || /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)) return;
    if (e.key === 'ArrowLeft') step(-1);
    if (e.key === 'ArrowRight') step(1);
  });
  $('#copy').addEventListener('click', () => {
    const text = 'Look ' + two(looks.indexOf(chosen) + 1) + ', ' + chosen.name;
    const done = () => { $('#copy-done').textContent = 'Copied "' + text + '"'; };
    const failed = () => { $('#copy-done').textContent = 'Its name is "' + text + '"'; };
    try { navigator.clipboard.writeText(text).then(done, failed); } catch (e) { failed(); }
  });
  window.addEventListener('hashchange', () => {
    const id = location.hash.slice(1);
    if (TL.get(id) && id !== chosen.id) choose(id, 'wake');
  });

  $('#how-many').textContent = String(looks.length);
  loadFonts();
  buildCards();
  buildSceneButtons();
  // The first look starts on its lock page, so the page is never a dark phone at rest.
  choose(location.hash.slice(1), moving ? 'lock' : 'home');
})();
