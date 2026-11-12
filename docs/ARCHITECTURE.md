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
| `main.js` | TCP server (non-blocking, polled from the `Main.DoUpdate` hook), request router, HUD hiding hooks, controller helpers (hotbar bumpers, Y) |
| `game.js` | state snapshot (inventory, equipment, craft list, buffs, info, NPCs, chest, env), slot references, item transfer / stacking, crafting, interface sounds |
| `actions.js` | item info, per-item actions (use, equip, split, drop, trash, favourite, chest buttons, sort), NPC dialogue state |
| `sprites.js` | where a sprite lives in the game's atlas (database, page, rectangle, tint), tile tables of background atlases, diagnostics |
| `map.js` | map sampling for the visible region |

Protocol: one request object per connection, one JSON object back. Ops: `state`, `select`, `transfer`, `craft`, `act`,
`map`, `entries`, `tiles`, `backgrounds`, `info`, `npc`, `sound`, plus diagnostics. Every mutating request carries what the
UI believes is in the slot (`expectFrom` / `expectTo`) and the mod refuses if the game disagrees.

## The app (`src/companion`)

- `TlProActivity` — polling loop on a worker thread; opens on the second display; keeps focus on the game
  (`FocusHelper`) because the controller follows the focused display.
- `TerraPanel` — the whole UI on one `Canvas`: tabs, slots, drag and edge gestures, item window, craft, map, chest, dialogue.
- `SpriteStore` — crops sprites from the locally decoded atlas pages, background thread, memory + disk cache.
- `BiomeScene` — the backdrop: real parallax layers and cave textures, weather, vignette, lightning.
- `Strings` — English / Russian text. `GameFont` — the game's bitmap font from decoded glyph sheets.

## Assets pipeline (`tools`)

The game's art is **never** shipped. `extract_assets.py` pulls the user's installed APK, decodes the Unity atlases
(index image + palette; each 256×256 block has its own palette row: `row = by * columns + (columns - 1 - bx)`), and pushes
PNG pages to the app's private folder. `extract_backgrounds.py` reads the APK's `Background` atlas table and rebuilds the
layers for surface, cave, and Underworld scenes. The mod reads the game's current surface style and the corresponding
texture tables for surface, cave, and Underworld scenes.

## Things that cost us time (so you do not repeat them)

- A hook whose callback gets a null object crashes the whole game at world entry. Hook only well-understood methods.
- Methods with overloads need a signature string; C# enums cannot be passed as JS numbers.
- The second display is a presentation display with no input association: keep focus on display 0 or the controller stops
  working in the game.
- `d8` mis-handles anonymous classes and generic framework subclasses: use plain top-level classes.
