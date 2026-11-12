#!/usr/bin/env python3
"""Zip the release folder (dist/) together with the source code into one archive.

  python tools/package_release.py
Result: dist/ stays as the folder; Terraduo-Companion-1.0-full.zip (dist + source/) is written next to it.
Nothing from the game (play/, assets-cache/, decoded atlases) and no keystore ever goes into the archive.
"""
import hashlib
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")
OUT = os.path.join(ROOT, "Terraduo-Companion-1.0-full.zip")

SOURCE_DIRS = ["src", "tools"]
SOURCE_FILES = ["README.md", "ARCHITECTURE.md", "BUILD.md", "INSTALL.md", "TESTING.md", "COMPATIBILITY.md",
                "APK_ANALYSIS.md", ".gitignore"]
SKIP_DIRS = {"venv", "android-sdk", "platform-tools", "Il2CppDumper", "_dl", "__pycache__"}
SKIP_SUFFIX = (".keystore", ".apk", ".so", ".pyc")


def source_entries():
    for name in SOURCE_FILES:
        path = os.path.join(ROOT, name)
        if os.path.isfile(path):
            yield path, "source/" + name
    for folder in SOURCE_DIRS:
        for base, dirs, files in os.walk(os.path.join(ROOT, folder)):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for file in files:
                if file.endswith(SKIP_SUFFIX):
                    continue
                path = os.path.join(base, file)
                yield path, "source/" + os.path.relpath(path, ROOT).replace(os.sep, "/")


def main():
    sums = []
    for base, _, files in os.walk(DIST):
        for file in sorted(files):
            if file == "SHA256SUMS.txt":
                continue
            path = os.path.join(base, file)
            digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
            sums.append("%s  %s" % (digest, os.path.relpath(path, DIST).replace(os.sep, "/")))
    open(os.path.join(DIST, "SHA256SUMS.txt"), "w", encoding="utf-8").write("\n".join(sorted(sums)) + "\n")
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as archive:
        for base, _, files in os.walk(DIST):
            for file in files:
                path = os.path.join(base, file)
                archive.write(path, "Terraduo-Companion-1.0/" + os.path.relpath(path, DIST).replace(os.sep, "/"))
        for path, name in source_entries():
            archive.write(path, "Terraduo-Companion-1.0/" + name)
    print(OUT, os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
