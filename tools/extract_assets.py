#!/usr/bin/env python3
"""Decode the game's own atlas pages and font glyph sheets from the user's installed Terraria APK.

Nothing produced here is committed or distributed: the output goes to ./assets-cache (gitignored) and, with --push,
to the companion's private folder on the user's own device.

  tools/venv/Scripts/python.exe tools/extract_assets.py [--apk base.apk] [--push] [--dbs Item,UI,Background]

Format (reverse-engineered from the shipped data, verified visually against in-game sprite rectangles):
  <Name>    Alpha8 texture: palette INDEX per pixel
  <Name>_A  Alpha8 texture: real alpha
  <Name>_P  RGBA texture 256 x N: palette rows; row 0 (top row as exported) is the default colouring,
            the other rows are recolour variants
Pages without a _P/_A pair are plain RGBA textures.
"""
import argparse
import os
import re
import subprocess
import sys
import zipfile

import UnityPy
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets-cache", "atlas")
ADB = os.path.join(ROOT, "tools", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"   # fall back to adb from PATH
GAME = "com.and.games505.TerrariaPaid"
COMPANION = "com.terraduo.companion"
FONTS = {"mouse": "Mouse_Text_%d", "stack": "Item_Stack_%d"}


def pull_apk():
    path = subprocess.check_output([ADB, "shell", "pm", "path", GAME], text=True).splitlines()[0].split(":", 1)[1].strip()
    target = os.path.join(ROOT, "assets-cache", "installed-base.apk")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    subprocess.check_call([ADB, "pull", path, target])
    return target


def load_bundle(apk):
    with zipfile.ZipFile(apk) as z:
        data = z.read("assets/bin/Data/data.unity3d")
    return UnityPy.load(data)


def collect(env):
    tex = {}
    for obj in env.objects:
        if obj.type.name == "Texture2D":
            t = obj.read()
            tex.setdefault(t.m_Name, []).append(t)
    return tex


def alpha_of(image):
    """Alpha8 textures are exported by UnityPy as RGBA with the data in the alpha channel."""
    return image.split()[3]


def compose(idx, alpha, palette):
    """Decode an indexed atlas page. The page is cut into 256x256 blocks; every block has its own palette row:
    row = block_y * columns + (columns - 1 - block_x)   (found by matching sprites against the game's own rendering;
    verified on the heart, mana crystal, wedding dress, top hat, zombie arm and ten more sprites)."""
    pal = palette.convert("RGBA")
    plane = alpha_of(idx.image).convert("L")
    width, height = plane.size
    columns = (width + 255) // 256
    rows = (height + 255) // 256
    out = Image.new("RGB", (width, height))
    for by in range(rows):
        for bx in range(columns):
            row = min(by * columns + (columns - 1 - bx), pal.size[1] - 1)
            flat = []
            for r, g, b, _ in pal.crop((0, row, 256, row + 1)).getdata():
                flat += [r, g, b]
            box = (bx * 256, by * 256, min(width, bx * 256 + 256), min(height, by * 256 + 256))
            block = plane.crop(box)
            block.putpalette(flat)
            out.paste(block.convert("RGB"), box[:2])
    rgba = out.convert("RGBA")
    rgba.putalpha(alpha_of(alpha.image))
    return rgba


def pages_of(tex, db):
    pattern = re.compile(r"^%s(\d+)$" % re.escape(db))
    for name in sorted(tex, key=lambda n: (len(n), n)):
        m = pattern.match(name)
        if m:
            yield int(m.group(1)), name


def extract_db(tex, db):
    count = 0
    for number, name in pages_of(tex, db):
        for base in tex[name]:
            w, h = base.m_Width, base.m_Height
            alpha = next((a for a in tex.get(name + "_A", []) if a.m_Width == w and a.m_Height == h), None)
            candidates = [p for p in tex.get(name + "_P", []) if p.m_Width == 256]
            # the palette belonging to this resolution tier has height h/32 (hi-res) or h/64 (low-res)
            pal = next((p for p in candidates if p.m_Height in (h // 32, h // 64)), candidates[0] if candidates else None)
            if alpha is None and pal is not None and db == "Buff":
                # the buff icons carry their own square frame and have no separate alpha page
                class _Opaque:
                    image = Image.new("RGBA", (w, h), (0, 0, 0, 255))
                image = compose(base, _Opaque, pal.image)
            elif alpha is not None and pal is not None:
                image = compose(base, alpha, pal.image)
            else:
                image = base.image.convert("RGBA")
            image.save(os.path.join(OUT, "%s%d_%d.png" % (db, number, w)))
            count += 1
    return count


def extract_fonts(tex):
    for key, pattern in FONTS.items():
        for n in range(1, 40):
            name = pattern % n + "_A"
            if name not in tex:
                if n > 1:
                    break
                continue
            t = max(tex[name], key=lambda x: x.m_Width)
            alpha = alpha_of(t.image)
            glyph = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
            glyph.putalpha(alpha)
            glyph.save(os.path.join(OUT, "font_%s_%d.png" % (key, n - 1)))


def push():
    base = "/sdcard/Android/data/%s/files/atlas" % COMPANION
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    subprocess.check_call([ADB, "shell", "mkdir", "-p", base], env=env)
    names = sorted(os.listdir(OUT))
    for n in names:
        subprocess.check_call([ADB, "push", os.path.join(OUT, n), base + "/" + n], env=env,
                              stdout=subprocess.DEVNULL)
    print("pushed", len(names), "files to", base)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--push", action="store_true")
    ap.add_argument("--dbs", default="Item,UI,Misc,Extra,Buff,Cursor,MiniMap,MapBG,Moon,Star,Sun,Gem,Coin")
    args = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    apk = args.apk or pull_apk()
    tex = collect(load_bundle(apk))
    for db in args.dbs.split(","):
        print(db, extract_db(tex, db.strip()), "pages")
    extract_fonts(tex)
    print("done ->", OUT)
    if args.push:
        push()
