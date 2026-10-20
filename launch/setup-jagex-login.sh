#!/usr/bin/env bash
# One-time Jagex account login for the OSRS MCP RuneLite build.
cd "$(dirname "$0")/.." || exit 1
cat <<'TXT'
One-time Jagex account login for the OSRS MCP RuneLite build.
Follows https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts
  1. RuneLite will open in developer mode.
  2. Click the Jagex auth button on the sidebar.
  3. Click "Add Jagex Account" and complete the Jagex login in the popup.
  4. Select your character, then click "Play Now" on the login screen.
After that, start normally with launch/osrs-mcp-runelite.sh and pick your character.
If your characters are not listed on a normal start, run:
  launch/osrs-mcp-runelite.sh --developer-mode
TXT
read -r -p "Press Enter to continue..." _
exec bash launch/osrs-mcp-runelite.sh --developer-mode
