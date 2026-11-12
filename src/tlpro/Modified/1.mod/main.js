import * as game from "./game.js";
import * as map from "./map.js";
import * as sprites from "./sprites.js";
import * as actions from "./actions.js";
import * as shop from "./shop.js";
const Main = game.Main;
const TcpListener = new NativeClass("System.Net.Sockets", "TcpListener");
const IPAddress = new NativeClass("System.Net", "IPAddress");
const Encoding = new NativeClass("System.Text", "Encoding");
const Type = new NativeClass("System", "Type");
const Enum = new NativeClass("System", "Enum");
const port = 38656;
const limit = 1024;

let listener;
let selectRead;
let encoding;
let buffer;
let connection;
let nextTick = 0;
let nextListen = 0;
let hideUntil = 0;
let hideMap = false;
let mirrorChest = -1;
let mirrorShop = 0;
let mirroredWasOpen = false;
let inventoryShutUntil = 0;   // after a mirrored chest / shop closes the game may flag its inventory open for a few frames
const hudHooks = [];

function report(message) {
    tl.log(message);
    tl.file.write("terraduo-status.txt", message);
}

function mirroredInteraction() {
    return (mirrorChest !== -1 && Main.get_LocalPlayer().chest === mirrorChest) ||
        (mirrorShop > 0 && Main.get_npcShop() === mirrorShop);
}

function finishMirroredInteraction(force = false) {
    if (Main.gameMenu || Main.netMode !== 0 || (Date.now() >= hideUntil && !force)) {
        mirroredWasOpen = false;
        return;
    }
    if ((mirroredWasOpen || force) && Main.get_LocalPlayer().chest === -1 && Main.get_npcShop() === 0) {
        inventoryShutUntil = Date.now() + 1500;
    }
    if ((mirroredWasOpen || force) && Main.get_LocalPlayer().chest === -1 && Main.get_npcShop() === 0 &&
        Main.get_playerInventory()) {
        try {
            GUIInstanceClass.Active.GUIPageIcons["void Close(bool hideChat)"](false);
        } catch (_) {
            Main.set_playerInventory(false);
        }
    }
    mirroredWasOpen = mirroredInteraction();
}

function hideWhileConnected(className, method, shouldHide) {
    try {
        new NativeClass("", className)[method].hook((original, self) => {
            guardInventory();
            if (Date.now() >= hideUntil || Main.gameMenu || Main.netMode !== 0 ||
                (Main.get_playerInventory() && !yWindow() && !mirroredInteraction()) || !shouldHide()) {
                original(self);
            }
        });
        hudHooks.push(className);
    } catch (error) {
        tl.log("Terraduo HUD " + className + ": " + error);
    }
}

// Everything the bottom screen already shows is hidden on the top screen while "Hide HUD" is on.
// (Each hook falls through to the original while the game's own inventory is open.)
for (const [name, method] of [["GUIHearts", "Draw"], ["GUIMana", "Draw"], ["GUIHotbar", "Draw"], ["GUIBuffs", "Draw"],
    ["GUIAccessoryInfo", "Draw"], ["GUIBuilderAccToggles", "Draw"], ["GUIControlsStatusIndicators", "Draw"],
    ["GUIPVPIcons", "Draw"], ["GUIMagnify", "Draw"], ["GUIEquipmentUtility", "Draw"], ["GUIInventory", "Draw"]]) {
    hideWhileConnected(name, method, () => true);
}
hideWhileConnected("GUIMap", "DrawMap", () => hideMap && !Main.get_mapFullscreen());
// Keep the container and its inventory session open. Suppress only the mirrored page's rendering.
hideWhileConnected("GUIPageIcons", "Draw", mirroredInteraction);

function closeConnection() {
    const current = connection;
    connection = undefined;
    if (current) {
        current.client.Close();
    }
}

function stopListening() {
    closeConnection();
    if (listener) {
        const current = listener;
        listener = undefined;
        current.Stop();
    }
}

function openListener() {
    // The TL API accepts a boxed enum for Socket.Poll.
    const enumType = Type["Type GetType(string typeName)"]("System.Net.Sockets.SelectMode, System");
    selectRead = Enum["object Parse(Type enumType, string value)"](enumType, "SelectRead");
    encoding = Encoding.get_UTF8();
    buffer = encoding["byte[] GetBytes(string s)"](" ".repeat(limit));
    const server = TcpListener.new();
    server["void .ctor(IPAddress localaddr, int port)"](IPAddress.Loopback, port);
    server["void Start(int backlog)"](2);
    listener = server;
    report("Terraduo listening on 127.0.0.1:" + port + "; HUD hooks " + hudHooks.join(", "));
}

function send(state) {
    const bytes = encoding["byte[] GetBytes(string s)"](JSON.stringify(state) + "\n");
    connection.stream["void Write(byte[] buffer, int offset, int size)"](bytes, 0, bytes.length);
    closeConnection();
}

function readRequest() {
    const available = Math.min(connection.client.get_Client().get_Available(), limit);
    if (available < 1) {
        return null;
    }
    const count = connection.stream["int Read(byte[] buffer, int offset, int size)"](buffer, 0, available);
    if (!connection.text && count === 1 && buffer[0] >= 0 && buffer[0] <= 2) {
        const code = buffer[0];
        return { op: code === 0 ? "state" : "select", slot: code - 1, legacy: true };
    }
    connection.text += encoding["string GetString(byte[] bytes, int index, int count)"](buffer, 0, count);
    if (connection.text.length > limit) {
        throw new Error("Команда слишком длинная");
    }
    const end = connection.text.indexOf("\n");
    return end < 0 ? null : JSON.parse(connection.text.slice(0, end));
}

function respond(player, request, error, mapState) {
    const state = game.snapshot(player, request.pane, request.page);
    if (request.legacy) {
        const slots = state.inventory.slice(0, 2).map(([type, stack, prefix]) => ({ type, stack, prefix }));
        send({ selected: state.selected, slots, message: error || "live" });
        return;
    }
    state.pad = pad;
    state.shop = shop.stock(player);
    state.hud = Date.now() < hideUntil;
    state.hudAvailable = hudHooks.length !== 0;
    state.bgNow = sprites.currentBackgrounds(state.env);
    if (mapState) {
        state.map = mapState;
    }
    if (error) {
        state.error = error;
    }
    send(state);
}

function runRequest(player, request, now) {
    connection.request = request;
    if (!request.legacy) {
        hideUntil = request.hide === true ? now + 1700 : 0;
        hideMap = request.hide === true;
        mirrorChest = Number.isInteger(request.mirrorChest) ? request.mirrorChest : -1;
        mirrorShop = Number.isInteger(request.mirrorShop) ? request.mirrorShop : 0;
        finishMirroredInteraction();
    }
    switch (request.op) {
        case "state":
            respond(player, request);
            break;
        case "select":
            if (player.dead) {
                throw new Error("Хотбар недоступен");
            }
            game.select(player, request.slot);
            connection.selectionDeadline = now + 480;
            break;
        case "transfer":
            if (player.dead) {
                throw new Error("Инвентарь недоступен");
            }
            game.transfer(player, request);
            report("Terraduo transferred " + request.from + " → " + request.to);
            respond(player, request);
            break;
        case "craft":
            if (player.dead) {
                throw new Error("Крафт недоступен");
            }
            game.craft(request);
            report("Terraduo crafted recipe " + request.id);
            respond(player, request);
            break;
        case "map":
            connection.map = map.start(player, request);
            break;
        case "sprite":
            send(sprites.get(request));
            break;
        case "font":
            send(sprites.font(request));
            break;
        case "probe":
            send(sprites.probe(request));
            break;
        case "render":
            send(sprites.render(request));
            break;
        case "inspect":
            send(sprites.inspect(request));
            break;
        case "targets":
            send(sprites.targets());
            break;
        case "entries":
            send(sprites.entries(request));
            break;
        case "tiles":
            send(sprites.tiles(request));
            break;
        case "loadedbg":
            send(sprites.loadedBackgrounds());
            break;
        case "backgrounds":
            send(game.backgrounds());
            break;
        case "info":
            send(actions.info(player, request));
            break;
        case "act":
            if (request.kind === "shop") shop.act(player, request);
            else actions.act(player, request);
            if ((request.kind === "chest" || request.kind === "shop") && request.command === "close") {
                finishMirroredInteraction(true);
            }
            report("Terraduo action " + request.kind);
            respond(player, request);
            break;
        case "npc":
            send({ ...actions.npc(), canShop: shop.canOpen(player) });
            break;
        case "sound":
            game.playSound(String(request.name));
            respond(player, request);
            break;
        default:
            throw new Error("Неизвестная команда Terraduo");
    }
}

function serve(now) {
    if (Main.gameMenu || Main.netMode !== 0) {
        hideUntil = 0;
        stopListening();
        return;
    }
    if (!listener) {
        if (now >= nextListen) {
            openListener();
        }
        return;
    }
    if (!connection) {
        if (!listener.get_Server().Poll(0, selectRead)) {
            return;
        }
        const client = listener.AcceptTcpClient();
        const stream = client.GetStream();
        stream.set_WriteTimeout(40);
        connection = { client, stream, text: "", deadline: now + 1000 };
    }

    const player = Main.get_LocalPlayer();
    const request = connection.request;
    if (connection.map) {
        if (map.sample(connection.map)) {
            respond(player, request, null, connection.map.result);
        }
    } else if (connection.selectionDeadline) {
        if (player.get_selectedItem() === request.slot) {
            report("Terraduo selected " + request.slot);
            respond(player, request);
        } else if (now >= connection.selectionDeadline) {
            respond(player, request, "Игра пока удерживает текущий слот");
        }
    } else {
        const next = readRequest();
        if (next) {
            runRequest(player, next, now);
        } else if (now >= connection.deadline) {
            closeConnection();
        }
    }
}

// With the game HUD hidden, make sure the controller bumpers still change the hotbar slot. The game normally does this
// itself; if it did not move the slot after a bumper press, do it here (never both). Several sources are read because
// the port's input layer is not documented: the game's own triggers and Unity's joystick buttons (L1 = 4, R1 = 5).
let PlayerInput;
let UnityInput;
let keyL1;
let keyR1;
let keyY;
try {
    PlayerInput = new NativeClass("Terraria.GameInput", "PlayerInput");
} catch (_) { /* no controller support to rescue */ }
try {
    UnityInput = new NativeClass("UnityEngine", "Input");
    const KeyCode = new NativeClass("UnityEngine", "KeyCode");
    keyL1 = KeyCode.JoystickButton4;
    keyR1 = KeyCode.JoystickButton5;
    keyY = KeyCode.JoystickButton3;
} catch (_) { /* Unity input not reachable */ }

const pad = { plus: 0, minus: 0, curPlus: 0, curMinus: 0, l1: 0, r1: 0, y: 0, rescued: 0, using: false, error: "" };
let prev = { plus: false, minus: false, l1: false, r1: false, y: false };
let yPressed = false;

function unityKey(code) {
    try {
        return !!UnityInput["bool GetKey(KeyCode key)"](code);
    } catch (_) {
        try {
            return !!UnityInput.GetKey(code);
        } catch (error) {
            pad.error = "unity: " + (error.message || error);
            return false;
        }
    }
}

function bumperStep() {
    let plus = false;
    let minus = false;
    try {
        const triggers = PlayerInput.get_Triggers();
        pad.using = !!PlayerInput.get_UsingGamepad();
        if (triggers.JustPressed.get_HotbarPlus()) {
            pad.plus++;
            plus = true;
        }
        if (triggers.JustPressed.get_HotbarMinus()) {
            pad.minus++;
            minus = true;
        }
        const curPlus = !!triggers.Current.get_HotbarPlus();
        const curMinus = !!triggers.Current.get_HotbarMinus();
        if (curPlus && !prev.plus) {
            pad.curPlus++;
            plus = true;
        }
        if (curMinus && !prev.minus) {
            pad.curMinus++;
            minus = true;
        }
        prev.plus = curPlus;
        prev.minus = curMinus;
    } catch (error) {
        pad.error = "triggers: " + (error.message || error);
    }
    if (UnityInput) {
        const l1 = unityKey(keyL1);
        const r1 = unityKey(keyR1);
        if (l1 && !prev.l1) {
            pad.l1++;
            minus = true;
        }
        if (r1 && !prev.r1) {
            pad.r1++;
            plus = true;
        }
        prev.l1 = l1;
        prev.r1 = r1;
        const y = unityKey(keyY);
        yPressed = y && !prev.y;
        if (yPressed) {
            pad.y++;
        }
        prev.y = y;
    }
    return plus ? 1 : minus ? -1 : 0;
}

// The game opens its inventory on Y while processing input; refuse that while the companion owns the tabs.
let lastYAt = 0;
let yBefore = false;

function noteY() {
    if (!UnityInput) {
        return;
    }
    const y = unityKey(keyY);
    if (y && yBefore) {
        lastYAt = Date.now();
    }
    if (y && !yBefore) {
        lastYAt = Date.now();
        try {
            inventoryAtY = !!Main.get_playerInventory();
        } catch (_) {
            inventoryAtY = false;
        }
    }
    yBefore = y;
}

// While the companion owns Y, an inventory that the game opens because of it is closed again at once. Called every
// frame from the update hook and from each hidden HUD part, so it also catches a toggle that happens while drawing.
let inventoryAtY = false;

let GUIInstanceClass;
try {
    GUIInstanceClass = new NativeClass("", "GUIInstance");
} catch (_) { /* no page UI */ }

// True while a Y press that the companion owns is in progress (held, or released less than 0.9 s ago).
function yWindow() {
    return Date.now() < hideUntil && !inventoryAtY && Date.now() - lastYAt < 900;
}

function guardInventory() {
    const afterContainer = Date.now() < hideUntil && Date.now() < inventoryShutUntil;
    if ((!yWindow() && !afterContainer) || mirroredInteraction()) {
        return;
    }
    try {
        if (Main.get_playerInventory()) {
            // close it the way the game's own UI does: the page UI owns the open state and re-opens the inventory
            // every frame if only the flag is cleared
            try {
                GUIInstanceClass.Active.GUIPageIcons["void Close(bool hideChat)"](true);
            } catch (_) {
                try {
                    Main.get_LocalPlayer().ToggleInv();
                } catch (__) {
                    Main.set_playerInventory(false);
                }
            }
            if (Main.get_playerInventory()) {
                Main.set_playerInventory(false);
            }
            pad.blocked = (pad.blocked || 0) + 1;
        }
    } catch (_) { /* not in a world */ }
}

// The game toggles its inventory only when Player.releaseInventory is set; keeping it cleared while Y is held (and the
// companion owns Y) stops the toggle without hooking anything.
function holdInventoryShut() {
    if (Date.now() >= hideUntil || !UnityInput) {
        return;
    }
    try {
        if (unityKey(keyY) || Date.now() - lastYAt < 400) {
            Main.get_LocalPlayer().releaseInventory = false;
        }
    } catch (_) { /* not in a world */ }
}

function rescueBumpers(before, inventoryBefore) {
    const step = bumperStep();
    guardInventory();
    if (step === 0 || Date.now() >= hideUntil || Main.gameMenu || Main.get_playerInventory()) {
        return;
    }
    try {
        const player = Main.get_LocalPlayer();
        if (player.selectedItemState.get_Hotbar() === before) {
            player.ScrollHotbar(step);
            pad.rescued++;
        }
    } catch (error) {
        pad.error = "scroll: " + (error.message || error);
    }
}

Main.DoUpdate.hook((original, self, gameTime) => {
    let before = -1;
    let inventoryBefore = false;
    try {
        before = Main.get_LocalPlayer().selectedItemState.get_Hotbar();
        inventoryBefore = !!Main.get_playerInventory();
    } catch (_) { /* not in a world */ }
    noteY();
    holdInventoryShut();
    original(self, gameTime);
    finishMirroredInteraction();
    rescueBumpers(before, inventoryBefore);
    const now = Date.now();
    if (now < nextTick) {
        return;
    }
    nextTick = now + 30;
    try {
        serve(now);
    } catch (error) {
        const message = error.message || String(error);
        if (connection) {
            try {
                send({ error: message });
            } catch (_) {
                closeConnection();
            }
        } else {
            nextListen = now + 2000;
            hideUntil = 0;
        }
        report("Terraduo: " + message);
    }
});
