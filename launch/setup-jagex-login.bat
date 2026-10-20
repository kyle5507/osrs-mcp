@echo off
setlocal EnableExtensions
cd /d "%~dp0.."

echo One-time Jagex account login for the OSRS MCP RuneLite build.
echo.
echo This follows RuneLite's guide: https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
echo   1. RuneLite will open in developer mode.
echo   2. Click the Jagex auth button on the sidebar.
echo   3. Click "Add Jagex Account" and complete the Jagex login in the popup.
echo   4. Select your character, then click "Play Now" on the login screen.
echo.
echo After that, start normally with OSRS-MCP-RuneLite.bat and pick your character.
echo If your characters are not listed on a normal start, run:
echo   launch\OSRS-MCP-RuneLite.bat --developer-mode
echo.
pause
call "%~dp0OSRS-MCP-RuneLite.bat" --developer-mode
