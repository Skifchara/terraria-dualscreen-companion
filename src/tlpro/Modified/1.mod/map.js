const Main = new NativeClass("Terraria", "Main");
const MapHelper = new NativeClass("Terraria.Map", "MapHelper");
const Lang = new NativeClass("Terraria", "Lang");
const colors = new Map();
const perTick = 640;

function clamp(value, low, high, fallback) {
    return Number.isInteger(value) ? Math.max(low, Math.min(high, value)) : fallback;
}

// The window is chosen by the companion (columns x rows of samples, `step` world tiles apart), so the map can
// fill the bottom screen instead of a fixed 48x32 patch.
function start(player, request) {
    const world = Main.get_Map();
    if (!world || world.MaxWidth < 1 || world.MaxHeight < 1) {
        throw new Error("Карта ещё загружается");
    }
    const width = clamp(request.cols, 16, 128, 48);
    const height = clamp(request.rows, 16, 96, 32);
    const center = player.get_Center();
    const step = [1, 2, 4, 8, 16].includes(request.step) ? request.step : 2;
    const x = Number.isInteger(request.x) ? request.x : Math.floor(center.X / 16);
    const y = Number.isInteger(request.y) ? request.y : Math.floor(center.Y / 16);
    const left = Math.max(0, Math.min(world.MaxWidth - width * step, x - Math.floor(width / 2) * step));
    const top = Math.max(0, Math.min(world.MaxHeight - height * step, y - Math.floor(height / 2) * step));
    return { world, result: { left, top, step, width, height, world: [world.MaxWidth, world.MaxHeight],
        pixels: [] } };
}

function sample(job) {
    const { world, result } = job;
    const total = result.width * result.height;
    const end = Math.min(total, result.pixels.length + perTick);
    while (result.pixels.length < end) {
        const index = result.pixels.length;
        const x = result.left + (index % result.width) * result.step;
        const y = result.top + Math.floor(index / result.width) * result.step;
        let rgb = 0;
        if (x < world.MaxWidth && y < world.MaxHeight && world.IsRevealed(x, y)) {
            const type = MapHelper.CreateMapTile(x, y, 255).Type;
            if (!colors.has(type)) {
                const color = MapHelper.colorLookup[type];
                colors.set(type, (color.R << 16) | (color.G << 8) | color.B);
            }
            rgb = colors.get(type);
        }
        result.pixels.push(rgb);
    }
    return result.pixels.length === total;
}

// What the map shows at one world tile (only tiles the player has already uncovered): the game's own map object name.
function tileInfo(request) {
    const world = Main.get_Map();
    const x = request.x;
    const y = request.y;
    if (!world || !Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0 || x >= world.MaxWidth || y >= world.MaxHeight) {
        throw new Error("Неверная точка карты");
    }
    if (!world.IsRevealed(x, y)) {
        return { x, y, revealed: false, name: "" };
    }
    const type = MapHelper.CreateMapTile(x, y, 255).Type;
    let name = "";
    try {
        name = String(Lang.GetMapObjectName(type));
    } catch (_) { /* no name for this tile */ }
    return { x, y, revealed: true, type, name };
}

export { start, sample, tileInfo };
