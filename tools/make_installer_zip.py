#!/usr/bin/env python3
"""Build the one-folder installer archive that is attached to a release.

  python tools/make_installer_zip.py <version> <apk> <mod.tl> [out_dir]

Layout of Terraduo-Companion-<version>-installer.zip:
  Install.bat, README-INSTALL.txt, Terraduo-Companion-<version>.apk, Terraduo-Companion-mod-*.tl,
  tools/install.py, tools/extract_assets.py, tools/extract_backgrounds.py, tools/requirements.txt
Nothing decoded from the game and no keystore ever goes into the archive.
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")


def main():
    version, apk, mod = sys.argv[1:4]
    out_dir = sys.argv[4] if len(sys.argv) > 4 else os.path.join(ROOT, "build")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "Terraduo-Companion-%s-installer.zip" % version)
    folder = "Terraduo-Companion-%s/" % version
    files = [
        (os.path.join(TOOLS, "installer", "Install.bat"), "Install.bat"),
        (os.path.join(TOOLS, "installer", "README-INSTALL.txt"), "README-INSTALL.txt"),
        (apk, os.path.basename(apk)),
        (mod, os.path.basename(mod)),
        (os.path.join(ROOT, "LICENSE"), "LICENSE"),
    ]
    for name in ("install.py", "extract_assets.py", "extract_backgrounds.py", "requirements.txt"):
        files.append((os.path.join(TOOLS, name), "tools/" + name))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:
        for path, name in files:
            if not os.path.isfile(path):
                sys.exit("missing: " + path)
            archive.write(path, folder + name)
    print(out)


if __name__ == "__main__":
    main()
