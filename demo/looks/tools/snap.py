"""Pictures of one look, taken in headless Chrome, for checking it by eye.

  python tools/snap.py <look> stills
      The three scenes at rest, side by side: lock, home, use.

  python tools/snap.py <look> film <scene> [seconds] [every]
      Plays from a scene (wake, lock, granted, use, launch, off) and photographs it every `every`
      seconds (default 0.2) for `seconds` (default 3): a contact sheet, read left to right, top to bottom.

  python tools/snap.py <look> small
      The home scene on a phone 150 px wide, as it is drawn on the picker's cards, enlarged 2x.

Prints the path of the PNG it wrote. Console errors from the page are printed too.
"""
import io
import os
import sys
import time
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent.parent
OUT = Path(os.environ.get("LOOKS_SHOTS", HERE / ".shots"))
W, H = 390, 845


def page_url(look, **params):
    query = "&".join(f"{k}={v}" for k, v in {"look": look, **params}.items())
    return (HERE / "dev.html").as_uri() + "?" + query


def open_page(browser, url, width=W, height=H, scale=1):
    page = browser.new_page(viewport={"width": width, "height": height}, device_scale_factor=scale)
    page.on("console", lambda m: m.type in ("error", "warning") and print(f"  [page {m.type}] {m.text}"))
    page.on("pageerror", lambda e: print(f"  [page error] {e}"))
    page.goto(url)
    page.wait_for_function("window.__ready === true", timeout=20000)
    return page


def shot(page):
    return Image.open(io.BytesIO(page.screenshot())).convert("RGB")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    look, mode = sys.argv[1], sys.argv[2]
    OUT.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        if mode == "stills":
            frames = []
            for scene in ("lock", "home", "use"):
                page = open_page(browser, page_url(look, still=scene))
                page.wait_for_timeout(250)
                frames.append(shot(page))
                page.close()
            sheet = Image.new("RGB", (W * 3 + 20, H), "#23262b")
            for i, frame in enumerate(frames):
                sheet.paste(frame, (i * (W + 10), 0))
            out = OUT / f"{look}-stills.png"
        elif mode == "small":
            page = open_page(browser, page_url(look, still="home", w=150), width=150, height=325, scale=2)
            page.wait_for_timeout(250)
            sheet = shot(page)
            page.close()
            out = OUT / f"{look}-small.png"
        elif mode == "film":
            scene = sys.argv[3] if len(sys.argv) > 3 else "wake"
            seconds = float(sys.argv[4]) if len(sys.argv) > 4 else 3.0
            every = float(sys.argv[5]) if len(sys.argv) > 5 else 0.2
            page = open_page(browser, page_url(look, scene=scene))
            start = time.monotonic()
            frames = []
            while True:
                due = len(frames) * every
                now = time.monotonic() - start
                if due > seconds:
                    break
                if now < due:
                    page.wait_for_timeout(int((due - now) * 1000))
                frames.append((time.monotonic() - start, shot(page)))
            page.close()
            tw, th = W // 2, H // 2
            cols = min(8, len(frames))
            rows = (len(frames) + cols - 1) // cols
            sheet = Image.new("RGB", (cols * (tw + 6), rows * (th + 6)), "#23262b")
            for i, (_, frame) in enumerate(frames):
                sheet.paste(frame.resize((tw, th), Image.LANCZOS), ((i % cols) * (tw + 6), (i // cols) * (th + 6)))
            print("  frames at (s): " + " ".join(f"{t:.2f}" for t, _ in frames))
            out = OUT / f"{look}-film-{scene}.png"
        else:
            print(__doc__)
            return 2
        browser.close()
    sheet.save(out)
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
