// Item descriptions, per-item actions and NPC dialogue state for the companion.
// Every action is validated against what the companion believes is in the slot (`expectFrom`), so a stale view
// can never delete, split or drop the wrong item. The game stays the authority.
import * as game from "./game.js";

const Main = game.Main;
const ItemSlot = new NativeClass("Terraria.UI", "ItemSlot");
const ItemSorting = new NativeClass("Terraria.UI", "ItemSorting");
const ChestUI = new NativeClass("Terraria.UI", "ChestUI");
const RecipeClass = new NativeClass("Terraria", "Recipe");

function safe(read, fallback) {
    try {
        const value = read();
        return value === undefined || value === null ? fallback : value;
    } catch (_) {
        return fallback;
    }
}

const ContentSamples = new NativeClass("Terraria.ID", "ContentSamples");

// What a reforge (prefix) changed, in percent against the unmodified item - the lines the game's own tooltip adds.
function prefixMods(item) {
    const mods = [];
    if (!(item.prefix > 0)) {
        return mods;
    }
    try {
        const base = ContentSamples.ItemsByType.get_Item(item.type);
        const add = (kind, value) => {
            if (Number.isFinite(value) && value !== 0) {
                mods.push([kind, value]);
            }
        };
        if (base.damage > 0) {
            add("damage", Math.round((item.damage / base.damage - 1) * 100));
        }
        add("crit", item.crit - base.crit);
        if (base.useAnimation > 0) {
            add("speed", Math.round((1 - item.useAnimation / base.useAnimation) * 100));
        }
        if (base.mana > 0) {
            add("mana", Math.round((item.mana / base.mana - 1) * 100));
        }
        if (base.scale > 0) {
            add("size", Math.round((item.scale / base.scale - 1) * 100));
        }
        if (base.shootSpeed > 0) {
            add("velocity", Math.round((item.shootSpeed / base.shootSpeed - 1) * 100));
        }
        if (base.knockBack > 0) {
            add("knockback", Math.round((item.knockBack / base.knockBack - 1) * 100));
        }
    } catch (_) { /* no base item to compare with */ }
    return mods;
}

function info(player, request) {
    const { item } = game.slotRef(player, request.from);
    if (!item || item.type === 0 || item.stack <= 0) {
        throw new Error("Слот пуст");
    }
    const tooltip = [];
    safe(() => {
        const tip = item.ToolTip;
        const count = tip ? tip.get_Lines() : 0;
        for (let line = 0; line < count; line++) {
            tooltip.push(String(tip.GetLine(line)));
        }
    }, null);
    const kinds = ["melee", "ranged", "magic", "summon"].filter((kind) => safe(() => item[kind], false));
    return {
        type: item.type, name: String(safe(() => item.AffixName(), item.get_Name())), stack: item.stack,
        maxStack: item.maxStack, prefix: item.prefix, rare: safe(() => item.rare, 0), value: safe(() => item.value, 0),
        damage: safe(() => item.damage, 0), crit: safe(() => item.crit, 0), knockBack: safe(() => item.knockBack, 0),
        useTime: safe(() => item.useTime, 0), defense: safe(() => item.defense, 0), pick: safe(() => item.pick, 0),
        axe: safe(() => item.axe, 0), hammer: safe(() => item.hammer, 0), healLife: safe(() => item.healLife, 0),
        healMana: safe(() => item.healMana, 0), mana: safe(() => item.mana, 0),
        accessory: safe(() => item.accessory, false), consumable: safe(() => item.consumable, false),
        ammo: safe(() => item.ammo, 0), vanity: safe(() => item.vanity, false), favorited: !!item.favorited,
        kinds, tooltip, mods: prefixMods(item)
    };
}

function checkedSlot(player, request) {
    const ref = game.slotRef(player, request.from);
    if (!game.sameItem(ref.item, request.expectFrom)) {
        throw new Error("Слот изменился. Открой предмет снова");
    }
    return ref;
}

function split(player, request) {
    const source = checkedSlot(player, request);
    const target = game.slotRef(player, request.to);
    if (request.from[0] !== 0 || request.to[0] !== 0) {
        throw new Error("Разделять можно только внутри сумки");
    }
    if (!target.item.get_IsAir() || !game.sameItem(target.item, request.expectTo)) {
        throw new Error("Выбери пустой слот");
    }
    const stack = source.item.stack;
    const amount = request.amount === "half" ? Math.floor(stack / 2) : 1;
    if (stack < 2 || amount < 1 || !Main.get_mouseItem().get_IsAir()) {
        throw new Error("Нечего разделять");
    }
    // Same total before and after: the clone takes `amount`, the source keeps the rest.
    const part = source.item.DeepClone();
    part.stack = amount;
    source.item.stack = stack - amount;
    target.array[target.index] = part;
    game.refreshRecipes();
}

function equip(player, request) {
    const ref = checkedSlot(player, request);
    const ok = ItemSlot["bool SwapEquip(Item[] inv, int context, int slot)"](ref.array, ref.context, ref.index);
    if (!ok) {
        throw new Error("Предмет нельзя надеть");
    }
    game.refreshRecipes();
}

function wearable(item) {
    return safe(() => item.accessory, false) || safe(() => item.vanity, false) || safe(() => item.headSlot, -1) >= 0 ||
        safe(() => item.bodySlot, -1) >= 0 || safe(() => item.legSlot, -1) >= 0;
}

// "Use" from the inventory: wear it, drink it, or summon with it - always through the game's own logic.
function use(player, request) {
    const ref = checkedSlot(player, request);
    const item = ref.item;
    if (wearable(item) && request.from[0] === 0) {
        equip(player, request);
        return;
    }
    if (safe(() => item.healLife, 0) > 0) {
        player.QuickHeal();
        return;
    }
    if (safe(() => item.healMana, 0) > 0) {
        player.QuickMana();
        return;
    }
    if (safe(() => item.buffType, 0) > 0 && safe(() => item.consumable, false)) {
        player.QuickBuff();
        return;
    }
    if (safe(() => player.SummonItemCheck(item), false)) {
        player["void ItemCheck_UseBossSpawners(int onWhichPlayer, Item sItem)"](player.whoAmI, item);
        if (item.consumable) {
            item.stack -= 1;
            if (item.stack <= 0) {
                item.TurnToAir();
            }
        }
        game.refreshRecipes();
        return;
    }
    throw new Error("Этот предмет нельзя применить отсюда");
}

// Buttons of the chest window, each one runs the game's own routine.
function chestAction(player, request) {
    if (player.chest === -1) {
        throw new Error("Контейнер закрыт");
    }
    switch (request.op) {
        case "loot":
            ChestUI.LootAll();
            break;
        case "deposit":
            ChestUI.DepositAll();
            break;
        case "quick":
            ChestUI["void QuickStack(bool voidStack)"](false);
            break;
        case "restock":
            ChestUI.Restock();
            break;
        case "sort":
            ItemSorting.SortChest();
            break;
        case "close":
            player.chest = -1;
            break;
        default:
            throw new Error("Неизвестная кнопка сундука");
    }
    game.refreshRecipes();
}

const actionSounds = { favorite: "tick", trash: "grab", trashswap: "grab", drop: "grab", use: "grab", split: "grab",
    equip: "grab", take: "grab", deposit: "grab", sort: "tick", heal: "grab", mana: "grab", buff: "grab", mount: "tick",
    grapple: "tick", chest: "open" };

function act(player, request) {
    actWithoutSound(player, request);
    game.playSound(actionSounds[request.kind] || "tick");
}

function actWithoutSound(player, request) {
    if (player.dead) {
        throw new Error("Недоступно, пока персонаж мёртв");
    }
    switch (request.kind) {
        case "favorite": {
            const ref = checkedSlot(player, request);
            ref.item.favorited = !ref.item.favorited;
            break;
        }
        case "trash": {
            const ref = checkedSlot(player, request);
            if (ref.item.favorited) {
                throw new Error("Избранный предмет не удаляется");
            }
            ref.item.TurnToAir();
            game.refreshRecipes();
            break;
        }
        case "drop": {
            const ref = checkedSlot(player, request);
            if (request.from[0] !== 0 || ref.index > 49) {
                throw new Error("Выбросить можно только из сумки и хотбара");
            }
            const hotbar = player.selectedItemState.get_Hotbar();
            if (ref.index === hotbar) {
                player.DropSelectedItem();
            } else {
                // bring the stack into the selected hotbar slot, let the game drop it, put the other item back
                const array = ref.array;
                const held = array[hotbar];
                array[hotbar] = array[ref.index];
                array[ref.index] = held;
                try {
                    player.DropSelectedItem();
                } finally {
                    const gone = array[hotbar];
                    array[hotbar] = array[ref.index];
                    array[ref.index] = gone;
                }
            }
            game.refreshRecipes();
            break;
        }
        case "use":
            use(player, request);
            break;
        case "split":
            split(player, request);
            break;
        case "equip":
            equip(player, request);
            break;
        case "sort":
            ItemSorting["void SortInventory()"]();
            game.refreshRecipes();
            break;
        case "chest":
            chestAction(player, request);
            break;
        case "trashswap": {
            // The game's trash slot: swaps the slot with what lies in the trash, so it is always recoverable.
            const ref = checkedSlot(player, request);
            if (request.from[0] !== 0 && request.from[0] !== 5) {
                throw new Error("В мусор можно класть только из сумки или сундука");
            }
            const moving = ref.item;
            const old = player.trashItem;
            if (!moving.get_IsAir() && moving.favorited) {
                throw new Error("Избранный предмет не удаляется");
            }
            if (!moving.get_IsAir() && !old.get_IsAir() && moving.type === old.type && old.stack < old.maxStack) {
                const room = old.maxStack - old.stack;
                const moved = Math.min(room, moving.stack);
                old.stack += moved;
                moving.stack -= moved;
                if (moving.stack <= 0) {
                    moving.TurnToAir();
                }
            } else {
                player.trashItem = moving;
                ref.array[ref.index] = old;
            }
            game.refreshRecipes();
            break;
        }
        case "take": {
            const ref = checkedSlot(player, request);
            if (request.from[0] !== 5) {
                throw new Error("Это не контейнер");
            }
            ChestUI.LootItem(ref.index);
            game.refreshRecipes();
            break;
        }
        case "deposit": {
            const ref = checkedSlot(player, request);
            if (request.from[0] !== 0 || ref.index > 49 || player.chest === -1) {
                throw new Error("Положить можно из сумки в открытый контейнер");
            }
            ChestUI.Deposit(ref.index);
            game.refreshRecipes();
            break;
        }
        case "heal":
            player.QuickHeal();
            break;
        case "mana":
            player.QuickMana();
            break;
        case "buff":
            player.QuickBuff();
            break;
        case "mount":
            player.QuickMount();
            break;
        case "grapple":
            player.QuickGrapple();
            break;
        default:
            throw new Error("Неизвестное действие");
    }
}

// Read-only view of an open NPC conversation (design probe for the shop/dialogue screen).
function npc() {
    const player = Main.get_LocalPlayer();
    const index = safe(() => player.talkNPC, -1);
    const result = { talk: index, shop: safe(() => Main.get_npcShop(), 0),
        text: String(safe(() => Main.get_npcChatText(), "")),
        buttons: [safe(() => Main.get_npcChatFocus1(), false), safe(() => Main.get_npcChatFocus2(), false),
            safe(() => Main.get_npcChatFocus3(), false), safe(() => Main.get_npcChatFocus4(), false)],
        chest: safe(() => player.chest, -1) };
    if (index >= 0) {
        const entity = safe(() => Main.npc[index], null);
        if (entity) {
            result.name = String(safe(() => entity.GivenName, ""));
            result.kind = String(safe(() => entity.TypeName, ""));
            result.id = safe(() => entity.type, 0);
        }
    }
    return result;
}

export { info, act, npc };
