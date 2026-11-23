# Writing a look

A look is a skin for one scripted demo of Terminal Launcher, an Android launcher that is a terminal.
Every look plays the same demo with the same words: the phone wakes to a lock page, a password is
typed, the login summary prints, a few commands run, an app is launched, the phone locks. A look
decides how all of that is drawn and how it moves. The owner will compare the looks side by side on
one page and pick one to rebuild in the real app (Kotlin, Jetpack Compose).

The engine is `src/engine.js`, the shared structure is `src/base.css`, and the reference look is
`src/skins/stock.css` + `src/skins/stock.js`. Read all four before you start. They are short.

## What you write

Exactly two files, and nothing else in the repo:

- `src/skins/<id>.css`
- `src/skins/<id>.js`

Do not edit the engine, `base.css`, `dev.html`, the tools or another look. If the engine stands in
your way, work around it in your own two files and say so in your report.

## The page the engine builds

```
div.tl[data-look=<id>][data-scene=…]         the phone screen, 9 : 19.5
  div.tl-bg                                   layer behind the text: yours (backgrounds, canvases)
  div.tl-screen                               the terminal
    div.tl-status > span.tl-status-l, span.tl-status-r
    div.tl-body
      div.tl-lines                            the scrollback; the engine scrolls it to its end
        div.tl-line.tl-text                   spans: plain, .s-dim .s-accent .s-app .s-ok .s-err
                                              .is-blank = empty line, .is-tap = tappable (taller)
        div.tl-line.tl-echo                   span.tl-ps + span.tl-typed: a command that was typed
        div.tl-line.tl-gauge[.is-alert]       span.tl-gauge-label, span.tl-gauge-bar > 14 × i[.on], span.tl-gauge-note
                                              --pct (0-100) and --filled (0-14) on the line, --i on each cell
        div.tl-line.tl-clock[data-time]       span.tl-clock-grid > 85 × i[.on]  (17 columns × 5 rows; --i --col --row)
        div.tl-line.tl-grid                   18 × span.tl-app (--i --col --row), three columns filled downwards
        div.tl-line.tl-note                   span.tl-note-app, span.tl-note-text: a notification
        div.tl-line.tl-launch                 span.tl-launch-text, span.tl-spin
      div.tl-prompt[data-kind=login|shell]    span.tl-ps, span.tl-input, span.tl-cursor
    div.tl-chips > span.tl-chip[.is-app|.is-cmd|.is-key]
  div.tl-appview                              the launched app. It is another app: leave its inside alone
  div.tl-fx                                   layer over everything: yours (scanlines, flashes, wipes)
```

Lines with a role carry `data-role`: `lock-title`, `lock-date`, `lock-info` on the lock page,
`granted` on the default "access granted" line, `wait` on "asking open-meteo.com".

`data-scene` on the root is one of `off`, `boot`, `lock`, `granted`, `home`, `launch`, `app`,
`poweroff`. Use it in selectors. In `off` the base hides `.tl-screen`; you may override that.

A line that has just been printed carries `.tl-in` (never in a still). `base.css` animates
`.tl-in` lines, gauge cells and grid cells. Override those rules to change how things arrive.

## The stylesheet

- **Scope every selector** with `.tl[data-look="<id>"]`. Prefix every `@keyframes` name with `<id>-`.
- **Set the palette and type** on the root selector: `--bg --fg --dim --line --accent --app --ok
  --err --font --weight --lh --pad`. `base.css` draws everything from these, so a look that sets
  only these already works. Then restyle whatever you like.
- **Units:** inside `.tl-screen` use `em` (1em is 3.6% of the phone's width, about 14px on the
  bench). In the layers use `cqw` / `cqh`. No `px`, except hairlines as `max(1px, 0.07em)`. The
  same look is drawn 150 px wide on the picker's cards and must be the same picture, smaller.
- **At rest everything is visible.** Animations run from an offset state to the resting state
  (`animation-fill-mode: backwards`, keyframes with only a `from`). Never make something visible
  only through an animation's end state: a still switches all animation off (`.tl-still`).
- **Nothing from the network** except Google Fonts faces named in your `fonts` list. Small inline
  SVG as `data:` URIs is fine for textures (keep each under 4 KB). No images, no sound.
- Keep the 42 or so columns of text fitting: if you change the face or the size, check that no
  line of the demo wraps or runs off the screen, at rest and while scrolling.
- Both files together should stay under about 40 KB.

## The script

```js
TL.register({
  id: 'phosphor',                       // lower-case letters, the file name
  name: 'Phosphor',                     // one or two words
  tagline: 'A green tube from 1983',    // under 45 characters, no full stop
  mood: 'One sentence on what it is like to use.',
  motion: ['three short lines', 'on its signature movements', 'as the owner will see them'],
  palette: { bg: '#…', fg: '#…', dim: '#…', accent: '#…', app: '#…', ok: '#…', err: '#…' },
  light: false,                         // true if the look is dark text on a light ground
  fonts: ['VT323'],                     // Google Fonts css2 "family=" values; [] for none
  hooks: { … },                         // all optional
});
```

Every hook gets `ctx` first. Hooks marked async may return a promise; the demo waits for it.

| hook | when | what it is for |
| --- | --- | --- |
| `mount(ctx)` | once, when the phone is built (stills too) | Put lasting things in `ctx.bg` / `ctx.fx`: canvases, scanlines, frames. May return a cleanup function. What you add here stays; what is added to the layers later is removed after each transition. |
| `clock(ctx, node, time)` | when the lock clock is drawn (stills too) | Fill `node` with your own clock for `"11:06"` instead of the 17×5 cells. |
| async `powerOn(ctx)` | scene `boot` | The screen comes on. The lock header (title, clock, date, info) is already in `ctx.lines`. Resolve when the screen is on; the boot log then prints above the header. |
| `line(ctx, node, desc)` | each newly printed line | Per-line entrances that CSS cannot do. `desc.t` is `text`, `gauge`, `clock`, `grid`, `note` or `launch`. |
| `key(ctx, ch)` | each character typed at the prompt | Per-keystroke effects. |
| async `granted(ctx)` | scene `granted`: the password was right | The lock page is still on screen. Take it away. When you resolve, the engine empties the page and prints the summary. `ctx.say('access granted', 'ok')` adds a line if you want the words. |
| async `notify(ctx, node)` | a notification line was just added | |
| async `launch(ctx, line, name)` | scene `launch` | `line` is the "opening spotify" line. Resolve when the screen is ready for the app to take over. The engine then shows `ctx.appview` over everything. |
| async `back(ctx)` | scene `app`, the app is closing | `ctx.appview` covers the terminal. Take it away. |
| async `powerOff(ctx)` | scene `poweroff` | Resolve when the screen is dark. |

With no hook the engine uses `TL.defaults.powerOn / powerOff / granted / launch / back`, which
are how the app moves today. You may call them from your own hooks.

No hook runs in a still, or while the engine lays out the scenes that come before the one being
played. So anything the look needs at rest must come from CSS or from `mount`.

`ctx` holds:

- `root, bg, screen, fx, lines, prompt, status, chips, appview` — the elements above
- `still` — true on a still: draw once, start nothing
- `sleep(ms)` — a promise; **use this, never `setTimeout`**
- `animate(node, keyframes, options)` — Web Animations as a promise; **use this for scripted
  movement**. For one you do not wait for, add `.catch(() => {})`
- `onFrame(fn)` — calls `fn(ms)` every frame; **use this, never `requestAnimationFrame`**. On a
  still it runs once, so a canvas has its picture
- `canvas(layer)` — a canvas filling `ctx.bg` or `ctx.fx`: returns `{ canvas, g, w, h, dpr }`,
  where `w` and `h` (device pixels) follow the phone's size, so read them each frame
- `rect(node)` — where a node is, in percent of the phone: `{ x, y, w, h }`
- `say(text, style)` — adds a line; `scramble(node, text, ms)` — resolves text out of noise
- `el(tag, className, text)` — makes an element

`ctx.sleep` and `ctx.animate` reject when the demo is cancelled (the owner switched looks). Let
that rejection pass through: do not catch it and carry on. After every transition the engine
cancels scripted animations, clears inline styles on the phone's own parts and empties what you
added to the layers, so `fill: 'forwards'` is safe.

`TL.util` has `clockCells(time)` (85 booleans), `scrambled(text, p)` and `el`.

## What a look may and may not change

May: palette, faces, sizes, spacing, where the status line and chips sit, how the clock, gauges,
app grid, notification, prompt and cursor are drawn, backgrounds, overlays, every transition.
Decoration that is not words (rules, ticks, brackets, frames, numerals on a scale) is welcome.

May not: the words of the demo, the order of things, the inside of the app view, or add words of
its own that could be read as the launcher saying something.

The look will be rebuilt in Compose, which has canvas drawing, gradients, blur, blend modes,
per-glyph layout and runtime shaders. Anything in that family is fair. Aim for something the
owner would enjoy on a phone they unlock fifty times a day: striking on the first unlock, not
tiring on the fiftieth. It must stay readable.

It must hold 60 frames a second on a laptop with twelve stills of other looks on the same page:
keep per-frame work small, and do not animate large blurs or filters every frame.

## Checking your work

From `demo/looks/`, with the environment variable `LOOKS_SHOTS` set as your brief says:

```
python tools/snap.py <id> stills              lock, home and use at rest, side by side
python tools/snap.py <id> small               home, 150 px wide, as on the picker's cards
python tools/snap.py <id> film wake 2.5       a contact sheet of a transition, a frame every 0.2 s
python tools/snap.py <id> film granted 3.2
python tools/snap.py <id> film use 6 0.4
python tools/snap.py <id> film launch 4.8 0.3
python tools/snap.py <id> film off 3
```

Each prints the PNG it wrote and any console error from the page. Open the PNG and look at it.
You are done when:

- the three stills are clean: nothing overlaps, wraps badly, is cut off or unreadable
- the small still is recognisably this look and nothing in it is broken
- each film shows the transition you meant, start to finish, and ends in a clean page
- the page logs no errors
- at a glance, nobody could mistake it for the stock look or for a plain recolour of it
