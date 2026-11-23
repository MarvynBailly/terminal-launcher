"""Puts the gallery together from src/ into single files with everything inline.

  python tools/build.py

Writes index.html (opens in any browser) and artifact.html (the same page without the document
shell, for publishing where the host supplies one). A look is included once both of its files
exist in src/skins/.
"""
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
SRC = HERE / "src"
# Stock first, as the thing to compare with. After that, light and dark looks are mixed.
ORDER = ["stock", "phosphor", "aurora", "teletype", "neon", "eink", "hud", "sumi", "bbs", "brutal", "blueprint", "dotmatrix"]

BODY = """<title>Terminal Launcher Looks</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
{css}
</style>
<main class="page">
  <header class="head">
    <div class="eyebrow">Terminal Launcher</div>
    <h1>Pick a look</h1>
    <p><span id="how-many">{count}</span> looks for the launcher, each playing the same demo: the phone wakes, unlocks, runs a few commands, opens an app and locks. The words never change, only how they are drawn and how they move.</p>
  </header>
  <div class="show">
    <section class="stage" id="stage-wrap" aria-label="The chosen look, playing">
      <div class="device"><div class="device-screen" id="stage"></div></div>
      <div class="scenes" id="scenes" role="group" aria-label="Jump to a part of the demo"></div>
      <p class="motion-note" id="motion-note"></p>
    </section>
    <div class="side">
      <section class="about" aria-live="polite">
        <div class="about-top"><span class="count" id="count"></span><h2 id="name"></h2></div>
        <p class="tagline" id="tagline"></p>
        <p class="mood" id="mood"></p>
        <div class="label">How it moves</div>
        <ul class="moves" id="moves"></ul>
        <div class="swatches" id="swatches" aria-label="Its colours"></div>
        <div class="row">
          <div class="step"><button type="button" id="prev">&larr; prev</button><button type="button" id="next">next &rarr;</button></div>
          <button type="button" class="copy" id="copy">Copy its name</button>
          <span class="copy-done" id="copy-done" role="status"></span>
        </div>
      </section>
      <section class="picker">
        <div class="label">All looks</div>
        <div class="cards" id="cards"></div>
      </section>
    </div>
  </div>
</main>
{scripts}
"""

SHELL = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
</head>
<body>
{body}</body>
</html>
"""


def read(path):
    return path.read_text(encoding="utf-8")


def script(code):
    # The closing tag inside a string would end the script early.
    return "<script>\n" + code.replace("</script", "<\\/script") + "\n</script>"


def main():
    skins = SRC / "skins"
    have = sorted(p.stem for p in skins.glob("*.js") if (skins / (p.stem + ".css")).exists())
    ids = [i for i in ORDER if i in have] + [i for i in have if i not in ORDER]
    css = [read(SRC / "base.css")] + [read(skins / f"{i}.css") for i in ids] + [read(SRC / "gallery.css")]
    scripts = [script(read(SRC / "engine.js"))] + [script(read(skins / f"{i}.js")) for i in ids] + [script(read(SRC / "gallery.js"))]
    body = BODY.format(css="\n".join(css).replace("</style", "<\\/style"), count=len(ids), scripts="\n".join(scripts))
    (HERE / "artifact.html").write_text(body, encoding="utf-8", newline="\n")
    (HERE / "index.html").write_text(SHELL.format(body=body), encoding="utf-8", newline="\n")
    size = len(body.encode("utf-8")) / 1024
    print(f"{len(ids)} looks: {' '.join(ids)}")
    print(f"index.html and artifact.html, {size:.0f} KB each")


if __name__ == "__main__":
    main()
