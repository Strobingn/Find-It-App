"""Generate the ThermalClaude launcher icon: rising thermal-wind curves over a ridge line.

Outputs (under app/src/main/res):
  drawable-nodpi/ic_thermalclaude_foreground.png   adaptive-icon foreground (432px, 108dp canvas)
  mipmap-*/ic_thermalclaude.png                    legacy square icons for API 24-25
  mipmap-*/ic_thermalclaude_round.png              legacy round icons for API 24-25
Run: python scripts/gen_thermalclaude_icon.py
"""
from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
BACKGROUND = (18, 18, 20, 255)
WARM_LOW = (255, 196, 64)   # amber at the base of the plume
WARM_HIGH = (255, 72, 48)   # red-orange at the top
RIDGE = (236, 236, 236, 255)
SCALE = 4                   # supersample, then downscale for clean edges


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw_mark(size: int) -> Image.Image:
    """The mark on a transparent canvas; content sits inside the central ~60% safe zone."""
    s = size * SCALE
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Ridge line: a two-peak mountain silhouette along the lower third.
    w = s * 0.045
    base_y = s * 0.70
    ridge = [
        (s * 0.20, base_y),
        (s * 0.38, s * 0.50),
        (s * 0.48, s * 0.60),
        (s * 0.62, s * 0.42),
        (s * 0.80, base_y),
    ]
    d.line(ridge, fill=RIDGE, width=int(w), joint="curve")
    for x, y in (ridge[0], ridge[-1]):
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=RIDGE)

    # Three thermal curves rising off the right-hand peak, warming as they climb.
    for i, (amp, phase, top) in enumerate([(0.030, 0.0, 0.27), (0.030, 0.0, 0.22), (0.030, 0.0, 0.27)]):
        cx = s * (0.50 + i * 0.09)
        bottom = s * (0.40 + abs(i - 1) * 0.03)
        pts = []
        steps = 80
        for k in range(steps + 1):
            t = k / steps
            y = bottom + (s * top - bottom) * t
            x = cx + math.sin(t * math.pi * 2.0 + phase) * s * amp
            pts.append((x, y))
        lw = s * 0.034
        for k in range(steps):
            color = lerp(WARM_LOW, WARM_HIGH, k / steps) + (255,)
            d.line([pts[k], pts[k + 1]], fill=color, width=int(lw))
            x, y = pts[k]
            d.ellipse([x - lw / 2, y - lw / 2, x + lw / 2, y + lw / 2], fill=color)

    # Small sun: the heat source.
    r = s * 0.050
    sx, sy = s * 0.33, s * 0.30
    d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=WARM_LOW + (255,))

    return img.resize((size, size), Image.LANCZOS)


def with_background(mark: Image.Image, round_shape: bool) -> Image.Image:
    size = mark.width
    s = size * SCALE
    bg = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    if round_shape:
        d.ellipse([0, 0, s - 1, s - 1], fill=BACKGROUND)
    else:
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.22), fill=BACKGROUND)
    bg = bg.resize((size, size), Image.LANCZOS)
    # Legacy icons have no safe-zone crop, so scale the mark up a little.
    inner = mark.resize((int(size * 1.15), int(size * 1.15)), Image.LANCZOS)
    off = (size - inner.width) // 2
    bg.alpha_composite(inner.crop((-off, -off, -off + size, -off + size)) if off < 0 else inner, (max(off, 0), max(off, 0)))
    return bg


def main() -> None:
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    draw_mark(432).save(os.path.join(nodpi, "ic_thermalclaude_foreground.png"))

    for density, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        mark = draw_mark(px)
        with_background(mark, round_shape=False).save(os.path.join(folder, "ic_thermalclaude.png"))
        with_background(mark, round_shape=True).save(os.path.join(folder, "ic_thermalclaude_round.png"))

    # Large preview for review / store listing.
    preview = with_background(draw_mark(512), round_shape=False)
    preview.save(os.path.join(os.path.dirname(__file__), "..", "thermalclaude-icon-512.png"))


if __name__ == "__main__":
    main()
