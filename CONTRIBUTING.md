# Contributing

Thanks for wanting to help! This is a small volunteer project; every report, idea and patch counts. Русский текст — ниже.

## Ground rules (please read)

1. **No game assets.** Do not commit or attach Terraria's sprites, sounds, fonts, atlas pages, APKs, dumps (`dump.cs`,
   `il2cpp.h`, `global-metadata.dat`, `libil2cpp.so`) or decoded textures. Everything is decoded locally from the user's own copy.
2. **No piracy and no protection bypass.** The project never modifies, re-signs or unprotects the game APK, and issues or
   PRs about cracked builds, PairIP/licence workarounds and the like will be closed.
3. **The game stays the authority.** Read the real state; change it only through the game's own routines, validating what the
   UI believes (`expectFrom` / `expectTo`) so a stale view can never move or delete the wrong item.
4. **Single-player only.**
5. Be kind — see the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to help

- **Test and report** — device, Android version, game version, TL Pro version, steps, and logs
  (`adb logcat -d | grep -i terraduo`). Reports from devices other than the AYN Thor are especially useful.
- **Pick a task** — open issues labelled `good first issue` / `help wanted`. Ideas that need hands:
  NPC dialogue and shop actions (buy / sell / chat buttons), underground backgrounds that match the game exactly,
  the player's head sprite on the map, chest / bank edge cases, controller shortcuts, accessibility, translations,
  automated tests for the pure logic.
- **Document** — fix and translate the guides, add device notes.

## Development setup

```
JAVA_HOME=<jdk 17+>   ANDROID_SDK_ROOT=<sdk with build-tools and platforms/android-33>
python tools/build.py          # APK, dev-signed (tools/dev.keystore is created on first run; never reuse it for releases)
python tools/build_tlpro.py    # mod pack
```

Companion code is plain Java 8 source level built with `javac`, `d8`, `aapt2` — **no Gradle**. Two gotchas learned the hard way:
use top-level, non-generic classes for anything `d8` has to chew on (anonymous classes and generic framework subclasses crashed
it), and build with `-g:none`.

Mod code lives in `src/tlpro/Modified/1.mod/*.js`. It runs inside TL Pro's runtime. Rules of thumb:
- Methods with overloads need a signature string: `obj["void SortInventory()"]()`.
- JS numbers cannot be passed as C# enum parameters.
- Do **not** hook arbitrary game methods without a test world open: a hook whose callback receives a null object crashes the
  whole game on world entry (`TL::Mods::HookManager::V8HookCallback` → `NativeObject::construct`). Prefer polling in the
  existing `Main.DoUpdate` hook, and keep experimental hooks behind a clearly named list.
- The mod is loaded once per game start: restart the game through TL Pro after every change.

Useful tools: `tools/tl_client.py` (talk to the mod over `adb forward tcp:38656 tcp:38656`),
`tools/extract_assets.py` and `tools/extract_backgrounds.py` (prepare sprites from your own game).

Check before you open a PR:

```
for f in src/tlpro/Modified/1.mod/*.js; do node --input-type=module --check < "$f"; done
python tools/build.py
```

## Pull requests

- Small and focused; describe *what* and *why*, and how you tested it (device, world, steps). Screenshots without game art are best;
  if you must show the UI, crop to the parts you changed.
- Keep user-visible strings in `Strings.java` in **both** English and Russian (or ask for a translation in the PR).
- Match the surrounding style; no unrelated reformatting.
- By contributing you agree that your work is released under the project's [MIT license](LICENSE).

---

## Участие (кратко по-русски)

Мы рады любой помощи: тесты и отчёты (особенно с других устройств), документация и переводы, код.
Правила: **нет ресурсов игры** в репозитории, **никакого обхода защиты и пиратских сборок**, игра остаётся главной
(проверяем ожидаемое состояние слотов), только одиночная игра.
Сборка: `python tools/build.py` (нужны JDK, Android SDK, Python), мод — `python tools/build_tlpro.py`.
Не вешайте хуки на произвольные методы игры без проверки в тестовом мире: хук с пустым объектом роняет игру при входе в мир.
Pull request’ы — небольшие и с описанием проверки; строки интерфейса добавляйте на английском и русском.
