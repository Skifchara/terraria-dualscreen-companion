#!/usr/bin/env python3
"""Draw the launcher icon (original pixel art: two stacked screens, nothing taken from the game).

Writes src/companion/res/mipmap-*/ic_launcher.png and dist-ready assets-cache/icon_512.png.
  tools/venv/Scripts/python.exe -I tools/make_icon.py
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "companion", "res")
GRID = 32


def draw():
    img = Image.new("RGBA", (GRID, GRID), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # rounded dark plate with a vertical gradient
    for y in range(GRID):
        t = y / (GRID - 1)
        color = (int(18 + 20 * t), int(30 + 26 * t), int(66 + 40 * t), 255)
        d.line([(0, y), (GRID - 1, y)], fill=color)
    mask = Image.new("L", (GRID, GRID), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, GRID - 1, GRID - 1], radius=6, fill=255)
    img.putalpha(mask)
    d = ImageDraw.Draw(img)
    # top screen: sky, sun, hills
    d.rectangle([3, 3, 28, 14], fill=(12, 16, 32, 255))
    d.rectangle([4, 4, 27, 13], fill=(96, 164, 232, 255))
    d.rectangle([4, 10, 27, 13], fill=(60, 130, 76, 255))
    d.rectangle([4, 11, 27, 13], fill=(40, 100, 56, 255))
    for x, y in ((8, 9), (9, 9), (10, 9), (9, 8), (20, 8), (21, 8), (22, 8), (21, 7)):
        d.point((x, y), fill=(240, 248, 255, 255))
    d.rectangle([23, 5, 25, 7], fill=(255, 214, 80, 255))
    # bottom screen: inventory slots and a gem
    d.rectangle([3, 16, 28, 28], fill=(12, 16, 32, 255))
    d.rectangle([4, 17, 27, 27], fill=(34, 52, 104, 255))
    slot = (70, 92, 176, 255)
    edge = (22, 32, 70, 255)
    for row in range(2):
        for col in range(4):
            x = 6 + col * 5
            y = 19 + row * 4
            d.rectangle([x, y, x + 3, y + 3], fill=edge)
            d.rectangle([x + 1, y + 1, x + 2, y + 2], fill=slot)
    d.rectangle([7, 20, 8, 21], fill=(255, 196, 64, 255))      # a gold ingot
    d.rectangle([17, 20, 18, 21], fill=(224, 65, 74, 255))     # a heart-red gem
    d.rectangle([12, 24, 13, 25], fill=(90, 160, 255, 255))    # a mana-blue gem
    return img


def main():
    base = draw()
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, size in sizes.items():
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        base.resize((size, size), Image.NEAREST).save(os.path.join(folder, "ic_launcher.png"))
    out = os.path.join(ROOT, "assets-cache", "icon_512.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    base.resize((512, 512), Image.NEAREST).save(out)
    print("icons written to", RES, "and", out)


if __name__ == "__main__":
    main()
