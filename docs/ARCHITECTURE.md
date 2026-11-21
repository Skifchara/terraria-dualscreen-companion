# Architecture

```
┌─────────────── one Android device, two displays ───────────────┐
│ display 0: Terraria (Unity / IL2CPP) running inside TL Pro      │
│                │  IL2CPP bindings                               │
│        TL Pro JavaScript mod  (src/tlpro/Modified/1.mod)         │
│                │  one JSON line per TCP connection              │
│        127.0.0.1:38656                                          │
│                │                                                 │
│ display 4: Companion app (src/companion)  — draws one Canvas    │
└──────────────────────────────────────────────────────────────────┘
```

## Why this shape

The game must stay unmodified and its protection untouched, so nothing is injected into the game APK. [TL Pro](https://play.google.com/store/apps/details?id=com.pixelcurves.terlauncher)
is an official launcher that can load JavaScript mods into the game process; the mod reads real state and calls the game's own
methods. The companion is an ordinary app on the second display.

## The mod (`src/tlpro`)

| File | Role |
|---|---|
| `main.js` | TCP server (non-blocking, polled from the `Main.DoUpdate` hook), request router, protocol version, pause, HUD hiding hooks, controller helpers (hotbar bumpers, Y) |
| `game.js` | state snapshot (inventory, equipment, craft list, buffs, info, NPCs, chest, env), recipes, banners, chat, emotes, bestiary, hide-on-character switches, item transfer / stacking, crafting, interface sounds |
| `actions.js` | item info, per-item actions (use, equip, split, drop, trash, favourite, chest buttons, sort, buffs, pets, team, loadouts, housing, say / emote / talk), NPC dialogue state |
| `shop.js` | town NPC shops (stock, buy / sell) and the "nearby NPCs" list |
| `sprites.js` | where a sprite lives in the game's atlas (database, page, rectangle, per-frame tiles of tall sprites, tint), tile tables of background atlases, diagnostics |
| `map.js` | map sampling for the visible region and block names of explored tiles |

Protocol: one request object per connection, one JSON object back. Ops: `state`, `select`, `transfer`, `craft`, `act`,
`map`, `maptile`, `entries`, `tiles`, `backgrounds`, `info`, `npc`, `sound`, `chatlog`, `recipesfor`, `bestiary`, `bestiaryinfo`,
`pause`, plus diagnostics (`sprite`, `font`, `probe`, `render`, `inspect`, `targets`, `loadedbg`). `state` carries `proto`: the
protocol number of the running mod; the app compares it with the one it needs and asks for a mod update when the mod is older
(bump `PROTOCOL` in `main.js` and `EXPECTED_PROTOCOL` in `TerraPanel.java` together). Every mutating request carries what the
UI believes is in the slot (`expectFrom` / `expectTo`) and the mod refuses if the game disagrees.

## The app (`src/companion`)

- `TlProActivity` — polling loop on a worker thread; opens on the second display; keeps focus on the game
  (`FocusHelper`) because the controller follows the focused display.
- `TerraPanel` — the whole UI on one `Canvas`: tabs, slots, drag and edge gestures, item window, craft (recipes / banners /
  guide), equipment, people and bestiary, chat and emotes, nearby, map, chest and shop, dialogue, info window.
- `SpriteStore` — crops sprites from the locally decoded atlas pages (stitching per-frame tiles of tall sprites), background
  thread, memory + disk cache; cache folders of older versions are removed on start.
- `BiomeScene` — the backdrop: real parallax layers and cave textures with cross-fades, weather, vignette, lightning.
- `SetupActivity` — the launcher screen and all-in-one installer: checks Terraria / TL Pro, runs `SpriteExtractor`, hands the mod pack
  to TL Pro (`ModProvider` serves the `.tl` stored in the app's assets to TL Pro's `.tl` VIEW handler) and shows live whether the
  mod answers (`ModLink`). Once everything is in place it forwards straight to the companion.
- `SpriteExtractor` (+ `UnityBundle`, `UnityAssets`, `Lz4`, `Png`) — decodes the game's atlases, fonts and backgrounds from the installed
  APK on the device: a UnityFS reader with random access to the LZ4 blocks of data.unity3d (750 MB uncompressed, never fully
  unpacked), serialized file parsing (format 22, Texture2D / TextAsset / ResourceManager) and the same palette composition as
  the Python tools. These classes have no Android dependency; `tests/java/.../ExtractCheck.java` runs them on a PC and compares every
  PNG with the Python output.
- `MapCache` — permanent per-world map chunks. `Strings` — English / Russian / Spanish / Polish text. `GameFont` — the game's
  bitmap font from decoded glyph sheets. `Json` — tiny helpers.

## Assets pipeline (`tools`)

The game's art is **never** shipped. `tools/install.py` (or `Install.bat`) drives the whole thing from the PC: adb, app, mod
copy, and the two extractors below. `extract_assets.py` pulls the user's installed APK, decodes the Unity atlases
(index image + palette; each 256×256 block has its own palette row: `row = by * columns + (columns - 1 - bx)`), and pushes
PNG pages to the app's private folder. `extract_backgrounds.py` reads the APK's `Background` atlas table and rebuilds the
layers for surface, cave, and Underworld scenes. The mod reads the game's current surface style and the corresponding
texture tables for surface, cave, and Underworld scenes.

## Things that cost us time (so you do not repeat them)

- A hook whose callback gets a null object crashes the whole game at world entry. Hook only well-understood methods.
- Methods with overloads need a signature string; C# enums cannot be passed as JS numbers.
- The second display is a presentation display with no input association: keep focus on display 0 or the controller stops
  working in the game.
- `d8` mis-handles anonymous classes and generic framework subclasses: use plain top-level classes (a lambda is fine).
- The TL Pack `version` in `src/tlpro/Settings.json` must go up whenever the mod changes, or TL Pro may not treat an import as an
  update: `tools/release.py` does it and `tools/release.py --check` verifies it.
- Atlas entries of tall sprites (town NPCs, big bosses) are stored as trimmed per-frame pieces whose rectangles are relative to
  the entry position; some pages have palette rows but no alpha plane (index 0 is transparent).
- Request size limit of the mod is 8 KB; keep batches of sprite requests small.
