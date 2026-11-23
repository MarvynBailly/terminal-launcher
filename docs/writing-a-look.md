# Writing a look

A look decides how the terminal is drawn and how it moves. The shell decides what is on screen and
a look never changes that. `look <name>` switches between them.

A look is one Kotlin object in `app/src/main/java/com/terminallauncher/ui/looks/`, a subclass of
`Look`, listed in `Looks.kt` and named in `Shell.LOOKS`. Read these first, they are short:

- `Look.kt`: the class, every member with its default (the defaults are the stock terminal), and
  `Motion`, the shape of a whole-page movement.
- `Kit.kt`: small tools the looks share: `dice`, `between`, easings, `tiled` and `grain` brushes,
  `scanlines`, `vignette`, drawing words on a canvas.
- `Phosphor.kt`: a complete look, to copy the shape of.

## The rules

- **No state.** Everything a look draws is a plain function of what it is handed: a time, a
  progress, a size. That is what lets any moment be drawn off the phone and looked at. Cache only
  what is costly to make (a texture), with `tiled`/`grain` or a `lazy`.
- **Random means `dice(i, salt)`**, never `Math.random()`: the same input must draw the same picture.
- **At rest it is exactly the resting picture.** `arriving` at progress 1 and a `Motion` past its
  end must leave nothing behind.
- **Cheap per frame.** `background` and `overlay` are drawn every frame while `moves` is true, on
  a phone. Tiled brushes, gradients and a few hundred primitives are fine. No per-pixel loops, no
  bitmaps made per frame. Set `moves` only if something really changes at rest, and keep that
  movement slow and faint: this is a screen someone unlocks fifty times a day.
- **Android 8 and up.** No runtime shaders, no blur or render effects. Text glow is
  `TextStyle(shadow = Shadow(...))`.
- **Sizes in dp and sp**, or from `env.cell` (one character, in pixels). Never raw pixel counts.
- **Readable first.** Body text needs strong contrast against whatever the background draws.

## What the app is, where it differs from the web demo

The looks were first drawn as web skins in `demo/looks/src/skins/` over a scripted demo. The app is
the real thing, and some things differ:

- The keyboard covers the lower two fifths of the screen whenever there is a prompt. The page a
  person sees is the part above it. `env.keyboard` is its height in pixels.
- Text is whatever the shell prints: app names of any length, wrapped lines, tables that line up
  only in a monospaced face. Use a monospaced face for the terminal's text and set `advance` to
  its real width (it decides how many columns the shell is told). A proportional face is fine
  for the clock.
- There is no way back from an app that a look can draw. A launch has about 420 ms before the app
  fades in over it.
- Typing happens in a real text field. A look styles the cursor, not each keystroke.
- A row decorates itself: a frame around several rows is only possible where the row is told its
  place (`appRow`'s `first` and `last`).

## The members

Type: `font`, `fontSize`, `lineHeight`, `advance`, `gutter`, `type()` (shape the base style),
`span()` (how each of the shell's styles is drawn: color, weight, underline, reverse video).

Layers: `background()` behind everything, `overlay()` over everything, `moves`.

Parts: `frame()` (room around the whole page), `status()` + `statusStyle()` + `statusText()`,
`Clock()` (the lock page's clock, a composable), `lockStyle()` + `lockText()` (the three lines of
words around it), `gauge()` + `gaugeSize` (a bar, drawn on a canvas from `shown`, 0 to 1),
`tapRow()`, `echoRow()`, `appRow()`, `appCell()`, `note()`, `chip()` + `chipStyle()`,
`promptColor()`, `cursor()`.

Movement: `arriving()` + `lineMs` (how a printed line arrives), `powerOn` (the screen coming on),
`powerOff` (going dark after `lock`), `granted` (a right password: the lock page leaves, and
`Clock`'s `leaving` lets the clock take part), `grantedWords`, `launch()` + `launchMs`.

A `Motion` has a length, a `shape()` (scale, move, fade the page as a whole) and a `draw()`
(`drawContent()` draws the page; call it inside clips and translations, more than once, or not at
all; draw over it afterwards).

Fonts are in `app/src/main/res/font/`. Their advances, as a share of the font size: IBM Plex Mono,
Geist Mono, Courier Prime and DM Mono 0.6; Space Mono 0.612; Share Tech Mono 0.54; VT323 0.4.

## Looking at it

`LookShots` (a test that only runs when asked) draws the real UI on the desktop: each look's lock
page, login summary and a page in use, and each movement held at several moments.

```bash
bash tools/shots.sh phosphor                 # everything, tiled into two sheets
bash tools/shots.sh phosphor rest            # only the pages at rest
bash tools/shots.sh phosphor wake,granted    # only some movements: wake granted off launch lines rest-moving
```

It prints the sheets it wrote: `shots/<look>-rest.png` and `shots/<look>-motion.png` (one row a
movement, time running left to right). Look at them. A look is done when the three pages are
clean and readable, nothing wraps that did not wrap in stock, and every movement reads as what it
is meant to be from the first frame to the last.
