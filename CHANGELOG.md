# Changelog

## 1.0.4

- The player marker on the map now follows the player continuously: cheap state requests move it between the (slower) map
  samples, so it no longer jumps only after a zoom.
- Dragging an item to the bottom uses it: the use zone is one big area along the whole bottom (status line and hotbar included)
  instead of a strip with a dead stretch in the middle. Drinking a potion consumes only the dragged potion, not every potion
  in the bag.
- The trash keeps only the newest item: putting something in replaces what was there instead of swapping it back.
- After a chest or shop closes with Hide HUD on, the game's inventory no longer pops up on the top screen.
- The live map accepts completed samples while zooming, panning or following the player.
- Recover a trashed item into a slot or drag it directly to an applicable action zone.
- Chest and NPC shop tabs slide into view when opened. With Hide HUD on, the mirrored inventory page is hidden on top.
- Buy and sell through the game's shop routines. Equipment readouts use the game's info accessory display.
- Added Spanish and Polish, game sprites on buttons and tabs, and the game's classic or fancy heart and mana art.
- Backgrounds use the game's active biome texture tables. The extractor includes the full background and Underworld atlases.

## 1.0.3

- The explored map is kept for good: samples are merged into per-world chunks on disk, so the map is no longer rebuilt from
  scratch when you pan, zoom or restart the app or the game.
- Hotbar frame follows the item in hand again (it only keeps the stable slot while the game holds a temporary override).

## 1.0.2

- Larger item icons: slots up to 100 px with 6 px gaps, sprites fill more of each slot.
- Compact top bar (about 40 px less height) and slimmer tabs; more room for the item grid, map and craft list.
- Refreshed screenshots.

## 1.0.1

- Fixed: with the HUD hidden, the controller's Y button no longer opens the game's own inventory; it cycles the companion's
  tabs. The game's page UI owns the open state, so the inventory is closed through `GUIPageIcons.Close`.
- Fixed: a mod file regression that made every state request fail (`readChest is not defined`) in the first 1.0.0 upload.
- Plain dirt / stone caves now use the texture pair the game itself uses for the current underground style.
- Town NPC heads and boss icons (with health) on the map, the game's interface sounds, boss / NPC data in the mod.
- Build script is portable (`JAVA_HOME`, `ANDROID_SDK_ROOT`).

Not verified on a device yet: chest window, interface sounds, boss icons on the map. Cave textures for biomes other than
plain dirt are a best guess.

## 1.0.0 — first public release

- Companion app for dual-screen Android devices: inventory (drag, swap, stack, split, trash slot, sort), item window with
  context actions and reforge percentages, equipment, crafting, chests and banks, hotbar, health / mana bars with hearts /
  stars flip, buffs and info accessories, map with NPC heads and boss icons, English / Russian.
- Edge gestures while dragging: drop, use / equip / summon, trash, favourite.
- Hide HUD with controller support: L1 / R1 hotbar, Y cycles tabs.
- Backgrounds decoded from the user's own game: biome parallax layers, cave walls, day / night tint, weather, lightning flashes,
  damage / heal / mana flashes; game interface sounds.
- TL Pro mod pack (JavaScript) that exposes the game state and runs the game's own routines.
- PC tools to decode sprites, fonts and backgrounds locally from the installed game.

Known gaps: NPC dialogue / shop actions, player head on the map, exact underground backgrounds, top-screen cleanup of every
controller hint.
