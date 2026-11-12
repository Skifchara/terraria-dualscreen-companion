#!/usr/bin/env python3
"""Package the local Terraduo TL Pro mod without any game assets."""

from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src" / "tlpro"
OUTPUT = ROOT / "build" / "terraduo-hotbar.tl"


if __name__ == "__main__":
    OUTPUT.parent.mkdir(exist_ok=True)
    with ZipFile(OUTPUT, "w", ZIP_DEFLATED) as archive:
        files = [SOURCE / "Settings.json", SOURCE / "Modified/1.json"]
        files.extend(sorted((SOURCE / "Modified" / "1.mod").glob("*.js")))
        for path in files:
            archive.write(path, path.relative_to(SOURCE).as_posix())
    print(OUTPUT)
