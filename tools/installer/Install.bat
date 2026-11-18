@echo off
rem Terraduo Companion - one-step installer for Windows. Double-click this file with the device connected over USB.
chcp 65001 >nul
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
    py -3 tools\install.py %*
    goto done
)
where python >nul 2>nul
if %errorlevel%==0 (
    python tools\install.py %*
    goto done
)
echo Python 3.10+ was not found. Install it from https://www.python.org/downloads/ (tick "Add python.exe to PATH"), then run this file again.
echo Python 3.10+ не найден. Установи его с https://www.python.org/downloads/ (поставь галочку "Add python.exe to PATH") и запусти файл снова.
:done
echo.
pause
