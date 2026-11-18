// Loads the whole TL Pro mod against a permissive mock of the game's runtime and walks the snapshot paths.
// It cannot prove the mod works in the game, but it catches what breaks at load time or on a first call:
// syntax errors, missing imports and exports, calls to functions that no longer exist (ReferenceError).
//
//   node tests/mod_load.mjs
import { pathToFileURL } from "node:url";
import path from "node:path";

function mock() {
    const target = function () { return mock(); };
    return new Proxy(target, {
        get(_, key) {
            if (key === "length") return 0;
            if (key === Symbol.toPrimitive) return () => 0;
            if (key === "then") return undefined;
            return mock();
        },
        apply() { return mock(); },
        construct() { return mock(); },
    });
}

globalThis.NativeClass = function () { return mock(); };
globalThis.NativeObject = mock();
globalThis.tl = { log() {}, file: { write() {}, read() { return ""; } } };

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")), "..", "src", "tlpro", "Modified", "1.mod");
const load = (name) => import(pathToFileURL(path.join(root, name)).href);

const fatal = (error) => error && (error.name === "ReferenceError" || error.name === "SyntaxError");
let failures = 0;

function attempt(label, fn) {
    try {
        fn();
    } catch (error) {
        if (fatal(error)) {
            failures++;
            console.log("FAIL", label, "-", error.name + ":", error.message);
        }
    }
}

try {
    await load("main.js");
    const game = await load("game.js");
    const actions = await load("actions.js");
    const player = new Proxy(mock(), { get(t, k) { return k === "dead" ? false : t[k]; } });
    for (const pane of ["inventory", "equipment", "craft", "map", "people"]) {
        attempt("snapshot " + pane, () => game.snapshot(player, pane, 0));
    }
    for (const kind of ["sort", "trashswap", "favorite", "use", "split", "equip", "drop", "togglepet", "togglevisible", "togglelook",
        "cancelbuff", "say", "emote", "talk", "housing", "bannerclaim", "quickstackall", "team", "pvp", "loadout"]) {
        attempt("act " + kind, () => actions.act(player, { kind, slot: 0, index: 0, from: [0, 0], to: [0, 1], id: 0, text: "x", command: "check" }));
    }
    for (const fn of ["readChat", "readBestiary"]) {
        attempt(fn, () => game[fn](player));
    }
    attempt("bestiaryInfo", () => game.bestiaryInfo(1));
    attempt("recipesFor", () => game.recipesFor(player, 1));
} catch (error) {
    failures++;
    console.log("FAIL load -", error && error.stack ? error.stack : error);
}

console.log(failures === 0 ? "mod load test: ok" : "mod load test: " + failures + " failure(s)");
process.exit(failures === 0 ? 0 : 1);
