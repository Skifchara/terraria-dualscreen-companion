#!/usr/bin/env python3
"""Rebuild the game's background textures from the user's own installation.

The game stores each biome background as 64x64 tiles on its own atlas page (only the distinct tiles, trimmed). The
tile table lives in the running game (TextAtlasEntryTiles.TileOffsetData) and is read through the TL Pro mod ("tiles"
op). One tile is six shorts: [-offsetX, -offsetY, srcX0, srcY0, srcX1, srcY1] - the rectangle (srcX0..srcX1, srcY0..srcY1)
of the page is drawn at (tileX*64 - offsetX, tileY*64 - offsetY).

  adb forward tcp:38656 tcp:38656      (game running with the mod)
  tools/venv/Scripts/python.exe -I tools/extract_backgrounds.py --apk play/base.apk [--push] [--ids 7,9,15]

Output: assets-cache/backgrounds/bg_<id>.png (+ tables cached in assets-cache/bg_tables.json). Nothing is committed.
"""
import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image  # noqa: E402

import extract_assets as ea  # noqa: E402
from tl_client import ask  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "assets-cache")
OUT = os.path.join(CACHE, "backgrounds")
TABLES = os.path.join(CACHE, "bg_tables.json")
ADB = ea.ADB


def fetch_tables(ids):
    tables = json.load(open(TABLES)) if os.path.isfile(TABLES) else {}
    for i in ids:
        key = str(i)
        if key in tables:
            continue
        try:
            probe = ask({"op": "probe", "kind": "background", "id": i}, timeout=60)
            entry = probe["entry"]
            if not isinstance(entry, dict):
                tables[key] = {"error": "no entry"}
                continue
            item = {"w": probe["w"], "h": probe["h"], "entry": entry, "unity": [probe["unity"]["w"], probe["unity"]["h"]],
                    "alpha": bool(probe.get("alpha"))}
            if entry.get("trim"):
                tiles_x = -(-entry["w"] // entry["tw"])
                tiles_y = -(-entry["h"] // entry["th"])
                data = []
                count = tiles_x * tiles_y * 6
                for start in range(0, count, 3600):
                    part = ask({"op": "tiles", "kind": "background", "id": i, "start": start, "count": min(3600, count - start)},
                               timeout=120)
                    data += part["data"]
                item["tiles"] = [tiles_x, tiles_y]
                item["data"] = data
            tables[key] = item
        except Exception as error:  # keep going; one broken id must not stop the rest
            tables[key] = {"error": str(error)}
        print("table", i, "ok" if "error" not in tables[key] else tables[key]["error"])
    json.dump(tables, open(TABLES, "w"))
    return tables


def page_image(tex, page, unity):
    """The page as RGBA (palette applied) in the resolution the game uses."""
    name = "Background%d" % (page + 1)
    variants = tex[name]
    base = next((v for v in variants if [v.m_Width, v.m_Height] == unity), None)
    if base is None:
        base = max(variants, key=lambda v: v.m_Width)
    pal_list = [p for p in tex.get(name + "_P", []) if p.m_Width == 256]
    blocks = ((base.m_Width + 255) // 256) * ((base.m_Height + 255) // 256)
    shared = next((p for p in pal_list if p.m_Height == blocks and blocks > 1), None)
    if shared is not None:
        # a shared page of small sprites: every 256x256 block has its own palette row (see extract_assets.compose)
        alpha_tex = next((a for a in tex.get(name + "_A", []) if a.m_Width == base.m_Width and a.m_Height == base.m_Height), None)
        if alpha_tex is None:
            class _Opaque:
                image = Image.new("RGBA", (base.m_Width, base.m_Height), (0, 0, 0, 255))
            alpha_tex = _Opaque
        return ea.compose(base, alpha_tex, shared.image)
    alpha = next((a for a in tex.get(name + "_A", []) if a.m_Width == base.m_Width and a.m_Height == base.m_Height), None)
    index = ea.alpha_of(base.image).convert("L")
    mask = index.point(lambda v: 0 if v == 0 else 255)
    if not pal_list:
        rgba = base.image.convert("RGBA")
        return rgba
    palette = pal_list[0].image.convert("RGBA")
    flat = []
    for r, g, b, _ in palette.crop((0, 0, 256, 1)).getdata():
        flat += [r, g, b]
    index.putpalette(flat)
    rgb = index.convert("RGB").convert("RGBA")
    if alpha is not None:
        rgb.putalpha(ea.alpha_of(alpha.image))
    else:
        # no alpha page: palette index 0 is the transparent colour
        rgb.putalpha(mask)
    return rgb


def build(item, page_img):
    entry = item["entry"]
    scale = entry.get("scale", 1) or 1
    if "tiles" not in item:
        x, y, w, h = entry["x"], entry["y"], entry["w"], entry["h"]
        return page_img.crop((x, y, x + w, y + h))
    tiles_x, tiles_y = item["tiles"]
    tw, th = entry["tw"], entry["th"]
    out = Image.new("RGBA", (tiles_x * tw, tiles_y * th), (0, 0, 0, 0))
    data = item["data"]
    for ty in range(tiles_y):
        for tx in range(tiles_x):
            at = (ty * tiles_x + tx) * 6
            ox, oy, x0, y0, x1, y1 = data[at:at + 6]
            if x1 <= x0 or y1 <= y0:
                continue
            piece = page_img.crop((x0, y0, x1, y1))
            out.paste(piece, (tx * tw - ox, ty * th - oy))
    return out.crop((0, 0, entry["w"], entry["h"]))


def push():
    base = "/sdcard/Android/data/com.terraduo.companion/files/atlas/backgrounds"
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    subprocess.check_call([ADB, "shell", "mkdir", "-p", base], env=env)
    for name in sorted(os.listdir(OUT)):
        subprocess.check_call([ADB, "push", os.path.join(OUT, name), base + "/" + name], env=env, stdout=subprocess.DEVNULL)
    print("pushed", len(os.listdir(OUT)), "backgrounds")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--ids", default="")
    ap.add_argument("--max", type=int, default=340)
    ap.add_argument("--push", action="store_true")
    args = ap.parse_args()
    ids = [int(v) for v in args.ids.split(",") if v] or list(range(args.max))
    os.makedirs(OUT, exist_ok=True)
    tables = fetch_tables(ids)
    tex = ea.collect(ea.load_bundle(args.apk or ea.pull_apk()))
    cache = {}
    for i in ids:
        item = tables.get(str(i))
        if not item or "error" in item:
            continue
        entry = item["entry"]
        key = (entry["page"], tuple(item["unity"]))
        try:
            if key not in cache:
                cache[key] = page_image(tex, entry["page"], list(item["unity"]))
            image = build(item, cache[key])
            image.save(os.path.join(OUT, "bg_%d.png" % i))
        except Exception as error:
            print("build", i, "failed:", error)
    print("done ->", OUT)
    if args.push:
        push()
