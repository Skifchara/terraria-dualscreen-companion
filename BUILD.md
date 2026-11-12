# Building

You need:

- **JDK 17+** (`JAVA_HOME`), Python 3.10+, Node.js (only to syntax-check the mod).
- **Android SDK** with `build-tools` (any recent, `d8`, `aapt2`, `zipalign`, `apksigner`) and `platforms/android-33`.
  Point `ANDROID_SDK_ROOT` at it, or put it in `tools/android-sdk` (git-ignored).

```
python tools/make_icon.py        # optional, redraws src/companion/res/mipmap-*/ic_launcher.png
python tools/build.py            # -> build/terraduo-companion.apk
python tools/build_tlpro.py      # -> build/terraduo-hotbar.tl  (import into TL Pro)
```

`tools/dev.keystore` is generated on the first build (password `terraduo-dev`). It is a **development** key only — never use it
to sign something you distribute under your own name; create your own keystore for that.

Sprites are not part of the build: see `tools/extract_assets.py` and `tools/extract_backgrounds.py`
(`pip install -r tools/requirements.txt`, run them against **your** installed copy of Terraria).

Syntax check of the mod (also run in CI):

```
for f in src/tlpro/Modified/1.mod/*.js; do node --input-type=module --check < "$f"; done
```
