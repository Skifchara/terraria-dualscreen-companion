TERRADUO COMPANION - QUICK INSTALL
==================================

You need: the AYN Thor (or another dual-screen Android) with Terraria from Google Play and TL Pro,
a USB cable, and a PC with Python 3.10+.

1. On the device: Settings -> Developer options -> turn on USB debugging. Connect the USB cable and
   tap "Allow" when the device asks.
2. On the PC:
     Windows        double-click  Install.bat
     macOS / Linux  python3 tools/install.py
   The installer finds (or downloads) adb, installs the app, copies the mod pack to the device's
   Download folder and decodes the game's sprites from YOUR installed Terraria (nothing from the game is
   included in this archive). It asks before downloading or removing anything.
3. On the device: open TL Pro -> mods / packs -> import "Download/Terraduo-Companion-mod-*.tl" and enable it
   for your single-player profile.
4. Start Terraria from TL Pro (restart it after every mod update), load a world and open the
   "Terraduo Companion" app. It opens on the bottom screen by itself.

Only want the app? Copy Terraduo-Companion-*.apk to the device and install it (allow "install unknown apps").
Without step 2's sprite part the app works but draws plain frames instead of the game's art.

Options: python tools/install.py --help   (--yes, --serial, --skip-sprites, --sprites, --game-apk ...)

------------------------------------------------------------------------------------------------

TERRADUO COMPANION - БЫСТРАЯ УСТАНОВКА
======================================

Нужно: AYN Thor (или другой Android с двумя экранами) с Terraria из Google Play и TL Pro, кабель USB
и ПК с Python 3.10+.

1. На устройстве: Настройки -> Для разработчиков -> включи «Отладку по USB». Подключи кабель и нажми
   «Разрешить», когда устройство спросит.
2. На ПК:
     Windows        дважды щёлкни  Install.bat
     macOS / Linux  python3 tools/install.py
   Установщик найдёт (или скачает) adb, установит приложение, положит мод в папку Download на
   устройстве и расшифрует спрайты из ТВОЕЙ установленной Terraria (в архиве нет ничего из игры).
   Перед скачиванием и удалением чего-либо он спрашивает.
3. На устройстве: TL Pro -> моды / паки -> импортируй «Download/Terraduo-Companion-mod-*.tl» и включи для
   своего одиночного профиля.
4. Запусти Terraria через TL Pro (после каждого обновления мода — заново), зайди в мир и открой
   приложение «Terraduo Companion». Оно само откроется на нижнем экране.

Нужно только приложение? Скопируй Terraduo-Companion-*.apk на устройство и установи его.
Без части со спрайтами приложение работает, но рисует простые рамки вместо графики игры.
