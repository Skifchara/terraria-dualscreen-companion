// Sprite export: the game itself renders its own textures to PNG on the user's device.
// Nothing from the game is stored in this repository; the companion caches what it receives locally.
const Main = new NativeClass("Terraria", "Main");
const TextureAssets = new NativeClass("Terraria.GameContent", "TextureAssets");
const MemoryStream = new NativeClass("System.IO", "MemoryStream");
const Convert = new NativeClass("System", "Convert");

// UI textures that the companion asks for by name (TextureAssets static Asset<Texture2D> fields).
const uiNames = new Set(["InventoryBack", "InventoryBack2", "InventoryBack3", "InventoryBack4", "InventoryBack5",
    "InventoryBack6", "InventoryBack7", "InventoryBack8", "InventoryBack9", "InventoryBack10",
    "InventoryBack11", "InventoryBack12", "InventoryBack13", "InventoryBack14", "InventoryBack15",
    "InventoryBack16", "InventoryBack17", "InventoryBack18", "Heart", "Heart2", "Mana", "Hb1", "Hb2",
    "Sun", "Sun2", "Sun3", "CursorRadial", "MagicPixel", "SettingsPanel", "SettingsPanel2", "Map",
    "MapPing", "MapDeath", "ColorSlider", "ColorBar", "BuilderAcc", "BuilderTools", "QuicksIcon", "Trash"]);
// Indexed arrays: name -> TextureAssets field
const arrays = { item: "Item", background: "Background", moon: "Moon", cloud: "Cloud", star: "Star",
    mapbg: "MapBGs", mapicon: "MapIcon", npchead: "NpcHead", bosshead: "NpcHeadBoss", playerhair: "PlayerHair", cursor: "Cursors", buff: "Buff",
    craft: "CraftToggle", sort: "InventorySort", hotbarlock: "HbLock", coin: "Coin" };

function encode(texture) {
    const stream = MemoryStream.new();
    stream["void .ctor()"]();
    texture["void SaveAsPng(Stream stream, int width, int height)"](stream, texture.Width, texture.Height);
    const bytes = stream.ToArray();
    return { png: Convert["string ToBase64String(byte[] inArray)"](bytes), w: texture.Width, h: texture.Height,
        bytes: bytes.length };
}

const nameShape = /^[A-Za-z][A-Za-z0-9]*$/;

function find(request) {
    const kind = String(request.kind);
    if (kind === "ui") {
        const name = String(request.name);
        if (!nameShape.test(name)) {
            throw new Error("Неверное имя текстуры");
        }
        return TextureAssets[name];
    }
    if (kind === "arr") {
        // any indexed texture list of the game, e.g. NpcHead, Coin, CraftToggle, Reforge, Buff
        const name = String(request.name);
        if (!nameShape.test(name) || !Number.isInteger(request.id) || request.id < 0) {
            throw new Error("Неверный спрайт");
        }
        const list = TextureAssets[name];
        if (request.id >= list.length) {
            throw new Error("Спрайт вне диапазона");
        }
        return list[request.id];
    }
    if (kind === "player") {
        // Players[skinVariant, part]: 0 head, 1 eye white, 2 eye pupil
        if (!Number.isInteger(request.id) || !Number.isInteger(request.part) || request.id < 0 || request.part < 0) {
            throw new Error("Неверный спрайт");
        }
        return TextureAssets.Players.GetValue(request.id, request.part);
    }
    const field = arrays[kind];
    if (!field || !Number.isInteger(request.id) || request.id < 0) {
        throw new Error("Неизвестный спрайт");
    }
    if (kind === "item") {
        if (request.id >= TextureAssets.Item.length) {
            throw new Error("Неизвестный предмет");
        }
        Main.instance.LoadItem(request.id);
    }
    if (kind === "background") {
        try {
            Main.instance.LoadBackground(request.id);
        } catch (_) { /* already loaded or not lazily loaded in this build */ }
    }
    const list = TextureAssets[field];
    if (request.id >= list.length) {
        throw new Error("Спрайт вне диапазона");
    }
    return list[request.id];
}

function get(request) {
    const asset = find(request);
    const texture = asset && asset.Value;
    if (!texture) {
        throw new Error("Текстура ещё не загружена");
    }
    const result = encode(texture);
    result.kind = request.kind;
    result.id = request.id;
    result.name = request.name;
    return result;
}

function safe(read) {
    try {
        const value = read();
        return value === undefined ? null : value;
    } catch (error) {
        return "ERR " + (error.message || error);
    }
}

function unityInfo(texture) {
    if (!texture) {
        return null;
    }
    return { w: safe(() => texture.get_width()), h: safe(() => texture.get_height()),
        format: safe(() => String(texture.get_format())), readable: safe(() => texture.get_isReadable()),
        raw: safe(() => texture.GetRawTextureData().length) };
}

// Diagnostics: where the game keeps the pixels of a sprite (atlas page, rectangle, readability).
function probe(request) {
    const texture = find(request).Value;
    const entry = safe(() => texture.PackedEntry);
    const atlas = safe(() => texture._textureAtlas);
    return {
        w: texture.Width, h: texture.Height,
        entry: entry && typeof entry === "object" ? {
            uid: safe(() => entry.TextureId), page: safe(() => entry.AtlasIndex),
            x: safe(() => entry.TextureOffsetX), y: safe(() => entry.TextureOffsetY),
            w: safe(() => entry.TextureWidth), h: safe(() => entry.TextureHeight),
            tw: safe(() => entry.TileWidth), th: safe(() => entry.TileHeight),
            trim: safe(() => entry.TileTrimming), scale: safe(() => entry.TextureScale) } : entry,
        atlas: atlas && typeof atlas === "object" ? {
            id: safe(() => atlas.Id), pages: safe(() => atlas.NumberOfPages),
            low: safe(() => atlas.UseLowResTextures) } : atlas,
        unity: unityInfo(safe(() => texture.get_UnityTexture())),
        alpha: unityInfo(safe(() => texture.get_UnityAlphaTexture())),
        palette: unityInfo(safe(() => texture.get_UnityPalTexture())),
        loaded: safe(() => texture._textureLoaded)
    };
}

// Where the game keeps the pixels of a sprite: atlas database id, page and rectangle inside the page.
// The pixels themselves are decoded on the PC from the user's own installation (tools/extract_assets.py).
// Animated items store all their frames stacked in one sprite; the companion shows the first.
function frameCount(request, texture) {
    const kind = String(request.kind);
    if (kind === "player" || kind === "playerhair") {
        // character sheets stack 56-pixel frames
        return Math.max(1, Math.round(texture.Height / 56));
    }
    if (kind !== "item") {
        return 1;
    }
    try {
        const animation = Main.itemAnimations[request.id];
        return animation ? Math.max(1, animation.FrameCount) : 1;
    } catch (_) {
        return 1;
    }
}

const ContentSamples = new NativeClass("Terraria.ID", "ContentSamples");

// Items such as Gel are drawn from a grey sprite that the game tints with Item.color at draw time.
function tintOf(request) {
    if (String(request.kind) !== "item") {
        return undefined;
    }
    try {
        const item = ContentSamples.ItemsByType.get_Item(request.id);
        const c = item.color;
        if (c.R || c.G || c.B || c.A) {
            return [c.R, c.G, c.B, c.A, item.alpha];
        }
    } catch (_) { /* no tint information */ }
    return undefined;
}

function entryOf(request) {
    const texture = find(request).Value;
    const entry = texture.PackedEntry;
    const atlas = texture._textureAtlas;
    return { tint: tintOf(request), kind: request.kind, id: request.id, name: request.name, w: texture.Width, h: texture.Height,
        db: String(atlas.Id), page: entry.AtlasIndex, x: entry.TextureOffsetX, y: entry.TextureOffsetY,
        sw: entry.TextureWidth, sh: entry.TextureHeight, scale: entry.TextureScale,
        pw: texture.get_UnityTexture().get_width(), ph: texture.get_UnityTexture().get_height(),
        frames: frameCount(request, texture) };
}

// Diagnostics for the tiled background atlas: where an entry keeps its tile table inside TileOffsetData.
function tileClass() {
    const names = ["TextureAtlasEntry.TextureAtlasEntryTiles", "TextureAtlasEntryTiles",
        "TextureAtlasEntry/TextureAtlasEntryTiles", "TextureAtlasEntry+TextureAtlasEntryTiles"];
    const errors = [];
    for (const name of names) {
        try {
            const cls = new NativeClass("", name);
            const data = cls.TileOffsetData;
            if (data) {
                return { cls, data, name };
            }
        } catch (error) {
            errors.push(name + ": " + (error.message || error));
        }
    }
    throw new Error("TileOffsetData: " + errors.join(" | "));
}

function tiles(request) {
    const texture = find(request).Value;
    const entry = texture.PackedEntry;
    const found = tileClass();
    const start = Number.isInteger(request.start) ? request.start : 0;
    const count = Math.min(Number.isInteger(request.count) ? request.count : 256, 4096);
    const offset = entry.TileDataOffset;
    const slice = [];
    const total = found.data.length;
    for (let index = start; index < Math.min(total, start + count); index++) {
        slice.push(found.data[offset + index]);
    }
    return { class: found.name, offset, total, tw: entry.TileWidth, th: entry.TileHeight, trim: entry.TileTrimming,
        w: entry.TextureWidth, h: entry.TextureHeight, page: entry.AtlasIndex, x: entry.TextureOffsetX,
        y: entry.TextureOffsetY, frames: safe(() => found.cls.CalculateFrameCount(entry)), data: slice };
}

// Which background textures the game has loaded so far (it loads them lazily, as they are drawn).
function loadedBackgrounds() {
    const list = TextureAssets.Background;
    const loaded = [];
    for (let index = 0; index < list.length; index++) {
        try {
            const asset = list[index];
            if (asset && asset.IsLoaded) {
                loaded.push(index);
            }
        } catch (_) { /* skip */ }
    }
    const numbers = (field) => safe(() => {
        const value = Main[field];
        const out = [];
        if (value && value.length !== undefined) {
            for (let i = 0; i < value.length; i++) {
                out.push(value[i]);
            }
            return out;
        }
        return value;
    });
    return { loaded, total: list.length, caveBackX: numbers("caveBackX"), caveBackStyle: numbers("caveBackStyle"),
        cave: numbers("caveBackground"), under: numbers("undergroundBackground"), style: numbers("bgStyle"),
        alphaFront: numbers("bgAlphaFrontLayer"), alphaFar: numbers("bgAlphaFarBackLayer") };
}

function textureIds(list) {
    const ids = [];
    for (let index = 0; list && index < list.length && index < 12; index++) {
        const id = list[index];
        if (Number.isInteger(id) && id >= 0) ids.push(id);
    }
    return ids;
}

// Terraria.ID.SurfaceBackgroundID and the tables used by Main.DrawSurfaceBG.
const forestRegions = { 0: 1, 10: 2, 11: 3, 12: 4 };
const surfaceBiomes = {
    1: ["corruptBG", [23, 22]],
    3: ["jungleBG", []],
    6: ["hallowBG", []],
    7: ["snowBG", []],
    8: ["crimsonBG", [24, 42]],
    9: ["mushroomBG", [246, 247]]
};
const desertVariants = { 2: "Pure", 5: "Corrupt", 13: "Hallow", 14: "Crimson" };

function desertLayers(variantName) {
    let ids = [];
    try {
        const variant = Main.desertBackgroundSet[variantName];
        ids = textureIds(variant.get_Backgrounds());
    } catch (_) { /* the plain desert table is available on earlier builds */ }
    if (ids.length === 0) ids = variantName === "Pure" ? textureIds(Main.desertBG) : [26];
    return [24, 25, ...ids];
}

function surfaceBackgrounds() {
    const style = Main.get_bgStyle();
    const region = forestRegions[style];
    if (region) {
        return [...textureIds(Main["treeMntBGSet" + region]), ...textureIds(Main["treeBGSet" + region])];
    }
    if (style === 4) return textureIds([Main.oceanBG]);
    if (desertVariants[style]) return desertLayers(desertVariants[style]);
    const biome = surfaceBiomes[style];
    if (!biome) return [];
    const [field, mountains] = biome;
    const far = style === 7 ? textureIds(Main.snowMntBG) : mountains;
    return [...far, ...textureIds(Main[field])];
}

function currentBackgrounds(env) {
    const zones = env.zones;
    if (zones.includes("UnderworldHeight")) {
        try {
            // DrawUnderworldBackground draws slots 4 through 0 from TextureAssets.Underworld.
            const ids = Main.underworldBG;
            return ids && ids.length >= 5 ? [ids[4], ids[3], ids[2], ids[1], ids[0]] : [];
        } catch (_) {
            return [];
        }
    }
    if (zones.includes("DirtLayerHeight") || zones.includes("RockLayerHeight")) {
        try {
            // Main.DrawCurrentBackground and Main.DrawRockLayer use slots 1 and 3
            // respectively. The game fills this table with the current world's styles.
            const ids = Main.instance.get_backTexture();
            const id = ids[zones.includes("RockLayerHeight") ? 3 : 1];
            return Number.isInteger(id) && id > 0 ? [id] : [];
        } catch (_) {
            return []; // use the companion's cave fallback on builds without this getter
        }
    }
    if (zones.includes("SkyHeight")) return [];
    try {
        return [...new Set(surfaceBackgrounds())];
    } catch (_) {
        return []; // use the companion's biome fallback if a field is unavailable
    }
}

function entries(request) {
    const list = Array.isArray(request.items) ? request.items.slice(0, 64) : [];
    return { entries: list.map((item) => {
        try {
            return entryOf(item);
        } catch (error) {
            return { kind: item.kind, id: item.id, name: item.name, error: String(error.message || error) };
        }
    }) };
}

// Let the game itself draw the sprite (its own palette shader) into a render target and encode that as PNG.
// Every step reports success/failure so a failing API shows up precisely in `log`.
const GraphicsNS = "Microsoft.Xna.Framework.Graphics";
const XnaNS = "Microsoft.Xna.Framework";
let renderTargets = new Map();
const SystemType = new NativeClass("System", "Type");
const SystemEnum = new NativeClass("System", "Enum");

// JS numbers cannot be passed for C# enum parameters; build a real enum value.
function enumValue(namespace, name, member) {
    const full = namespace + "." + name;
    const variants = [() => new NativeClass(namespace, name)[member],
        () => SystemEnum["object Parse(Type enumType, string value)"](
            SystemType["Type GetType(string typeName)"](full + ", Assembly-CSharp"), member),
        () => SystemEnum["object Parse(Type enumType, string value)"](
            SystemType["Type GetType(string typeName)"](full), member)];
    let last;
    for (const variant of variants) {
        try {
            const value = variant();
            if (value !== undefined && value !== null) {
                return value;
            }
        } catch (error) {
            last = error;
        }
    }
    throw last || new Error("enum " + full);
}

// Fallback readback through Unity itself when the shim's SaveAsPng writes nothing.
function describe(object) {
    if (object === null || object === undefined) {
        return "null";
    }
    try {
        return String(object.GetType());
    } catch (_) {
        return "object";
    }
}

function encodeViaUnity(target, width, height, log, fullHeight) {
    const candidates = [["_unityRenderTexture", () => target._unityRenderTexture],
        ["UnityBindTexture", () => target.get_UnityBindTexture()], ["UnityTexture", () => target.get_UnityTexture()],
        ["_unityTexture", () => target._unityTexture]];
    const UnityTexture2D = new NativeClass("UnityEngine", "Texture2D");
    const RenderTexture = new NativeClass("UnityEngine", "RenderTexture");
    const ImageConversion = new NativeClass("UnityEngine", "ImageConversion");
    const Rect = new NativeClass("UnityEngine", "Rect");
    let lastError;
    for (const [name, read] of candidates) {
        let source;
        try {
            source = read();
        } catch (error) {
            log.push("unity." + name + ": ERR " + (error.message || error));
            continue;
        }
        log.push("unity." + name + ": " + describe(source));
        if (!source) {
            continue;
        }
        try {
            const texture = UnityTexture2D.new();
            texture["void .ctor(int width, int height)"](width, height);
            const previous = RenderTexture.get_active();
            RenderTexture.set_active(source);
            const rect = Rect.new();
            rect["void .ctor(float x, float y, float width, float height)"](0, fullHeight ? fullHeight - height : 0, width, height);
            texture["void ReadPixels(Rect source, int destX, int destY)"](rect, 0, 0);
            texture.Apply();
            RenderTexture.set_active(previous);
            const bytes = ImageConversion["byte[] EncodeToPNG(Texture2D tex)"](texture);
            return { png: Convert["string ToBase64String(byte[] inArray)"](bytes), w: width, h: height, bytes: bytes.length };
        } catch (error) {
            lastError = error;
            log.push("unity.read(" + name + "): ERR " + (error.message || error));
        }
    }
    throw lastError || new Error("no unity render texture");
}

const borrowNames = ["screenTarget", "screenTargetSwap", "backgroundTarget", "backgroundTargetSwap", "skyTarget",
    "waterTarget", "backWaterTarget", "tileTarget", "tile2Target", "wallTarget", "blackTarget", "objectsTarget_Small",
    "mapSectionTexture"];

function unityOf(target) {
    for (const read of [() => target._unityRenderTexture, () => target.get_UnityBindTexture(),
        () => target.get_UnityTexture(), () => target._unityTexture]) {
        try {
            const value = read();
            if (value) {
                return value;
            }
        } catch (_) { /* next */ }
    }
    return null;
}

// Which of the game's own render targets exist and carry a Unity render texture.
function targets() {
    const result = [];
    for (const name of borrowNames) {
        try {
            const target = Main[name];
            if (!target) {
                result.push({ name, present: false });
                continue;
            }
            const unity = unityOf(target);
            result.push({ name, present: true, w: target.Width, h: target.Height, unity: describe(unity) });
        } catch (error) {
            result.push({ name, error: String(error.message || error) });
        }
    }
    return { targets: result };
}

// Every numeric/boolean property the shim keeps per sprite: used to find where the palette row comes from.
function inspect(request) {
    const texture = find(request).Value;
    const read = (name, getter) => {
        try {
            const value = getter();
            return value === undefined ? null : value;
        } catch (error) {
            return "ERR " + (error.message || error);
        }
    };
    const entry = read("entry", () => texture.PackedEntry);
    const atlas = read("atlas", () => texture._textureAtlas);
    const out = {
        tex: {}, entry: {}, atlas: {}
    };
    for (const name of ["TextureOffsetScale", "SharedBatching", "NonSharedHeadInsert", "TexelWidth", "TexelHeight",
        "ArraySize", "UnityWidth", "UnityHeight", "Width", "Height", "BatchTextureIndex", "_batchTextureIndex",
        "_textureLoaded", "loadRequested"]) {
        out.tex[name] = read(name, () => texture[name]);
    }
    if (entry && typeof entry === "object") {
        for (const name of ["TextureId", "AtlasIndex", "TextureOffsetX", "TextureOffsetY", "TextureWidth", "TextureHeight",
            "TileWidth", "TileHeight", "TileTrimming", "TextureScale", "TileDataOffset"]) {
            out.entry[name] = read(name, () => entry[name]);
        }
    }
    if (atlas && typeof atlas === "object") {
        out.atlas.Id = read("Id", () => String(atlas.Id));
        out.atlas.NumberOfPages = read("NumberOfPages", () => atlas.NumberOfPages);
        for (const name of ["TextureRemap", "BatchTextureIndex", "TextureWidth", "TextureHeight"]) {
            out.atlas[name] = read(name, () => {
                const array = atlas[name];
                const items = [];
                for (let index = 0; index < array.length && index < 16; index++) {
                    items.push(array[index]);
                }
                return items;
            });
        }
    }
    return out;
}

function render(request) {
    const log = [];
    // Runs the first variant that works; every failure is logged so the next iteration knows what to change.
    const attempt = (name, ...variants) => {
        let lastError;
        for (let index = 0; index < variants.length; index++) {
            try {
                const value = variants[index]();
                log.push(name + "#" + index + ": ok");
                return value;
            } catch (error) {
                lastError = error;
                log.push(name + "#" + index + ": ERR " + (error.message || error));
            }
        }
        throw lastError;
    };
    try {
        const texture = attempt("find", () => find(request).Value);
        const width = texture.Width;
        const height = texture.Height;
        const device = attempt("device", () => Main.instance.get_GraphicsDevice());
        const key = width + "x" + height;
        let target = null;
        let borrowedHeight = 0;
        for (const name of borrowNames) {
            try {
                const candidate = Main[name];
                if (candidate && candidate.Width >= width && candidate.Height >= height && unityOf(candidate)) {
                    target = candidate;
                    borrowedHeight = candidate.Height;
                    log.push("borrowing " + name + " " + candidate.Width + "x" + candidate.Height);
                    break;
                }
            } catch (_) { /* not usable */ }
        }
        if (!target) {
            target = renderTargets.get(key);
        }
        if (!target) {
            const RenderTarget2D = new NativeClass(GraphicsNS, "RenderTarget2D");
            target = attempt("target", () => {
                const created = RenderTarget2D.new();
                created["void .ctor(GraphicsDevice device, int width, int height)"](device, width, height);
                return created;
            });
            renderTargets.set(key, target);
        }
        const Vector2 = new NativeClass(XnaNS, "Vector2");
        const Color = attempt("colorclass", () => new NativeClass(GraphicsNS, "Color"),
            () => new NativeClass(XnaNS, "Color"));
        const white = attempt("white", () => Color.White, () => Color.get_White());
        const clear = attempt("transparent", () => Color.Transparent, () => Color.get_Transparent());
        const batch = Main.spriteBatch;
        const origin = attempt("vector", () => {
            const v = Vector2.new();
            v["void .ctor(float x, float y)"](0, 0);
            return v;
        }, () => Vector2.get_Zero());
        attempt("bind", () => device.SetRenderTarget(target));
        attempt("clear", () => device["void Clear(Color color)"](clear));
        const mode = attempt("sortmode", () => enumValue(GraphicsNS, "SpriteSortMode", "Deferred"),
            () => enumValue(GraphicsNS, "SpriteSortMode", "Immediate"));
        attempt("begin", () => batch["void Begin(SpriteSortMode sortMode, bool defferedBatch)"](mode, false));
        const rectangle = () => {
            const Rectangle = new NativeClass(XnaNS, "Rectangle");
            const r = Rectangle.new();
            r["void .ctor(int x, int y, int width, int height)"](0, 0, width, height);
            return r;
        };
        attempt("draw", () => batch["void Draw(Texture2D texture, Vector2 position, Color color)"](texture, origin, white),
            () => batch["void Draw(Texture2D texture, Rectangle destinationRectangle, Color color)"](texture, rectangle(), white));
        attempt("end", () => batch.End());
        // Read while the target is still bound: the shim may hand the Unity render texture back on unbind.
        let image;
        try {
            image = attempt("encode", () => {
                const first = encode(target);
                if (first.bytes > 0) {
                    return first;
                }
                log.push("SaveAsPng wrote 0 bytes (bound)");
                return encodeViaUnity(target, width, height, log, borrowedHeight);
            });
        } finally {
            attempt("unbind", () => device.SetRenderTarget(null));
        }
        return { log, w: width, h: height, png: image.png, bytes: image.bytes };
    } catch (error) {
        try {
            Main.instance.get_GraphicsDevice().SetRenderTarget(null);
        } catch (_) { /* nothing bound */ }
        return { log, error: String(error.message || error) };
    }
}

const FontAssets = new NativeClass("Terraria.GameContent", "FontAssets");
const fonts = { mouse: "MouseText", stack: "ItemStack", death: "DeathText" };
// Basic Latin, Latin-1/Extended-A, Cyrillic, general punctuation: enough for English and Russian.
const wanted = (code) => (code >= 32 && code < 383) || (code >= 1024 && code < 1280) ||
    (code >= 8208 && code < 8231) || code === 8470;

// Glyph atlas of one of the game's own fonts (textures as PNG + metrics), so the companion can draw text
// with exactly the game's lettering, including Cyrillic.
function font(request) {
    const field = fonts[String(request.name)];
    if (!field) {
        throw new Error("Неизвестный шрифт");
    }
    const sprite = FontAssets[field].Value;
    const textures = sprite.get_Textures();
    const glyphs = sprite.get_Glyphs();
    const result = { name: request.name, line: sprite.get_LineSpacing(), spacing: sprite.get_Spacing(),
        textures: [], glyphs: [] };
    if (request.glyphs !== false) {
        for (let index = 0; index < glyphs.length; index++) {
            const glyph = glyphs[index];
            const code = glyph.Character.charCodeAt ? glyph.Character.charCodeAt(0) : Number(glyph.Character);
            if (!wanted(code)) {
                continue;
            }
            const bounds = glyph.BoundsInTexture;
            const crop = glyph.Cropping;
            result.glyphs.push([code, bounds.X, bounds.Y, bounds.Width, bounds.Height, crop.X, crop.Y,
                glyph.LeftSideBearing, glyph.RightSideBearing, glyph.Width, glyph.TexureIndex]);
        }
    }
    if (Number.isInteger(request.texture)) {
        result.texture = request.texture;
        result.image = encode(textures[request.texture]);
    } else {
        result.textureCount = textures.length;
        result.sizes = Array.from({ length: textures.length }, (_, i) => [textures[i].Width, textures[i].Height]);
    }
    return result;
}

export { get, font, probe, entries, tiles, loadedBackgrounds, currentBackgrounds, render, targets, inspect };
