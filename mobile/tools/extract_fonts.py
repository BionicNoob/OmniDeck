#!/usr/bin/env python3
"""Pulls OMNI-DECK's embedded fonts out of OllamaChat.html into TTFs for the app.

The web app embeds Orbitron and Share Tech Mono (both SIL Open Font License)
as base64 WOFF2. Android can't load WOFF2, so this decodes them, converts to
TrueType and pins variable fonts to a single weight.

Usage: python3 tools/extract_fonts.py path/to/OllamaChat.html
Needs: pip install fonttools brotli
"""
import base64
import io
import os
import re
import sys

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

WANTED = {
    # family -> list of (output file, weight for variable fonts, new family name)
    # Converted/subsetted OFL fonts are "Modified Versions" and may not keep
    # their Reserved Font Names, so the family is renamed; the copyright
    # notice (name ID 0) is kept as the license requires.
    "Orbitron": [("title.ttf", 700, "OmniDeck Title"), ("title-medium.ttf", 600, "OmniDeck Title Medium")],
    "Share Tech Mono": [("mono.ttf", 400, "OmniDeck Mono")],
    "Inter": [("inter-regular.ttf", 400, "OmniDeck Sans"), ("inter-medium.ttf", 500, "OmniDeck Sans Medium"),
              ("inter-semibold.ttf", 600, "OmniDeck Sans SemiBold"), ("inter-bold.ttf", 700, "OmniDeck Sans Bold")],
}


def rename(font, family):
    ps = family.replace(" ", "") + "-Regular"
    for rec in list(font["name"].names):
        if rec.nameID in (1, 16):
            rec.string = family
        elif rec.nameID in (4,):
            rec.string = family + " Regular"
        elif rec.nameID in (6,):
            rec.string = ps
        elif rec.nameID in (3,):
            rec.string = ps + ";converted"
        elif rec.nameID in (17,):
            rec.string = "Regular"


def main():
    html_path = sys.argv[1] if len(sys.argv) > 1 else "../OllamaChat.html"
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "fonts")
    os.makedirs(out_dir, exist_ok=True)
    html = open(html_path, encoding="utf-8").read()
    faces = re.findall(r"@font-face\s*\{(.*?)\}", html, re.S)
    done = set()
    for face in faces:
        fam = re.search(r"font-family:\s*'([^']+)'", face)
        src = re.search(r"url\(data:font/woff2;base64,([A-Za-z0-9+/=]+)\)", face)
        if not fam or not src or fam.group(1) not in WANTED or fam.group(1) in done:
            continue
        for name, weight, family in WANTED[fam.group(1)]:
            font = TTFont(io.BytesIO(base64.b64decode(src.group(1))))
            if "fvar" in font:
                axes = {a.axisTag: a for a in font["fvar"].axes}
                pins = {}
                if "wght" in axes:
                    w = axes["wght"]
                    pins["wght"] = max(w.minValue, min(w.maxValue, weight))
                font = instancer.instantiateVariableFont(font, pins)
            font.flavor = None
            if "OS/2" in font:
                font["OS/2"].usWeightClass = weight
            rename(font, family)
            path = os.path.join(out_dir, name)
            font.save(path)
            print("wrote", path, os.path.getsize(path), "bytes")
        done.add(fam.group(1))
    missing = set(WANTED) - done
    if missing:
        sys.exit("fonts not found in the HTML: " + ", ".join(sorted(missing)))


if __name__ == "__main__":
    main()
