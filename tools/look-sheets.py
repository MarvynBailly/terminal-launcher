"""Tiles the pictures LookShots took of one look into two sheets for looking at.

  python tools/look-sheets.py <shots dir> <look> [<out dir>]

Writes <look>-rest.png (lock page, summary, in use) and <look>-motion.png (one row a movement:
waking, a right password, going dark, an app opening, lines arriving, and the look at rest a few
seconds apart if it moves). Prints the two paths.
"""
import sys
from pathlib import Path

from PIL import Image

GROUPS = ["wake", "granted", "off", "launch", "lines", "rest-moving"]


def strip(frames, width):
    scaled = [f.resize((width, f.height * width // f.width), Image.LANCZOS) for f in frames]
    sheet = Image.new("RGB", (sum(s.width for s in scaled) + 6 * (len(scaled) - 1), scaled[0].height), "#30333a")
    x = 0
    for s in scaled:
        sheet.paste(s, (x, 0))
        x += s.width + 6
    return sheet


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src, look = Path(sys.argv[1]), sys.argv[2]
    out = Path(sys.argv[3]) if len(sys.argv) > 3 else src
    out.mkdir(parents=True, exist_ok=True)

    def frames(group):
        return [Image.open(p).convert("RGB") for p in sorted(src.glob(f"{look}__{group}__*.png"))]

    rest = frames("rest")
    if not rest:
        print(f"no pictures of {look} in {src}")
        return 1
    a = out / f"{look}-rest.png"
    strip(rest, 380).save(a)

    rows = [strip(f, 190) for f in (frames(g) for g in GROUPS) if f]
    print(a)
    if not rows:
        return 0
    sheet = Image.new("RGB", (max(r.width for r in rows), sum(r.height for r in rows) + 8 * (len(rows) - 1)), "#30333a")
    y = 0
    for r in rows:
        sheet.paste(r, (0, y))
        y += r.height + 8
    b = out / f"{look}-motion.png"
    sheet.save(b)
    print(b)
    return 0


if __name__ == "__main__":
    sys.exit(main())
