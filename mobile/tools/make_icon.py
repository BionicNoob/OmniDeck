#!/usr/bin/env python3
"""Draws the launcher icons (legacy + adaptive background/foreground) into res/.

The Cyber "Mainframe HUD" look from the web app: a deep-navy glass tile
(#0a1622 → #050a12) with a faint hairline grid, a sky-cyan (#38BDF8)
arc-reactor emblem (hairline outer ring, four segments, inner ring, glowing
core) and corner brackets. It matches the LOGO the app draws in its top bar.
Needs: pip install pillow
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter

CYAN = (56, 189, 248)
CORE = (186, 230, 253)
NAVY_IN = (10, 22, 34)
NAVY_MID = (5, 10, 18)
NAVY_OUT = (3, 5, 9)

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "res")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SS = 4  # supersampling


def rgba(c, a):
    return (c[0], c[1], c[2], int(255 * a))


def backdrop(n, grid=True):
    """Radial navy gradient centred a little above the middle + hairline grid."""
    img = Image.new("RGBA", (n, n))
    px = img.load()
    cx, cy = n / 2, n * 0.42
    rmax = math.hypot(n / 2, n * 0.58)
    for y in range(n):
        for x in range(n):
            t = min(1.0, math.hypot(x - cx, y - cy) / rmax)
            if t < 0.55:
                k = t / 0.55
                c = [NAVY_IN[i] + (NAVY_MID[i] - NAVY_IN[i]) * k for i in range(3)]
            else:
                k = (t - 0.55) / 0.45
                c = [NAVY_MID[i] + (NAVY_OUT[i] - NAVY_MID[i]) * k for i in range(3)]
            px[x, y] = (int(c[0]), int(c[1]), int(c[2]), 255)
    if grid:
        g = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        d = ImageDraw.Draw(g)
        step = n / 9
        w = max(1, int(n / 400))
        for i in range(1, 9):
            d.line([(i * step, 0), (i * step, n)], fill=rgba(CYAN, 0.05), width=w)
            d.line([(0, i * step), (n, i * step)], fill=rgba(CYAN, 0.05), width=w)
        # Soft bloom off the top edge.
        b = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        ImageDraw.Draw(b).ellipse([-n * 0.2, -n * 0.75, n * 1.2, n * 0.35], fill=rgba(CYAN, 0.10))
        b = b.filter(ImageFilter.GaussianBlur(n * 0.12))
        img = Image.alpha_composite(img, g)
        img = Image.alpha_composite(img, b)
    return img


def emblem(n, cx, cy, r):
    """Arc-reactor emblem of outer radius r, with glow."""
    layer = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    def ring(rad, width, color):
        d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], outline=color, width=max(1, int(width)))

    def arc(rad, start, sweep, width, color):
        d.arc([cx - rad, cy - rad, cx + rad, cy + rad], start, start + sweep, fill=color, width=max(1, int(width)))

    # Outer hairline + fine ticks.
    ring(r, r * 0.03, rgba(CYAN, 0.55))
    for k in range(48):
        a = math.radians(k * 7.5)
        long = k % 6 == 0
        r1 = r * (0.86 if long else 0.91)
        d.line([cx + r1 * math.cos(a), cy + r1 * math.sin(a), cx + r * 0.95 * math.cos(a), cy + r * 0.95 * math.sin(a)],
               fill=rgba(CYAN, 0.75 if long else 0.35), width=max(1, int(r * (0.028 if long else 0.018))))
    # Four segments with gaps on the diagonals.
    for start in (-38, 52, 142, 232):
        arc(r * 0.70, start, 76, r * 0.12, rgba(CYAN, 1.0))
    # Inner ring.
    ring(r * 0.46, r * 0.035, rgba(CYAN, 0.8))

    glow = layer.filter(ImageFilter.GaussianBlur(r * 0.08))
    out = Image.alpha_composite(glow, layer)
    # Core with a soft halo.
    halo = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(halo).ellipse([cx - r * 0.34, cy - r * 0.34, cx + r * 0.34, cy + r * 0.34], fill=rgba(CYAN, 0.55))
    halo = halo.filter(ImageFilter.GaussianBlur(r * 0.12))
    out = Image.alpha_composite(out, halo)
    ImageDraw.Draw(out).ellipse([cx - r * 0.22, cy - r * 0.22, cx + r * 0.22, cy + r * 0.22], fill=rgba(CORE, 1.0))
    return out


def brackets(n, inset, length, width, color):
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    a, b = inset, n - inset
    d.line([(a, a), (a + length, a)], fill=color, width=width)
    d.line([(a, a), (a, a + length)], fill=color, width=width)
    d.line([(b, b), (b - length, b)], fill=color, width=width)
    d.line([(b, b), (b, b - length)], fill=color, width=width)
    return img


def rounded_mask(n, radius, margin):
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).rounded_rectangle([margin, margin, n - margin, n - margin], radius=radius, fill=255)
    return m


def legacy(px):
    n = px * SS
    margin = n * 0.045
    tile = backdrop(n)
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    img.paste(tile, (0, 0), rounded_mask(n, n * 0.2, margin))
    edge = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle([margin, margin, n - margin, n - margin], radius=n * 0.2,
                                           outline=rgba(CYAN, 0.38), width=max(1, int(n * 0.012)))
    img = Image.alpha_composite(img, edge)
    img = Image.alpha_composite(img, brackets(n, n * 0.16, n * 0.1, max(1, int(n * 0.014)), rgba(CYAN, 0.6)))
    img = Image.alpha_composite(img, emblem(n, n / 2, n / 2, n * 0.29))
    return img.resize((px, px), Image.LANCZOS)


def adaptive_bg(px):
    n = px * SS
    img = backdrop(n)
    return img.resize((px, px), Image.LANCZOS)


def adaptive_fg(px):
    # 108dp canvas; the launcher mask shows the middle 72dp, keep art in the 66dp safe zone.
    n = px * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    img = Image.alpha_composite(img, brackets(n, n * 0.29, n * 0.055, max(1, int(n * 0.009)), rgba(CYAN, 0.6)))
    img = Image.alpha_composite(img, emblem(n, n / 2, n / 2, n * 0.2))
    return img.resize((px, px), Image.LANCZOS)


def main():
    for name, scale in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        legacy(int(48 * scale)).save(os.path.join(folder, "ic_launcher.png"), optimize=True)
        adaptive_fg(int(108 * scale)).save(os.path.join(folder, "ic_launcher_fg.png"), optimize=True)
        adaptive_bg(int(108 * scale)).save(os.path.join(folder, "ic_launcher_bg.png"), optimize=True)
    legacy(512).save(os.path.join(HERE, "icon-preview.png"))
    print("icons written to", os.path.abspath(RES))


if __name__ == "__main__":
    main()
