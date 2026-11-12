# Changelog

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
