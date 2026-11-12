const Main = new NativeClass("Terraria", "Main");
const ItemSlot = new NativeClass("Terraria.UI", "ItemSlot");
const Recipe = new NativeClass("Terraria", "Recipe");
const CraftingRequests = new NativeClass("Terraria.GameContent", "CraftingRequests");
const equipmentArrays = ["armor", "dye", "miscEquips", "miscDyes"];
const utilityContexts = [19, 20, 18, 17, 16];
const inventorySize = 58;

// The game's own interface sounds (SoundID): played through its sound engine on the main device.
const SoundEngine = new NativeClass("Terraria.Audio", "SoundEngine");
const soundIds = { tick: 12, open: 10, close: 11, grab: 7, coins: 18, unlock: 22, chat: 24 };
let lastSoundAt = 0;

function playSound(name) {
    const id = soundIds[name];
    const now = Date.now();
    if (id === undefined || now - lastSoundAt < 60) {
        return;
    }
    lastSoundAt = now;
    try {
        SoundEngine["SoundEffectInstance PlaySound(int type, int x, int y, int Style, float volumeScale, float pitchOffset)"](
            id, -1, -1, 1, 1, 0);
    } catch (_) { /* sounds are optional */ }
}

function itemState(item) {
    if (!item || item.type === 0 || item.stack <= 0) {
        return [0, 0, 0, false, ""];
    }
    return [item.type, item.stack, item.prefix, item.favorited, item.get_Name()];
}

function slots(array, count) {
    const result = [];
    for (let index = 0; index < Math.min(count, array.length); index++) {
        result.push(itemState(array[index]));
    }
    return result;
}

function sameItem(item, expected) {
    const actual = itemState(item);
    return Array.isArray(expected) && expected.length === 4 &&
        expected.every((value, index) => value === actual[index]);
}

function slotRef(player, key) {
    if (!Array.isArray(key) || key.length !== 2 ||
        !key.every(Number.isInteger) || key[0] < 0 || key[0] > equipmentArrays.length + 1 || key[1] < 0) {
        throw new Error("Некорректный слот");
    }
    const [group, index] = key;
    if (group === 5) {
        if (player.chest === -1) {
            throw new Error("Контейнер закрыт");
        }
        const storage = player.GetCurrentContainer();
        const items = storage.item;
        if (index >= Math.min(storage.maxItems, items.length)) {
            throw new Error("Некорректный слот");
        }
        return { array: items, index, context: player.chest >= 0 ? 3 : 4, item: items[index] };
    }
    const array = group === 0 ? player.inventory : player[equipmentArrays[group - 1]];
    const count = group === 0 ? inventorySize : group === 1 ? 20 : group === 2 ? 10 : 5;
    if (index >= Math.min(count, array.length)) {
        throw new Error("Некорректный слот");
    }

    let context;
    if (group === 0) {
        context = index < 50 ? 0 : index < 54 ? 1 : 2;
    } else if (group === 1) {
        if (!player.IsItemSlotUnlockedAndUsable(index % 10)) {
            throw new Error("Слот снаряжения закрыт");
        }
        context = index < 10 ? (index < 3 ? 8 : 10) : (index < 13 ? 9 : 11);
    } else if (group === 2) {
        if (!player.IsItemSlotUnlockedAndUsable(index)) {
            throw new Error("Слот красителя закрыт");
        }
        context = 12;
    } else {
        context = group === 3 ? utilityContexts[index] : 33;
    }
    return { array, index, context, item: array[index] };
}

function refreshRecipes() {
    Recipe.FindRecipes(false);
    Recipe.GetThroughDelayedFindRecipes();
}

function readRecipes(page) {
    refreshRecipes();
    const player = Main.get_LocalPlayer();
    const indices = Main.get_availableRecipe();
    const count = Main.get_numAvailableRecipes();
    const recipes = Main.recipe;
    const result = [];
    const total = Math.min(count, indices.length);
    const offset = Math.min(Math.floor(Math.max(0, total - 1) / 24), page) * 24;
    for (let index = offset; index < Math.min(offset + 24, total); index++) {
        const id = indices[index];
        const recipe = recipes[id];
        if (recipe) {
            const ingredients = slots(recipe.requiredItem, 32).filter((item) => item[0] !== 0).map((item) => {
                let have = -1;
                try {
                    have = player.CountItem(item[0], 99999);
                } catch (_) { /* leave unknown */ }
                return [item[4], item[1], item[0], have];
            });
            let tile = "";
            try {
                if (recipe.requiredTile >= 0) {
                    tile = String(Recipe.GetRequiredTileName(recipe.requiredTile));
                }
            } catch (_) { /* no station name */ }
            const needs = [];
            for (const [flag, label] of [["needWater", "water"], ["needHoney", "honey"], ["needLava", "lava"],
                ["needSnowBiome", "snow"]]) {
                try {
                    if (recipe[flag]) {
                        needs.push(label);
                    }
                } catch (_) { /* flag missing */ }
            }
            result.push([id, itemState(recipe.createItem), ingredients, tile, needs]);
        }
    }
    return { entries: result, total, page: offset / 24, focus: safe(() => Main.get_focusRecipe(), -1) };
}

function safe(read, fallback) {
    try {
        return read();
    } catch (_) {
        return fallback;
    }
}

function craft(request) {
    if (!Number.isInteger(request.id) || request.id < 0 ||
        Main.LocalPlayerHasPendingInventoryActions() || !Main.get_mouseItem().get_IsAir()) {
        throw new Error("Крафт сейчас недоступен");
    }
    refreshRecipes();
    const indices = Main.get_availableRecipe();
    const count = Main.get_numAvailableRecipes();
    const recipe = request.id < Main.recipe.length ? Main.recipe[request.id] : null;
    if (!recipe || !sameItem(recipe.createItem, request.expect) ||
        !Array.from({ length: Math.min(count, indices.length) }, (_, index) => indices[index]).includes(request.id) ||
        !Recipe["bool CollectedEnoughItemsToCraft(Recipe recipe)"](recipe)) {
        throw new Error("Рецепт больше недоступен");
    }
    CraftingRequests.CraftItem(recipe, 1, true);
    playSound("grab");
}

function transfer(player, request) {
    const source = slotRef(player, request.from);
    const destination = slotRef(player, request.to);
    if (request.from[0] === request.to[0] && source.index === destination.index) {
        return;
    }
    if (!sameItem(source.item, request.expectFrom) || !sameItem(destination.item, request.expectTo)) {
        throw new Error("Слоты изменились. Выбери предмет снова");
    }
    // Same item on both sides: stack them, like the game does when you drop one stack on another.
    if (!source.item.get_IsAir() && !destination.item.get_IsAir() && source.item.type === destination.item.type &&
        source.item.prefix === destination.item.prefix && destination.item.maxStack > 1 &&
        destination.item.stack < destination.item.maxStack && source.context !== 8 && destination.context < 8) {
        const moved = Math.min(destination.item.maxStack - destination.item.stack, source.item.stack);
        destination.item.stack += moved;
        source.item.stack -= moved;
        if (source.item.stack <= 0) {
            source.item.TurnToAir();
        }
        refreshRecipes();
        playSound("grab");
        return;
    }
    const cursor = Main.get_mouseItem();
    if (source.item.get_IsAir() || !cursor.get_IsAir() || Main.LocalPlayerHasPendingInventoryActions() ||
        player.itemAnimation !== 0 || player.itemTime !== 0 || Main.get_mouseLeft()) {
        throw new Error("Слот сейчас занят действием игры");
    }
    if (ItemSlot.PickItemMovementAction(destination.array, destination.context, destination.index,
        source.item, false, source.index) < 0 ||
        ItemSlot.PickItemMovementAction(source.array, source.context, source.index,
            destination.item, true, destination.index) < 0) {
        throw new Error("Предмет не подходит в этот слот");
    }

    const sourceCopy = source.item.DeepClone();
    const destinationCopy = destination.item.DeepClone();
    const release = Main.get_mouseLeftRelease();
    const click = ItemSlot["void LeftClick(Item[] inv, int context, int slot)"];
    try {
        Main.set_mouseLeft(true);
        Main.set_mouseLeftRelease(true);
        click(source.array, source.context, source.index);
        if (Main.get_mouseItem().get_IsAir()) {
            throw new Error("Игра не взяла предмет");
        }
        Main.set_mouseLeftRelease(true);
        click(destination.array, destination.context, destination.index);
        if (!Main.get_mouseItem().get_IsAir()) {
            Main.set_mouseLeftRelease(true);
            click(source.array, source.context, source.index);
        }
        if (!Main.get_mouseItem().get_IsAir()) {
            throw new Error("Игра не завершила перенос");
        }
    } catch (error) {
        source.array[source.index] = sourceCopy;
        destination.array[destination.index] = destinationCopy;
        Main.set_mouseItem(cursor);
        throw error;
    } finally {
        Main.set_mouseLeft(false);
        Main.set_mouseLeftRelease(release);
    }
    if (sameItem(source.array[source.index], request.expectFrom) &&
        sameItem(destination.array[destination.index], request.expectTo)) {
        throw new Error("Игра оставила слоты без изменений");
    }
    playSound("grab");
}

function select(player, slot) {
    if (!Number.isInteger(slot) || slot < 0 || slot > 9) {
        throw new Error("Некорректный слот хотбара");
    }
    const current = player.selectedItemState.get_Hotbar();
    if (slot !== current) {
        player.ScrollHotbar(slot - current);
    }
}

const zoneNames = ["ZoneSnow", "ZoneJungle", "ZoneCorrupt", "ZoneCrimson", "ZoneHallow", "ZoneDesert",
    "ZoneUndergroundDesert", "ZoneGlowshroom", "ZoneDungeon", "ZoneMeteor", "ZoneGraveyard", "ZoneBeach",
    "ZoneSkyHeight", "ZoneOverworldHeight", "ZoneDirtLayerHeight", "ZoneRockLayerHeight",
    "ZoneUnderworldHeight", "ZoneHive", "ZoneLihzhardTemple", "ZoneGranite", "ZoneMarble", "ZoneSandstorm"];
const GameCulture = new NativeClass("Terraria.Localization", "GameCulture");
let language = "";

function environment(player) {
    const zones = [];
    for (const name of zoneNames) {
        try {
            if (player["get_" + name]()) {
                zones.push(name.slice(4));
            }
        } catch (_) { /* zone not exposed in this build */ }
    }
    return { day: !!Main.dayTime, rain: !!Main.raining, blood: !!Main.bloodMoon, eclipse: !!Main.eclipse, zones,
        time: safe(() => Number(Main.time), 0), lightning: safe(() => Number(Main.lightning), 0),
        worldW: safe(() => Main.maxTilesX, 0), worldH: safe(() => Main.maxTilesY, 0),
        surface: safe(() => Main.worldSurface, 0), rock: safe(() => Main.rockLayer, 0),
        rainPower: safe(() => Number(Main.maxRaining), 0), moon: safe(() => Main.moonPhase, 0),
        cave: safe(() => Main.caveBackground, 0), under: safe(() => Main.undergroundBackground, 0),
        style: safe(() => Main.bgStyle, 0) };
}

function currentLanguage() {
    if (!language) {
        try {
            language = String(GameCulture.get_ActiveCulture().get_Name());
        } catch (_) {
            language = "en-US";
        }
    }
    return language;
}


const Lang = new NativeClass("Terraria", "Lang");
const NPCClass = new NativeClass("Terraria", "NPC");
const buffNames = new Map();

function buffName(type) {
    if (!buffNames.has(type)) {
        try {
            buffNames.set(type, String(Lang.GetBuffName(type)));
        } catch (_) {
            buffNames.set(type, "#" + type);
        }
    }
    return buffNames.get(type);
}

// Active buffs and debuffs: [type, ticks left, isDebuff, localised name]
function readBuffs(player) {
    const result = [];
    try {
        for (let index = 0; index < 22; index++) {
            const type = player.buffType[index];
            if (type > 0) {
                result.push([type, player.buffTime[index], !!Main.debuff[type], buffName(type)]);
            }
        }
    } catch (_) { /* buffs unavailable */ }
    return result;
}

// What the info accessories (watch, depth meter, compass, weather radio) would show, from the game's own formulas.
function readInfo(player) {
    const info = {};
    try {
        const center = player.get_Center();
        if (player.accWatch > 0) {
            let ticks = Main.time;
            if (!Main.dayTime) {
                ticks += 54000;
            }
            let hours = ticks / 86400 * 24 - 7.5 - 12;
            if (hours < 0) {
                hours += 24;
            }
            info.time = [Math.floor(hours), Math.floor((hours - Math.floor(hours)) * 60)];
        }
        if (player.accDepthMeter > 0) {
            info.depth = Math.round((center.Y + player.height / 2) * 2 / 16 - Main.worldSurface * 2);
        }
        if (player.accCompass > 0) {
            info.compass = Math.round(center.X * 2 / 16 - Main.maxTilesX);
        }
        if (player.accWeatherRadio) {
            info.wind = Math.round(Main.windSpeedCurrent * 50);
            info.rain = !!Main.raining;
        }
        info.fish = !!player.accFishFinder;
    } catch (_) { /* partial info is fine */ }
    return info;
}

// NPCs for the map: [tileX, tileY, headIndex, name, kind (0 town, 1 boss), life, lifeMax]
function readNpcs() {
    const result = [];
    try {
        const list = Main.npc;
        for (let index = 0; index < list.length && result.length < 60; index++) {
            const npc = list[index];
            if (!npc || !npc.active) {
                continue;
            }
            let head = -1;
            let kind = 0;
            if (npc.townNPC) {
                head = NPCClass.TypeToDefaultHeadIndex(npc.type);
            } else if (npc.boss || safe(() => npc.GetBossHeadTextureIndex(), -1) >= 0) {
                head = safe(() => npc.GetBossHeadTextureIndex(), -1);
                kind = 1;
            }
            if (head < 0 && kind === 0) {
                continue;
            }
            const center = npc.get_Center();
            result.push([Math.floor(center.X / 16), Math.floor(center.Y / 16), head,
                String(safe(() => npc.GivenName, "")) || String(safe(() => npc.TypeName, "")), kind, npc.life, npc.lifeMax]);
        }
    } catch (_) { /* no npc list */ }
    return result;
}

// What the player looks like, for the map marker: skin variant and tint colours.
function readLook(player) {
    const rgb = (c) => [c.R, c.G, c.B];
    try {
        return { skin: [player.skinVariant, ...rgb(player.skinColor)], hair: [player.hair, ...rgb(player.hairColor)],
            eye: rgb(player.eyeColor) };
    } catch (_) {
        return null;
    }
}

const bankNames = { "-2": "Piggy Bank", "-3": "Safe", "-4": "Defender's Forge", "-5": "Void Vault" };

// The container the player has open: world chest (id >= 0) or a bank. null when nothing is open.
function readChest(player) {
    try {
        const id = player.chest;
        if (id === -1) {
            return null;
        }
        const storage = player.GetCurrentContainer();
        let name = bankNames[String(id)] || "";
        if (id >= 0) {
            name = String(safe(() => Main.chest[id].name, "")) || "";
        }
        return { id, name, max: storage.maxItems, items: slots(storage.item, storage.maxItems) };
    } catch (_) {
        return null;
    }
}

function snapshot(player, pane, page) {
    const center = player.get_Center();
    const selectionState = player.selectedItemState;
    const state = {
        selected: player.get_selectedItem(),
        hotbar: selectionState.get_Hotbar(),
        override: selectionState.get_HasActiveOverride(),
        talk: safe(() => player.talkNPC, -1),
        env: environment(player),
        lang: currentLanguage(),
        buffs: readBuffs(player),
        info: readInfo(player),
        chest: readChest(player),
        trash: safe(() => itemState(player.trashItem), [0, 0, 0, false, ""]),
        hp: [Math.max(0, player.statLife), player.statLifeMax2 || player.statLifeMax],
        mana: [Math.max(0, player.statMana), player.statManaMax2 || player.statManaMax],
        position: [Math.floor(center.X / 16), Math.floor(center.Y / 16)],
        inventory: slots(player.inventory, pane === "inventory" ? inventorySize : 10)
    };
    if (pane === "map") {
        state.npcs = readNpcs();
        state.look = readLook(player);
    }
    if (pane === "equipment") {
        state.equipment = equipmentArrays.map((field, index) => slots(player[field], [20, 10, 5, 5][index]));
        state.unlocked = Array.from({ length: 10 }, (_, index) => player.IsItemSlotUnlockedAndUsable(index));
    } else if (pane === "craft") {
        state.recipes = readRecipes(Number.isInteger(page) && page >= 0 ? page : 0);
    }
    return state;
}

// Background texture ids the game itself uses for each biome (TextureAssets.Background indices).
function ids(array) {
    const result = [];
    if (array) {
        for (let index = 0; index < array.length; index++) {
            result.push(array[index]);
        }
    }
    return result;
}

function backgrounds() {
    const read = (field) => {
        try {
            const value = Main[field];
            return typeof value === "number" ? value : ids(value);
        } catch (error) {
            return "ERR " + (error.message || error);
        }
    };
    const result = {};
    for (const field of ["treeBGSet1", "treeBGSet2", "treeBGSet3", "treeBGSet4", "corruptBG", "jungleBG", "snowBG",
        "hallowBG", "crimsonBG", "desertBG", "mushroomBG", "oceanBG", "underworldBG", "treeStyle", "cloudBG"]) {
        result[field] = read(field);
    }
    for (const getter of ["get_bgStyle", "get_undergroundBackground", "get_caveBackground"]) {
        try {
            result[getter.slice(4)] = Main[getter]();
        } catch (error) {
            result[getter.slice(4)] = "ERR " + (error.message || error);
        }
    }
    return result;
}

export { Main, snapshot, select, transfer, craft, backgrounds, slotRef, sameItem, refreshRecipes, playSound };
