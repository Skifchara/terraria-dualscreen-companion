#!/usr/bin/env python3
"""One-step installer for Terraduo Companion (run it from the PC, with the device connected over USB).

  python tools/install.py            (or double-click Install.bat on Windows)

What it does, in order:
  1. finds adb (or offers to download Google's official platform-tools),
  2. checks that the device is connected and authorised,
  3. installs the companion app (Terraduo-Companion-*.apk next to this folder),
  4. copies the TL Pro mod pack (*.tl) to the device's Download folder,
  5. decodes the game's sprites from YOUR installed Terraria and copies them to the app (nothing from the game is
     bundled or distributed; Python packages are installed into tools/venv only if they are missing).

Options:  --yes            answer "yes" to every question
          --serial ID      use this device when several are connected
          --apk FILE / --mod FILE   use these files instead of the ones next to the installer
          --skip-sprites   only install the app and the mod
          --sprites        decode and copy the sprites again even if they are already on the device
          --game-apk FILE  decode this copy of the game's base.apk instead of pulling it from the device
"""
import argparse
import glob
import os
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
COMPANION = "com.terraduo.companion"
GAME = "com.and.games505.TerrariaPaid"
WIN = os.name == "nt"
CACHE = os.path.join(ROOT, "assets-cache")

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def say(en, ru=None):
    print(en)
    if ru:
        print("   " + ru)


def step(number, en, ru):
    print("\n[%s] %s\n    %s" % (number, en, ru))


def ask(en, ru, auto):
    print("\n? %s\n  %s" % (en, ru))
    if auto:
        print("  -> yes")
        return True
    answer = input("  [Y/n] ").strip().lower()
    return answer in ("", "y", "yes", "д", "да")


def run(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", **kwargs)


# ------------------------------------------------------------------ adb

def find_adb():
    candidates = [os.environ.get("TERRADUO_ADB"), shutil.which("adb"),
                  os.path.join(TOOLS, "platform-tools", "adb.exe" if WIN else "adb")]
    for home in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if home:
            candidates.append(os.path.join(home, "platform-tools", "adb.exe" if WIN else "adb"))
    for path in candidates:
        if path and os.path.isfile(path):
            return path
    return None


def download_platform_tools():
    system = {"Windows": "windows", "Darwin": "darwin", "Linux": "linux"}.get(platform.system())
    if not system:
        return None
    url = "https://dl.google.com/android/repository/platform-tools-latest-%s.zip" % system
    target = os.path.join(TOOLS, "_platform-tools.zip")
    say("Downloading Google's platform-tools (adb) ...", "Скачиваю platform-tools (adb) с сайта Google ...")
    urllib.request.urlretrieve(url, target)
    with zipfile.ZipFile(target) as archive:
        archive.extractall(TOOLS)
    os.remove(target)
    adb = os.path.join(TOOLS, "platform-tools", "adb.exe" if WIN else "adb")
    if not WIN and os.path.isfile(adb):
        os.chmod(adb, 0o755)
    return adb if os.path.isfile(adb) else None


def get_adb(auto):
    adb = find_adb()
    if adb:
        return adb
    if ask("adb (Android platform-tools) was not found. Download it from Google now?",
           "adb не найден. Скачать его с сайта Google сейчас?", auto):
        adb = download_platform_tools()
        if adb:
            return adb
    sys.exit("adb is required: https://developer.android.com/tools/releases/platform-tools  /  нужен adb, установи platform-tools.")


def pick_device(adb, serial):
    run([adb, "start-server"])
    lines = run([adb, "devices"]).stdout.splitlines()[1:]
    devices = [line.split("\t") for line in lines if "\t" in line]
    if serial:
        devices = [d for d in devices if d[0] == serial]
    ready = [d[0] for d in devices if d[1] == "device"]
    if ready:
        if len(ready) > 1:
            say("Several devices are connected: %s - using the first one (choose another with --serial)." % ", ".join(ready),
                "Подключено несколько устройств, беру первое (другое: --serial).")
        return ready[0]
    for _, state in devices:
        if state == "unauthorized":
            sys.exit("The device asks to allow USB debugging: look at its screen, tap \"Allow\", then run the installer again.\n"
                     "Устройство ждёт разрешения USB-отладки: нажми «Разрешить» на экране и запусти установщик снова.")
    sys.exit("No device found. Connect it with USB and enable Developer options -> USB debugging.\n"
             "Устройство не найдено. Подключи его по USB и включи «Отладка по USB» в параметрах разработчика.")


# ------------------------------------------------------------------ steps

def first(patterns):
    for pattern in patterns:
        found = sorted(glob.glob(pattern))
        if found:
            return found[-1]
    return None


def install_app(adb, serial, apk, auto):
    result = run([adb, "-s", serial, "install", "-r", apk])
    text = result.stdout + result.stderr
    if "Success" in text:
        say("Companion app installed.", "Приложение установлено.")
        return
    if "UPDATE_INCOMPATIBLE" in text or "signatures do not match" in text:
        if ask("A copy of the app signed with another key is installed. Remove it first? "
               "(its decoded sprites are removed too and will be restored in step 5)",
               "Установлена копия, подписанная другим ключом. Удалить её? (спрайты тоже удалятся, шаг 5 вернёт их)", auto):
            run([adb, "-s", serial, "uninstall", COMPANION])
            result = run([adb, "-s", serial, "install", apk])
            text = result.stdout + result.stderr
            if "Success" in text:
                say("Companion app installed.", "Приложение установлено.")
                return
    sys.exit("Installing the app failed:\n" + text.strip())


def push_mod(adb, serial, mod):
    name = os.path.basename(mod)
    run([adb, "-s", serial, "push", mod, "/sdcard/Download/" + name], env=dict(os.environ, MSYS_NO_PATHCONV="1"))
    say("Mod pack copied to the device: Download/%s" % name, "Мод скопирован на устройство: Download/%s" % name)


def atlas_present(adb, serial):
    out = run([adb, "-s", serial, "shell", "ls", "/sdcard/Android/data/%s/files/atlas" % COMPANION],
              env=dict(os.environ, MSYS_NO_PATHCONV="1")).stdout
    return len([line for line in out.splitlines() if line.strip().endswith(".png")]) > 50


def python_with_tools(auto):
    try:
        import PIL  # noqa: F401
        import UnityPy  # noqa: F401
        return sys.executable
    except ImportError:
        pass
    venv = os.path.join(TOOLS, "venv")
    python = os.path.join(venv, "Scripts" if WIN else "bin", "python.exe" if WIN else "python")
    if not os.path.isfile(python):
        say("Creating a private Python environment (tools/venv) ...", "Создаю отдельное окружение Python (tools/venv) ...")
        subprocess.check_call([sys.executable, "-m", "venv", venv])
    say("Installing Python packages (UnityPy, Pillow) - needs internet ...", "Ставлю пакеты Python (UnityPy, Pillow), нужен интернет ...")
    subprocess.check_call([python, "-m", "pip", "install", "-q", "-r", os.path.join(TOOLS, "requirements.txt")])
    return python


def pull_game(adb, serial, given):
    if given:
        return given
    out = run([adb, "-s", serial, "shell", "pm", "path", GAME]).stdout.splitlines()
    if not out:
        return None
    os.makedirs(CACHE, exist_ok=True)
    target = os.path.join(CACHE, "installed-base.apk")
    say("Copying Terraria's APK from the device (for decoding only; it is not modified) ...",
        "Копирую APK Terraria с устройства (только для чтения спрайтов, он не изменяется) ...")
    run([adb, "-s", serial, "pull", out[0].split(":", 1)[1].strip(), target])
    return target if os.path.isfile(target) else None


def sprites(adb, serial, game_apk, auto):
    apk = pull_game(adb, serial, game_apk)
    if not apk:
        say("Terraria (Google Play version) was not found on the device - skipping sprites.",
            "Terraria (версия из Google Play) на устройстве не найдена, спрайты пропущены.")
        return False
    python = python_with_tools(auto)
    env = dict(os.environ, TERRADUO_ADB=adb, ANDROID_SERIAL=serial, PYTHONIOENCODING="utf-8")
    for script in ("extract_assets.py", "extract_backgrounds.py"):
        path = os.path.join(TOOLS, script)
        if not os.path.isfile(path):
            continue
        say("Running %s ..." % script, "Запускаю %s ..." % script)
        code = subprocess.call([python, path, "--apk", apk, "--push"], env=env, cwd=ROOT)
        if code != 0:
            say("%s failed (exit code %d)." % (script, code), "%s завершился с ошибкой (код %d)." % (script, code))
            return False
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--yes", action="store_true")
    parser.add_argument("--serial")
    parser.add_argument("--apk")
    parser.add_argument("--mod")
    parser.add_argument("--skip-sprites", action="store_true")
    parser.add_argument("--sprites", action="store_true")
    parser.add_argument("--game-apk")
    args = parser.parse_args()

    apk = args.apk or first([os.path.join(ROOT, "Terraduo-Companion-*.apk"), os.path.join(ROOT, "build", "terraduo-companion.apk")])
    mod = args.mod or first([os.path.join(ROOT, "Terraduo-Companion-mod-*.tl"), os.path.join(ROOT, "build", "terraduo-hotbar.tl")])
    if not apk or not os.path.isfile(apk):
        sys.exit("Terraduo-Companion-*.apk was not found next to the installer. / APK не найден рядом с установщиком.")

    print("Terraduo Companion installer")
    print("This never touches or re-signs Terraria; saves are not modified.  /  Игра и сохранения не изменяются.")

    step(1, "adb", "Поиск adb")
    adb = get_adb(args.yes)
    say("Using " + adb, "Использую " + adb)

    step(2, "Device", "Устройство")
    serial = pick_device(adb, args.serial)
    say("Device: " + serial, "Устройство: " + serial)

    step(3, "Install the companion app", "Установка приложения")
    install_app(adb, serial, apk, args.yes)

    step(4, "Copy the TL Pro mod pack", "Копирование мода для TL Pro")
    if mod and os.path.isfile(mod):
        push_mod(adb, serial, mod)
    else:
        say("No .tl mod pack found next to the installer - skipped.", "Файл мода (.tl) не найден, шаг пропущен.")

    step(5, "Game sprites (decoded on this PC from your own copy of Terraria)", "Спрайты игры (расшифровываются на ПК из твоей копии Terraria)")
    sprites_done = None
    if args.skip_sprites:
        say("Skipped (--skip-sprites).", "Пропущено.")
    elif atlas_present(adb, serial) and not args.sprites:
        say("Sprites are already on the device - nothing to do (use --sprites to redo).", "Спрайты уже на устройстве (повторить: --sprites).")
    else:
        sprites_done = sprites(adb, serial, args.game_apk, args.yes)

    print("\nDone. Two things are left on the device:\n  1. TL Pro -> mods / packs -> import 'Download/%s', enable it for your single-player profile.\n"
          "  2. Start Terraria from TL Pro (it must be restarted after every mod update), load a world, open 'Terraduo Companion'.\n"
          % (os.path.basename(mod) if mod else "the .tl file"))
    print("Готово. Осталось на устройстве:\n  1. TL Pro -> моды / паки -> импортируй 'Download/%s' и включи для своего одиночного профиля.\n"
          "  2. Запусти Terraria через TL Pro (после каждого обновления мода — заново), зайди в мир и открой 'Terraduo Companion'."
          % (os.path.basename(mod) if mod else "файл .tl"))
    if sprites_done is False:
        print("\nSprites were not copied; the app still works with plain frames. Run the installer again after fixing the message above.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
    except subprocess.CalledProcessError as error:
        sys.exit("A command failed: %s" % " ".join(map(str, error.cmd)))
