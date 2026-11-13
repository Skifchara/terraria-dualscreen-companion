import * as game from "./game.js";

const Main = game.Main;
const NPCInteractions = new NativeClass("Terraria.GameContent", "NPCInteractions");
const ItemSlot = new NativeClass("Terraria.UI", "ItemSlot");
const ItemSettings = new NativeClass("Terraria", "GetItemSettings");
const CurrencyId = new NativeClass("Terraria.ID", "CustomCurrencyID");
// Mobile's NPCInteractions.Initialize registers these NPC types in shop index order.
const vendorTypes = [0, 17, 19, 20, 38, 54, 107, 108, 124, 142, 160, 178, 207, 208, 209, 227, 228, 229,
    353, 368, 453, 550, 588, 633, 663];

function vendor(player) {
    const index = player.talkNPC;
    if (index < 0 || index >= Main.npc.length) return null;
    const npc = Main.npc[index];
    return npc.active ? npc : null;
}

function shopOption(npc) {
    try {
        const interactions = NPCInteractions.All;
        for (let index = 0; index < interactions.get_Count(); index++) {
            const option = interactions.get_Item(index);
            try {
                if (option._shopIndex > 0 && option._npcType === npc.type && option.Condition()) return option;
            } catch (_) { /* this interaction is not a shop */ }
        }
    } catch (_) { /* some mobile menus use the built-in shop indexes directly */ }
    return null;
}

function storage(id) {
    return Main.instance.get_shop()[id];
}

function price(player, item) {
    const currency = item.shopSpecialCurrency;
    let amount = item.GetStoreValue();
    if (currency >= 0) return currency === CurrencyId.DefenderMedals ? [amount, 3817] : null;
    const adjustment = player.currentShoppingSettings.PriceAdjustment;
    if (!(adjustment > 0) || amount < 0) return null;
    if (player.discountAvailable && !item.buyOnce) amount = Math.trunc(Math.fround(amount * Math.fround(0.8)));
    const adjusted = Math.fround(item.buyOnce ? amount / adjustment : amount * adjustment);
    const floor = Math.floor(adjusted);
    const rounded = adjusted - floor === 0.5 && floor % 2 === 0 ? floor : Math.round(adjusted);
    return [item.buyOnce && amount > 0 ? Math.max(1, Math.trunc(rounded / 5)) : rounded, 0];
}

function stock(player) {
    const id = Main.get_npcShop();
    const npc = id > 0 ? vendor(player) : null;
    if (!npc) return null;
    const inventory = storage(id);
    const items = game.slots(inventory.item, inventory.maxItems);
    const prices = items.map(([type], index) => {
        try {
            return type ? price(player, inventory.item[index]) : null;
        } catch (_) {
            return null;
        }
    });
    return { id, talk: player.talkNPC, name: npc.get_GivenOrTypeName(), items, prices };
}

let nearbyAt = 0;
let nearbyCache = [];

// Town NPCs within reach of the player: [npcIndex, type, name, headIndex, distanceInTiles, hasShop]. Refreshed twice a second.
function nearby(player) {
    const now = Date.now();
    if (now - nearbyAt < 500) {
        return nearbyCache;
    }
    nearbyAt = now;
    const result = [];
    try {
        const center = player.get_Center();
        const list = Main.npc;
        for (let index = 0; index < list.length; index++) {
            const npc = list[index];
            if (!npc || !npc.active || !npc.townNPC) {
                continue;
            }
            const there = npc.get_Center();
            const distance = Math.hypot(there.X - center.X, there.Y - center.Y) / 16;
            if (distance <= 14) {
                result.push([index, npc.type, String(npc.get_GivenOrTypeName()),
                    new NativeClass("Terraria", "NPC").TypeToDefaultHeadIndex(npc.type), Math.round(distance),
                    vendorTypes.indexOf(npc.type) > 0 || !!shopOption(npc)]);
            }
        }
    } catch (_) { /* leave the list empty */ }
    result.sort((a, b) => a[4] - b[4]);
    nearbyCache = result.slice(0, 8);
    return nearbyCache;
}

function canOpen(player) {
    const npc = vendor(player);
    return !!npc && (vendorTypes.indexOf(npc.type) > 0 || !!shopOption(npc));
}

function act(player, request) {
    const npc = vendor(player);
    if (!npc || player.talkNPC !== request.talk || !Main.get_mouseItem().get_IsAir() ||
        Main.LocalPlayerHasPendingInventoryActions() || Main.get_mouseLeft()) {
        throw new Error("Торговля сейчас недоступна");
    }
    const id = Main.get_npcShop();
    if (request.command === "open" && id === 0) {
        const option = shopOption(npc);
        if (option) option.Interact();
        else {
            const index = vendorTypes.indexOf(npc.type);
            if (index <= 0) throw new Error("У этого NPC нет магазина");
            Main.instance.OpenShop(index);
        }
        return;
    }
    if (id <= 0 || request.shop !== id) throw new Error("Магазин закрылся");
    if (request.command === "close") {
        Main.SetNPCShopIndex(0);
        Main.CloseNPCChatOrSign();
        return;
    }
    const inventory = storage(id);
    if (request.command === "buy") {
        const index = request.slot;
        const items = inventory.item;
        if (!Number.isInteger(index) || index < 0 || index >= Math.min(inventory.maxItems, items.length) ||
            items[index].get_IsAir() || !game.sameItem(items[index], request.expectFrom)) {
            throw new Error("Ассортимент изменился");
        }
        const quote = price(player, items[index]);
        if (!quote || JSON.stringify(quote) !== JSON.stringify(request.price)) throw new Error("Цена изменилась");
        const item = items[index].DeepClone();
        item.stack = 1;
        if (!player["ItemSpaceStatus ItemSpace(Item newItem)"](item).CanTakeItem) {
            throw new Error("Нет места для покупки");
        }
        const split = Main.get_stackSplit();
        const fastStack = Main.get_superFastStack();
        const cursor = Main.get_cursorOverride();
        try {
            Main.set_stackSplit(0);
            Main.set_superFastStack(0);
            Main.set_cursorOverride(0);
            Main.set_mouseLeft(true);
            ItemSlot["void HandleShopSlot(Item[] inv, int slot, bool rightClickIsValid, bool leftClickIsValid)"](
                items, index, false, true);
        } finally {
            Main.set_mouseLeft(false);
            Main.set_stackSplit(split);
            Main.set_superFastStack(fastStack);
            Main.set_cursorOverride(cursor);
        }
        const bought = Main.get_mouseItem();
        if (bought.get_IsAir()) throw new Error("Покупка недоступна или не хватает денег");
        player.GetOrDropItem(bought.DeepClone(), ItemSettings.InventoryUIToInventorySettings);
        bought.TurnToAir();
    } else if (request.command === "sell") {
        const ref = game.slotRef(player, request.from);
        if (request.from[0] !== 0 || ref.index >= 50 || ref.item.get_IsAir() || ref.item.favorited ||
            !game.sameItem(ref.item, request.expectFrom)) {
            throw new Error("Этот предмет нельзя продать");
        }
        if (!player.SellItem(ref.item, -1)) throw new Error("Игра отклонила продажу");
        try {
            inventory.AddItemToShop(ref.item);
        } finally {
            ref.item.TurnToAir();
        }
        game.playSound("coins");
    } else {
        throw new Error("Неизвестное действие магазина");
    }
    game.refreshRecipes();
}

export { nearby, stock, canOpen, act };
