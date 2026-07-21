"""Generate Lidar Metal Detector launcher icons + splash logo."""
from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")


def make_icon(size: int, path: str, rounded: bool = False) -> None:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    bg = (13, 14, 18, 255)
    if rounded:
        r = max(size // 5, 4)
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=bg)
    else:
        d.rectangle([0, 0, size - 1, size - 1], fill=bg)

    cx, cy = size / 2, size / 2
    step = max(size // 9, 4)
    for i in range(0, size + 1, step):
        d.line([(i, 0), (i, size)], fill=(30, 44, 53, 90), width=1)
        d.line([(0, i), (size, i)], fill=(30, 44, 53, 90), width=1)

    outer_r = size * 0.32
    d.ellipse(
        [cx - outer_r, cy - outer_r, cx + outer_r, cy + outer_r],
        outline=(255, 215, 0, 255),
        width=max(size // 18, 2),
    )
    inner_r = size * 0.17
    d.ellipse(
        [cx - inner_r, cy - inner_r, cx + inner_r, cy + inner_r],
        outline=(255, 215, 0, 230),
        width=max(size // 28, 2),
    )

    pts = [(cx, cy)]
    for a in range(-90, 1, 2):
        rad = math.radians(a)
        pts.append((cx + outer_r * math.cos(rad), cy + outer_r * math.sin(rad)))
    d.polygon(pts, fill=(0, 230, 118, 50))

    green = (0, 230, 118, 210)
    w = max(size // 40, 1)
    d.line([(size * 0.12, cy), (size * 0.88, cy)], fill=green, width=w)
    d.line([(cx, size * 0.12), (cx, size * 0.88)], fill=green, width=w)

    cr = size * 0.05
    d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=(255, 255, 255, 255))
    hx, hy = cx + outer_r * 0.45, cy - outer_r * 0.35
    hr = size * 0.04
    d.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=(255, 215, 0, 255))

    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print("wrote", path)


def main() -> None:
    dens = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, sz in dens.items():
        base = os.path.join(ROOT, folder)
        make_icon(sz, os.path.join(base, "ic_launcher.png"), rounded=False)
        make_icon(sz, os.path.join(base, "ic_launcher_round.png"), rounded=True)
        for name in ("ic_launcher", "ic_launcher_round"):
            png = os.path.join(base, f"{name}.png")
            webp = os.path.join(base, f"{name}.webp")
            Image.open(png).save(webp, "WEBP", quality=92)
            print("webp", webp)

    make_icon(
        512,
        os.path.join(ROOT, "drawable-nodpi", "splash_logo.png"),
        rounded=False,
    )
    print("done")


if __name__ == "__main__":
    main()
