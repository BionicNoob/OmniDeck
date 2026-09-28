#!/usr/bin/env python3
"""Draws the launcher icons (legacy + adaptive foreground) into res/.

A neon-red segmented ring with a cyan core on OMNI-DECK's near-black
background — the same emblem the app draws in its top bar.
Needs: pip install pillow
"""
import os

from PIL import Image, ImageDraw, ImageFilter

RED = (255, 0, 60, 255)
CYAN = (0, 240, 255, 255)
BG = (1, 1, 3, 255)
TILE = (5, 9, 15, 255)

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4  # supersampling


def emblem(draw, size, cx, cy, r, width, glow=False):
    """Four ring segments with gaps on the diagonals + a core dot."""
    box = [cx - r, cy - r, cx + r, cy + r]
    for start in (-35, 55, 145, 235):
        draw.arc(box, start, start + 70, fill=RED, width=int(width))
    core = r * 0.32
    if not glow:
        draw.ellipse([cx - core, cy - core, cx + core, cy + core], fill=CYAN)
        # Small cyan tick marks in the ring's gaps.
        import math
        for ang in (45, 135, 225, 315):
            a = math.radians(ang)
            r1, r2 = r - width * 0.2, r + width * 0.9
            draw.line([cx + r1 * math.cos(a), cy + r1 * math.sin(a), cx + r2 * math.cos(a), cy + r2 * math.sin(a)],
                      fill=CYAN, width=max(1, int(width * 0.35)))


def render(px, legacy):
    n = px * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if legacy:
        # Chamfered tile, like the web app's clip-path corners.
        c = n * 0.2
        m = n * 0.04
        poly = [(m + c, m), (n - m, m), (n - m, n - m - c), (n - m - c, n - m), (m, n - m), (m, m + c)]
        d = ImageDraw.Draw(img)
        d.polygon(poly, fill=TILE, outline=RED, width=max(1, int(n * 0.022)))
        cx = cy = n / 2
        r, w = n * 0.25, n * 0.07
    else:
        # Adaptive foreground: 108dp canvas, keep inside the 66dp safe zone.
        cx = cy = n / 2
        r, w = n * 0.19, n * 0.052
    # Glow layer.
    glow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    emblem(ImageDraw.Draw(glow), n, cx, cy, r, w * 1.6, glow=True)
    glow = glow.filter(ImageFilter.GaussianBlur(n * 0.03))
    img = Image.alpha_composite(img, glow)
    emblem(ImageDraw.Draw(img), n, cx, cy, r, w)
    return img.resize((px, px), Image.LANCZOS)


def main():
    for name, scale in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        legacy = Image.new("RGBA", (int(48 * scale), int(48 * scale)), (0, 0, 0, 0))
        legacy = Image.alpha_composite(legacy, render(int(48 * scale), True))
        legacy.save(os.path.join(folder, "ic_launcher.png"), optimize=True)
        fg = render(int(108 * scale), False)
        fg.save(os.path.join(folder, "ic_launcher_fg.png"), optimize=True)
    # A large preview for the README.
    preview = Image.new("RGBA", (512, 512), BG)
    preview = Image.alpha_composite(preview, render(512, True))
    preview.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon-preview.png"))
    print("icons written to", os.path.abspath(RES))


if __name__ == "__main__":
    main()
