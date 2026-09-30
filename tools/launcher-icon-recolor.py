#!/usr/bin/env python3
"""Re-tint the legacy (pre-Android 8) launcher PNGs to the fork palette.

The adaptive icon is pure XML (drawable/ic_launcher_background.xml +
ic_launcher_foreground.xml, whose gradient comes from the
anki_foreground_icon_color_* resValues). These bitmaps only cover API 24-25,
so they are recolored in place instead of redrawn: the gray plate becomes the
emerald -> blue-teal background gradient, the blue spark becomes the mint ->
teal one, and white/black details are left alone.

Run from the repository root after restoring the upstream bitmaps:
    git checkout -- AnkiDroid/src/main/res/mipmap-mdpi ...
    python tools/launcher-icon-recolor.py
"""

from pathlib import Path
import colorsys
import sys

from PIL import Image

RES = Path("AnkiDroid/src/main/res")
DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
FILENAMES = ["ic_launcher.png", "ic_launcher_round.png"]

# Upstream palette, used to recognise pixels by their original role.
UPSTREAM_PLATE_VALUE = 74 / 255  # #434748
UPSTREAM_SPARK_VALUE = 228 / 255  # #18A1E4

PLATE_TOP = (0x0F, 0x49, 0x38)
PLATE_BOTTOM = (0x0A, 0x24, 0x42)
SPARK_TOP = (0x6E, 0xE7, 0xB7)
SPARK_BOTTOM = (0x0D, 0x94, 0x88)


def vertical_mix(top, bottom, y, height):
    t = y / max(height - 1, 1)
    return tuple(a + (b - a) * t for a, b in zip(top, bottom))


def scaled(base, factor):
    return tuple(max(0, min(255, round(c * factor))) for c in base)


def recolor(path):
    image = Image.open(path).convert("RGBA")
    pixels = image.load()
    counts = {"plate": 0, "spark": 0, "kept": 0}
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            hue, sat, value = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            degrees = hue * 360
            if sat < 0.12 and 0.22 <= value <= 0.48:
                pixels[x, y] = (*scaled(vertical_mix(PLATE_TOP, PLATE_BOTTOM, y, image.height),
                                         value / UPSTREAM_PLATE_VALUE), a)
                counts["plate"] += 1
            elif 160 <= degrees <= 240 and sat >= 0.12:
                pixels[x, y] = (*scaled(vertical_mix(SPARK_TOP, SPARK_BOTTOM, y, image.height),
                                        value / UPSTREAM_SPARK_VALUE), a)
                counts["spark"] += 1
            else:
                counts["kept"] += 1
    image.save(path)
    print(f"{path}: {counts}")


def main():
    if not RES.is_dir():
        sys.exit(f"run from the repository root, {RES} not found")
    for density in DENSITIES:
        for filename in FILENAMES:
            recolor(RES / f"mipmap-{density}" / filename)


if __name__ == "__main__":
    main()
