#!/usr/bin/env python3
"""Rebuild Terraria's biome backdrop textures from the user's own installed APK.

  python tools/extract_backgrounds.py [--apk path/to/base.apk] [--push] [--ids 7,9,15]

Output: assets-cache/backgrounds. The APK contains both the texture pages and the atlas tables.
The extraction can run with Terraria closed. No game files or images are committed.
"""
import argparse
from functools import lru_cache
from io import BytesIO
from pathlib import Path
import struct
import subprocess
from types import SimpleNamespace
import zlib

from PIL import Image

import extract_assets as ea

OUT = Path(ea.ROOT) / "assets-cache" / "backgrounds"
HIGH_RES = "content/packedimages/"


def background_atlas(data):
    """Read Terraria's version 3 Unity atlas database."""
    reader = BytesIO(data)

    def read(fmt):
        record = struct.Struct("<" + fmt)
        return record.unpack(reader.read(record.size))

    marker, version, count = read("hhh")
    if (marker, version) != (0, 3):
        raise ValueError(f"Unsupported background atlas version: {marker}, {version}")
    pages = [read("hhh") for _ in range(count)]
    entries = {}
    count, = read("i")
    for _ in range(count):
        uid, page, w, h, x, y, scale, tw, th = read("IIhhhhhhh")
        if scale != 1:
            raise ValueError(f"Unexpected scale {scale} in high-resolution background atlas")
        tiles = b""
        trim = False
        if tw > 0 and th > 0:
            trim, = read("?")
            count_x = -(-w // tw)
            count_y = -(-h // th)
            tiles = reader.read(count_x * count_y * (12 if trim else 8))
        entries[uid] = (page, w, h, x, y, tw, th, trim, tiles)
    return pages, entries


def load_page(resources, pages, page):
    """Read the shared index/alpha planes and the palette for this logical page."""
    width, height, remap = pages[page]
    physical = f"{HIGH_RES}background{remap + 1}"
    logical = f"{HIGH_RES}background{page + 1}"
    base = resources[physical].read()
    if (base.m_Width, base.m_Height) != (width, height):
        raise ValueError(f"Unexpected dimensions for {physical}")
    alpha = resources.get(physical + "_a")
    palette = resources.get(logical + "_p")
    if palette is None:
        return base.image.convert("RGBA")

    colors = palette.read().image.convert("RGBA")
    index = ea.alpha_of(base.image).convert("L")
    mask = ea.alpha_of(alpha.read().image) if alpha else index.point(lambda value: 255 if value else 0)
    if colors.height > 1:
        alpha_image = Image.new("RGBA", index.size)
        alpha_image.putalpha(mask)
        return ea.compose(base, SimpleNamespace(image=alpha_image), colors)

    index.putpalette([channel for r, g, b, _ in colors.getdata() for channel in (r, g, b)])
    image = index.convert("RGBA")
    image.putalpha(mask)
    return image


def build(entry, page_image):
    _, w, h, x, y, tw, th, trim, tiles = entry
    if not tiles:
        return page_image.crop((x, y, x + w, y + h))
    count_x = -(-w // tw)
    output = Image.new("RGBA", (w, h))
    for i, tile in enumerate(struct.iter_unpack("<6h" if trim else "<4h", tiles)):
        ox, oy = tile[:2] if trim else (0, 0)
        x0, y0, x1, y1 = tile[-4:]
        if x1 > x0 and y1 > y0:
            piece = page_image.crop((x0, y0, x1, y1))
            output.paste(piece, (i % count_x * tw - ox, i // count_x * th - oy))
    return output


def extract(apk, out, ids=None, max_id=None):
    bundle = ea.load_bundle(apk)
    resources = dict(next(obj.read().m_Container for obj in bundle.objects if obj.type.name == "ResourceManager"))
    atlas = resources[HIGH_RES + "background"].read()
    data = atlas.m_Script
    pages, entries = background_atlas(data.encode("utf-8", "surrogateescape") if isinstance(data, str) else data)
    jobs = []
    for prefix, stem in (("bg", "background_"), ("underworld", "backgrounds/underworld ")):
        if prefix == "underworld" and ids is not None:
            continue
        if prefix == "bg" and ids is not None:
            numbers = ids
        elif prefix == "bg" and max_id is not None:
            numbers = range(max_id)
        else:
            # Both of the game's indexed arrays are contiguous from zero.
            numbers = range(len(entries))
        for number in numbers:
            uid = zlib.crc32(f"{stem}{number}.png".encode("ascii"))
            if uid not in entries:
                if ids is not None:
                    raise ValueError(f"Background {number} is missing from the APK")
                break
            entry = entries[uid]
            jobs.append((entry[0], f"{prefix}_{number}.png", entry))

    out.mkdir(parents=True, exist_ok=True)
    read_page = lru_cache(maxsize=1)(lambda page: load_page(resources, pages, page))
    paths = []
    for page, name, entry in sorted(jobs):
        path = out / name
        build(entry, read_page(page)).save(path)
        paths.append(path)
    return paths


def push(paths):
    base = f"/sdcard/Android/data/{ea.COMPANION}/files/atlas/backgrounds"
    subprocess.check_call([ea.ADB, "shell", "mkdir", "-p", base])
    for path in paths:
        subprocess.check_call([ea.ADB, "push", str(path), base + "/" + path.name], stdout=subprocess.DEVNULL)
    print(f"Pushed {len(paths)} backgrounds to {base}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", help="Local copy of the installed game's base APK")
    parser.add_argument("--ids", help="Extract only these comma-separated background IDs")
    parser.add_argument("--max", type=int, help="Limit the number of regular backgrounds")
    parser.add_argument("--push", action="store_true")
    args = parser.parse_args()
    ids = [int(value) for value in args.ids.split(",")] if args.ids else None
    paths = extract(args.apk or ea.pull_apk(), OUT, ids, args.max)
    print(f"Extracted {len(paths)} backgrounds to {OUT}")
    if args.push:
        push(paths)
