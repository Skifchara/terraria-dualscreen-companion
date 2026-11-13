const Main = new NativeClass("Terraria", "Main");
const ItemSlot = new NativeClass("Terraria.UI", "ItemSlot");
const Recipe = new NativeClass("Terraria", "Recipe");
const CraftingRequests = new NativeClass("Terraria.GameContent", "CraftingRequests");
const GUIInstance = new NativeClass("", "GUIInstance");
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

// Result of the last housing check / move-in, shown on the People tab for a short while.
let lastHousing = null;

function setHousing(result) {
    lastHousing = Object.assign({ at: Date.now() }, result);
}

// Armour / wings the player chose to hide on the character (the game only has eye buttons for accessories).
// Applied after every update, so the equipment itself (defence, set bonuses, flight) is untouched.
const hidden = { armor: [false, false, false], wings: false };

function toggleHidden(kind, slot) {
    if (kind === "wings") {
        hidden.wings = !hidden.wings;
    } else if (Number.isInteger(slot) && slot >= 0 && slot <= 2) {
        hidden.armor[slot] = !hidden.armor[slot];
    } else {
        throw new Error("Неверный слот");
    }
}

function applyHidden(player) {
    if (!hidden.wings && !hidden.armor[0] && !hidden.armor[1] && !hidden.armor[2]) {
        return;
    }
    if (hidden.armor[0]) {
        player.head = -1;
    }
    if (hidden.armor[1]) {
        player.body = -1;
    }
    if (hidden.armor[2]) {
        player.legs = -1;
    }
    if (hidden.wings) {
        player.wings = 0;
    }
}

// Resolved on first use so a missing class can never stop the whole mod from loading.
const lazyClasses = new Map();

function lazyClass(namespace, name) {
    const key = namespace + "." + name;
    if (!lazyClasses.has(key)) {
        lazyClasses.set(key, new NativeClass(namespace, name));
    }
    return lazyClasses.get(key);
}

// The game's chat log: [text, r, g, b, secondsLeft], newest first. The phone build keeps it in a ChatLine array, the PC style
// monitor in a list of containers; whichever the game runs is read.
function readChat() {
    const result = [];
    const monitor = Main.chatMonitor;
    try {
        const lines = monitor.chatLine;
        if (lines) {
            for (let index = 0; index < lines.length && result.length < 40; index++) {
                const line = lines[index];
                if (line && line.originalText) {
                    const color = line.color;
                    result.push([String(line.originalText), color.R, color.G, color.B, line.showTime]);
                }
            }
            return result;
        }
    } catch (_) { /* not the phone monitor */ }
    try {
        const messages = monitor._messages;
        const count = messages.get_Count();
        for (let index = count - 1; index >= 0 && result.length < 40; index--) {
            const message = messages.get_Item(index);
            const color = message._color;
            result.push([String(message.OriginalText), color.R, color.G, color.B, message._timeLeft]);
        }
    } catch (_) { /* no chat log */ }
    return result;
}

// Say something (or run a /command) exactly as if it had been typed into the game's chat.
function sendChat(player, text) {
    const value = String(text).trim();
    if (!value || value.length > 250) {
        throw new Error("Пустое сообщение");
    }
    if (Main.netMode === 0) {
        // Single player has no server to bounce the message off: handle commands locally, show plain text as the game does.
        if (value.startsWith("/")) {
            const commands = lazyClass("Terraria.Chat", "ChatManager").Commands;
            commands.ProcessIncomingMessage(commands.CreateOutgoingMessage(value), player.whoAmI);
        } else {
            Main["void NewText(string newText, byte R, byte G, byte B, bool onlyCurrentPlayer)"]("<" + player.name + "> " + value, 255, 255, 255, false);
        }
        return;
    }
    const message = lazyClass("Terraria.Chat", "ChatMessage").new();
    message["void .ctor(string message)"](value);
    lazyClass("Terraria.Chat", "ChatHelper").SendChatMessageFromClient(message);
}

// An emote bubble above the player's head, from the game's own emote list.
function sendEmote(player, id) {
    if (!Number.isInteger(id) || id < 0 || id > 150) {
        throw new Error("Неизвестная эмоция");
    }
    const anchor = lazyClass("Terraria.GameContent.UI", "WorldUIAnchor").new();
    anchor["void .ctor(Entity anchor)"](player);
    lazyClass("Terraria.GameContent.UI", "EmoteBubble").NewBubble(id, anchor, 360);
}

// 0 other, 1 weapon, 2 tool, 3 armour / accessory, 4 potion / food, 5 block / material. Cached per type.
const categories = new Map();

function categoryOf(item) {
    const type = item.type;
    if (categories.has(type)) {
        return categories.get(type);
    }
    let category = 0;
    try {
        if (item.pick > 0 || item.axe > 0 || item.hammer > 0) {
            category = 2;
        } else if (item.damage > 0 && !(item.ammo > 0)) {
            category = 1;
        } else if (item.headSlot >= 0 || item.bodySlot >= 0 || item.legSlot >= 0 || item.accessory) {
            category = 3;
        } else if (item.consumable && (item.buffType > 0 || item.healLife > 0 || item.healMana > 0)) {
            category = 4;
        } else if (item.createTile >= 0 || item.createWall >= 0 || item.material) {
            category = 5;
        }
    } catch (_) { /* leave as other */ }
    categories.set(type, category);
    return category;
}

function itemState(item) {
    if (!item || item.type === 0 || item.stack <= 0) {
        return [0, 0, 0, false, "", 0];
    }
    return [item.type, item.stack, item.prefix, item.favorited, item.get_Name(), categoryOf(item)];
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
        !key.every(Number.isInteger) || key[0] < 0 || key[0] > 6 || key[1] < 0) {
        throw new Error("Некорректный слот");
    }
    const [group, index] = key;
    if (group === 6 && index === 0) {
        return { index, item: player.trashItem };
    }
    if (group === 6) {
        throw new Error("Некорректный слот корзины");
    }
    if (group === 5) {
        if (player.chest === -1) {
            throw new Error("Контейнер закрыт");
        }
        const storage = player.GetCurrentContainer();
        const items = storage.item;
        if (index >= Math.min(storage.maxItems, items.length)) {
            throw new Error("Некорректный слот");
        }
        return { array: items, index, context: player.chest >= 0 ? 3 : 4, item: items[index], storage };
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

function describeRecipe(id, recipe, player) {
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
    return [id, itemState(recipe.createItem), ingredients, tile, needs];
}

// Every recipe that makes an item, and every recipe that uses it as an ingredient (the Guide's "what can I craft with this").
function recipesFor(player, type) {
    const makes = [];
    const uses = [];
    if (!Number.isInteger(type) || type <= 0) {
        throw new Error("Неверный предмет");
    }
    const recipes = Main.recipe;
    const total = Math.min(Recipe.numRecipes, recipes.length);
    for (let id = 0; id < total && (makes.length < 40 || uses.length < 40); id++) {
        const recipe = recipes[id];
        if (!recipe || !recipe.createItem || recipe.createItem.type === 0) {
            continue;
        }
        if (recipe.createItem.type === type && makes.length < 40) {
            makes.push(describeRecipe(id, recipe, player));
            continue;
        }
        if (uses.length < 40) {
            const required = recipe.requiredItem;
            for (let k = 0; k < Math.min(required.length, 32); k++) {
                const entry = required[k];
                if (!entry || entry.type === 0) {
                    break;
                }
                if (entry.type === type) {
                    uses.push(describeRecipe(id, recipe, player));
                    break;
                }
            }
        }
    }
    let name = "";
    try {
        name = String(Lang.GetItemNameValue(type));
    } catch (_) { /* no name */ }
    return { recipesFor: { type, name, makes, uses } };
}

// Banners the player has earned (every 50 kills) and can claim: [bannerId, itemType, claimable, kills, name]
const BannerSystem = new NativeClass("Terraria.GameContent", "BannerSystem");

function readBanners() {
    const result = [];
    try {
        const counts = BannerSystem.GetClaimableBannerCounts();
        for (let id = 0; id < counts.length && result.length < 120; id++) {
            const claimable = counts[id];
            if (claimable > 0) {
                const itemType = safe(() => BannerSystem.BannerToItem(id), 0);
                result.push([id, itemType, claimable, safe(() => BannerSystem.GetKillCount(id), 0),
                    itemType > 0 ? String(safe(() => Lang.GetItemNameValue(itemType), "")) : ""]);
            }
        }
    } catch (_) { /* no banner system */ }
    return result;
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
            result.push(describeRecipe(id, recipe, player));
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
    if ((request.from[0] === 5 || request.to[0] === 5) && request.container !== player.chest) {
        throw new Error("Открыт другой контейнер");
    }
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
        if (player.chest >= 0) {
            if (source.storage) source.storage.SyncToChestItem(source.index);
            if (destination.storage) destination.storage.SyncToChestItem(destination.index);
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
        if (player.chest >= 0) {
            if (source.storage) source.storage.SyncToChestItem(source.index);
            if (destination.storage) destination.storage.SyncToChestItem(destination.index);
        }
        throw error;
    } finally {
        Main.set_mouseLeft(false);
        Main.set_mouseLeftRelease(release);
    }
    if (sameItem(source.array[source.index], request.expectFrom) &&
        sameItem(destination.array[destination.index], request.expectTo)) {
        throw new Error("Игра оставила слоты без изменений");
    }
    if (player.chest >= 0) {
        if (source.storage) source.storage.SyncToChestItem(source.index);
        if (destination.storage) destination.storage.SyncToChestItem(destination.index);
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
        worldId: safe(() => Main.worldID, 0), worldName: String(safe(() => Main.worldName, "")),
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

// The game's bestiary: [npcId, name, kills, seen, talked, kind (0 enemy, 1 boss, 2 town NPC, 3 critter), frames, unlock state,
// background texture id (-1 none, null not read yet), [habitat names] (null not read yet)].
let bestiaryAt = 0;
let bestiaryCache = [];
let bestiaryPending = 0;
const bestiaryMeta = new Map();

// The game's own rule for how much of an entry the player may see, from kills / sightings / conversations.
function unlockStateOf(entry, npc, kills, seen, talked, kind) {
    try {
        const provider = entry.UIInfoProvider;
        const needed = provider._killCountNeededToFullyUnlock;
        const quick = !!provider._quickUnlock;
        const value = Number(lazyClass("Terraria.GameContent.Bestiary", "CommonEnemyUICollectionInfoProvider")[
            "BestiaryEntryUnlockState GetUnlockStateByKillCount(int killCount, bool quickUnlock, int fullKillCountNeeded)"](
            kills, quick, needed));
        if (Number.isFinite(value)) {
            return value === 0 && seen ? 1 : value;
        }
    } catch (_) { /* another kind of provider */ }
    if (kind === 2) {
        return talked ? 4 : seen ? 1 : 0;
    }
    if (kind === 1 || kind === 3) {
        return kills > 0 ? 4 : seen ? 1 : 0;
    }
    return kills >= 50 ? 4 : kills >= 25 ? 3 : kills >= 1 ? 2 : seen ? 1 : 0;
}

// Habitat tags and the portrait background of an entry; read a few entries per call so the list never stalls the game.
function metaOf(entry, text) {
    const meta = { bg: -1, tags: [] };
    const info = entry.Info;
    const count = info.get_Count();
    for (let index = 0; index < count; index++) {
        const element = info.get_Item(index);
        let tagged = false;
        try {
            tagged = element._filterIconFrame !== undefined;
        } catch (_) { /* not a habitat element */ }
        if (!tagged) {
            continue;
        }
        try {
            meta.tags.push(text(String(element._key)));
        } catch (_) { /* no name */ }
        if (meta.bg < 0) {
            try {
                const path = String(element._backgroundImagePath);
                const match = /MapBG(\d+)/.exec(path);
                if (match) {
                    meta.bg = Number(match[1]) - 1;
                }
            } catch (_) { /* no background */ }
        }
    }
    return meta;
}

function readBestiary() {
    const now = Date.now();
    if (now - bestiaryAt < 700 || (bestiaryPending === 0 && now - bestiaryAt < 4000)) {
        return bestiaryCache;
    }
    bestiaryAt = now;
    const result = [];
    let budget = 70;
    let pending = 0;
    try {
        const database = Main.BestiaryDB;
        const tracker = Main.BestiaryTracker;
        const samples = lazyClass("Terraria.ID", "ContentSamples").NpcsByNetId;
        const language = lazyClass("Terraria.Localization", "Language");
        const text = (key) => String(language["string GetTextValue(string key)"](key));
        const total = Main.npcFrameCount.length;
        for (let id = 1; id < total; id++) {
            try {
                const entry = database.FindEntryByNPCID(id);
                if (!entry) {
                    continue;
                }
                const npc = samples.get_Item(id);
                if (!npc) {
                    continue;
                }
                const kills = safe(() => tracker.Kills["int GetKillCount(NPC npc)"](npc), 0);
                const seen = safe(() => tracker.Sights["bool GetWasNearbyBefore(NPC npc)"](npc), false);
                const talked = safe(() => tracker.Chats["bool GetWasChatWith(NPC npc)"](npc), false);
                const kind = npc.townNPC ? 2 : npc.boss ? 1 : safe(() => npc.CountsAsACritter, false) ? 3 : 0;
                let meta = bestiaryMeta.get(id);
                if (!meta && budget > 0) {
                    budget--;
                    meta = safe(() => metaOf(entry, text), { bg: -1, tags: [] });
                    bestiaryMeta.set(id, meta);
                }
                if (!meta) {
                    pending++;
                }
                const state = unlockStateOf(entry, npc, kills, seen, talked, kind);
                result.push([id, String(Lang.GetNPCNameValue(id)), kills, seen || kills > 0 || state > 0 ? 1 : 0, talked ? 1 : 0,
                    kind, Math.max(1, Main.npcFrameCount[id]), state, meta ? meta.bg : null, meta ? meta.tags : null]);
            } catch (_) { /* skip this entry */ }
        }
    } catch (_) { /* no bestiary in this build */ }
    bestiaryCache = result;
    bestiaryPending = pending;
    return result;
}

function bestiaryPendingCount() {
    return bestiaryPending;
}

// One bestiary page, like the game's: stats, flavour text, where it lives and what it drops (each part as far as the game has
// unlocked it for this player).
function bestiaryInfo(id) {
    if (!Number.isInteger(id) || id < 1) {
        throw new Error("Неверный NPC");
    }
    const database = Main.BestiaryDB;
    const tracker = Main.BestiaryTracker;
    const entry = database.FindEntryByNPCID(id);
    if (!entry) {
        throw new Error("Нет записи в бестиарии");
    }
    const samples = lazyClass("Terraria.ID", "ContentSamples").NpcsByNetId;
    const npc = samples.get_Item(id);
    const language = lazyClass("Terraria.Localization", "Language");
    const kills = safe(() => tracker.Kills["int GetKillCount(NPC npc)"](npc), 0);
    const seen = safe(() => tracker.Sights["bool GetWasNearbyBefore(NPC npc)"](npc), false);
    const talked = safe(() => tracker.Chats["bool GetWasChatWith(NPC npc)"](npc), false);
    const kind = npc.townNPC ? 2 : npc.boss ? 1 : safe(() => npc.CountsAsACritter, false) ? 3 : 0;
    const result = { id, name: String(Lang.GetNPCNameValue(id)), kills,
        state: unlockStateOf(entry, npc, kills, seen, talked, kind), biomes: [], drops: [],
        flavor: "", catches: 0, stats: null, bg: -1 };
    const info = entry.Info;
    const count = info.get_Count();
    for (let index = 0; index < count; index++) {
        const element = info.get_Item(index);
        const text = (key) => String(language["string GetTextValue(string key)"](key));
        try {
            if (element.LifeMax !== undefined && element.Damage !== undefined) {
                result.stats = { life: element.LifeMax, damage: element.Damage, defense: element.Defense,
                    knockback: element.KnockbackResist, value: element.MonetaryValue };
                continue;
            }
        } catch (_) { /* not a stats element */ }
        try {
            const rate = element._droprateInfo;
            if (rate !== undefined && rate !== null) {
                result.drops.push([rate.itemId, rate.stackMin, rate.stackMax, rate.dropRate]);
                continue;
            }
        } catch (_) { /* not a drop */ }
        try {
            if (element._itemType !== undefined) {
                result.catches = element._itemType;
                continue;
            }
        } catch (_) { /* not a catch item */ }
        try {
            if (element._filterIconFrame !== undefined) {
                const key = String(element._key);
                if (key.length > 0) {
                    result.biomes.push(text(key));
                }
                if (result.bg < 0) {
                    const match = /MapBG(\d+)/.exec(String(element._backgroundImagePath));
                    if (match) {
                        result.bg = Number(match[1]) - 1;
                    }
                }
                continue;
            }
        } catch (_) { /* not a spawn condition */ }
        let namePlate = false;
        try {
            namePlate = element._npcNetId !== undefined;
        } catch (_) { /* no such field */ }
        if (!namePlate) {
            try {
                const key = String(element._key);
                if (key.startsWith("Bestiary_FlavorText")) {
                    result.flavor = text(key);
                }
            } catch (_) { /* not flavour text */ }
        }
    }
    result.drops.sort((a, b) => b[3] - a[3]);
    result.drops = result.drops.slice(0, 40);
    result.dropNames = result.drops.map((drop) => safe(() => String(Lang.GetItemNameValue(drop[0])), ""));
    return { bestiaryinfo: result };
}

// Active buffs and debuffs: [type, ticks left, isDebuff, localised name, isPet, slot, 1 pet / 2 light pet / 0 other]
function readBuffs(player) {
    const result = [];
    try {
        for (let index = 0; index < 22; index++) {
            const type = player.buffType[index];
            if (type > 0) {
                result.push([type, player.buffTime[index], !!Main.debuff[type], buffName(type),
                    !!(Main.vanityPet[type] || Main.lightPet[type]), index, Main.vanityPet[type] ? 1 : Main.lightPet[type] ? 2 : 0]);
            }
        }
    } catch (_) { /* buffs unavailable */ }
    return result;
}

// What the info accessories (watch, depth meter, compass, weather radio) would show, from the game's own formulas.
function readInfo(player) {
    const info = {};
    try {
        const display = GUIInstance.Active.GUIAccessoryInfo;
        display.BuildInfoAccs();
        const lines = display.InfoText;
        const icons = display.DisplayIcons;
        info.accessories = [];
        for (let i = 0; i < Math.min(display.numInfoAccessories, lines.length, icons.length); i++) {
            if (lines[i]) info.accessories.push([icons[i], String(lines[i]), safe(() => Number(display.InfoState[i]), 0)]);
        }
        if (info.accessories.length) return info;
    } catch (_) { /* keep the basic readouts if this game version has a different GUI */ }
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
            info.layer = player.get_ZoneSkyHeight() ? "space" : player.get_ZoneUnderworldHeight() ? "underworld"
                : player.get_ZoneRockLayerHeight() ? "caverns" : player.get_ZoneDirtLayerHeight() ? "underground" : "surface";
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

// The page the game calls housing / players / team: town NPCs with their homes, other players, PvP and team.
function readPeople(player) {
    const npcs = [];
    const players = [];
    try {
        const list = Main.npc;
        for (let index = 0; index < list.length && npcs.length < 40; index++) {
            const npc = list[index];
            if (npc && npc.active && npc.townNPC) {
                npcs.push([NPCClass.TypeToDefaultHeadIndex(npc.type), String(safe(() => npc.GivenName, "")) || String(safe(() => npc.TypeName, "")),
                    !!npc.homeless, npc.homeTileX, npc.homeTileY, npc.type, npc.life, npc.lifeMax, index]);
            }
        }
    } catch (_) { /* no npc list */ }
    try {
        const all = Main.player;
        for (let index = 0; index < all.length && players.length < 16; index++) {
            const other = all[index];
            if (other && other.active && index !== Main.myPlayer) {
                players.push([String(safe(() => other.name, "")), other.team, !!other.hostile, other.statLife, other.statLifeMax2]);
            }
        }
    } catch (_) { /* no player list */ }
    return { npcs, players, team: safe(() => player.team, 0), pvp: !!safe(() => player.hostile, false) };
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
        housing: lastHousing && Date.now() - lastHousing.at < 20000 ? lastHousing : null,
        defense: safe(() => player.statDefense, 0),
        loadout: safe(() => player.CurrentLoadoutIndex, 0),
        team: safe(() => player.team, 0),
        pvp: !!safe(() => player.hostile, false),
        trash: safe(() => itemState(player.trashItem), [0, 0, 0, false, ""]),
        hp: [Math.max(0, player.statLife), player.statLifeMax2 || player.statLifeMax, player.statLifeMax],
        mana: [Math.max(0, player.statMana), player.statManaMax2 || player.statManaMax],
        hudStyle: safe(() => Number(GUIInstance.Active.GUIHearts.ActiveSet), 1),
        position: [Math.floor(center.X / 16), Math.floor(center.Y / 16)],
        inventory: slots(player.inventory, pane === "inventory" ? inventorySize : 10)
    };
    if (pane === "map") {
        state.npcs = readNpcs();
        state.look = readLook(player);
    }
    if (pane === "people") {
        state.people = readPeople(player);
    }
    if (pane === "equipment") {
        state.equipment = equipmentArrays.map((field, index) => slots(player[field], [20, 10, 5, 5][index]));
        // which accessory slots have their look hidden (the game's eye buttons), by armour slot 0..9
        state.hideLook = [hidden.armor[0], hidden.armor[1], hidden.armor[2], hidden.wings];
        state.hideAcc = [];
        for (let index = 0; index < 10; index++) {
            state.hideAcc.push(!!safe(() => player.hideVisibleAccessory[index], false));
        }
        state.unlocked = Array.from({ length: 10 }, (_, index) => player.IsItemSlotUnlockedAndUsable(index));
    } else if (pane === "craft") {
        state.recipes = readRecipes(Number.isInteger(page) && page >= 0 ? page : 0);
        state.banners = readBanners();
        state.guideMenu = !!safe(() => Main.get_InGuideCraftMenu(), false);
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

export { bestiaryPendingCount, bestiaryInfo, readBestiary, toggleHidden, applyHidden, readChat, sendChat, sendEmote, recipesFor, setHousing, Main, snapshot, select, transfer, craft, backgrounds, slots, slotRef, sameItem, refreshRecipes, playSound };
