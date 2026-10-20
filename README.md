# OSRS MCP

OSRS MCP is a RuneLite plugin that runs a local [MCP](https://modelcontextprotocol.io) server inside your client. AI assistants such as Claude Code and Claude Desktop can then read your live Old School RuneScape state (stats, gear, inventory, bank, quests, surroundings) and draw visual-only hints in your game. Ask things like "What should I train next?", "Do I have the gear for Vorkath in my bank?" or "Where am I and what's nearby?" and the assistant answers from your real account data.

## Features

**Read tools**
- `get_player`: name, combat level, world, account type, HP, prayer, run energy, location
- `get_skills`: real and boosted levels, XP, XP to next level, total level
- `get_equipment`: worn items with value and equipment bonuses (per item and totals)
- `get_inventory`: inventory items with value
- `get_bank`: bank snapshot from the last time you opened it (filterable, with snapshot age)
- `get_session_stats`: XP gained per skill since login
- `get_quests`: quest states and quest points
- `get_achievement_diaries`: diary tier completion
- `get_slayer_task`: current task, amount remaining, points
- `get_nearby`: nearby NPCs, ground items and object info
- `get_recent_messages`: recent game messages (drops, level-ups, kill counts)
- `get_ge_offers`: active Grand Exchange offers with fill progress and guide price
- `lookup_item`: search any item by name for GE / wiki / high-alch prices
- `get_ge_history`: your own Grand Exchange trades (recorded while the plugin runs, plus up to 20 rows imported when you open the in-game GE History tab) with per-item profit estimates
- `get_kill_counts`: boss kill counts and personal bests (from chat while the plugin runs, plus Chat Commands data)
- `get_minigame_points`: Kingdom of Miscellania approval/coffer, Barbarian Assault, Nightmare Zone, Blast Furnace coffer, Tithe Farm, Soul Wars, Giants' Foundry and Mixology points
- `get_containers`: looting bag, seed box/vault, tackle box, forestry kit, bonds pouch, quiver ammo, knapsack and POH costume room contents (snapshot when opened, with age)
- `get_account_progress`: total level, weight, collection log count, clue totals, boss/raid completion totals, prayer unlocks, POH location
- `get_recent_loot`: recent NPC drops this session with values, grouped by source
- `get_combat_achievements`: Combat Achievement points and tier thresholds
- `get_active_state`: active prayers, spellbook, rune pouch, boosts, poison/venom, combat target
- `get_farming_timers`: farming patches and bird houses saved by RuneLite's Time Tracking plugin (raw patch state with age; only patches visited while Time Tracking was on)
- `get_charges_and_timers`: item charges saved by the Item Charges plugin, plus live stamina, antifire, overload, teleblock and teleport cooldown timers

**Overlay tools** (visual only, drawn in your client)
- `highlight_tile`: highlight a world tile
- `highlight_npc`: highlight an NPC by name or id
- `set_hint_arrow` / `clear_hint_arrow`: place or remove the game's hint arrow
- `show_hint`: show a text hint
- `clear_overlays`: remove all AI overlays

**Bank tools** (use RuneLite's Bank Tags plugin; nothing is saved to your tags)
- `filter_bank`: show only the given items in your bank
- `show_bank_layout`: show a recommended bank layout (8 columns wide)
- `clear_bank_filter`: remove the filter or layout
- `highlight_bank_items`: outline items in your bank and inventory

**GE offer assist** (you always place and confirm offers yourself)
- `stage_ge_offer` / `stage_ge_offers`: stage buy or sell offers (item, type, quantity, price) with guide-price sanity warnings
- `list_staged_offers`, `remove_staged_offer`, `clear_staged_offers`: manage the staged list

**Prompt**
- `osrs_advisor`: tells the assistant to check your account data before giving advice

### GE offer assist

The assistant can stage Grand Exchange offers (up to 16, saved per account). At the GE the plugin lists the pending ones in an overlay and, on the offer screen, shows OK / X checks for item, type, quantity and price. Staged sell items are outlined in your inventory, staged buys are listed at the top of the item search while the search box is empty, and when the price or quantity box opens for a staged offer a clickable "Click to set price: ... (staged)" line appears (an optional hotkey does the same). Filling a value is always your click or key press; you press Enter and Confirm yourself, and the plugin never sends any input. Offers you place that match a staged one are marked placed automatically. Turn it off under "GE offer assist" in the plugin settings.

Placing a staged offer: pick the item (it is listed first in the search), then click the highlighted **...** button next to Quantity and click the yellow "Click to set quantity" line in the chatbox, press Enter, and do the same for Price. The overlay says which step is next and shows "All set" when everything matches; then click Confirm. Selecting the item alone leaves the GE's default price, because the game only takes a price you enter in its price box.

### Bank filtering & layouts

The assistant can filter your bank or lay it out for you through RuneLite's built-in Bank Tags plugin (the same mechanism the Inventory Setups plugin uses). Open your bank in-game for the result to show; if it is closed the request is held and applied the moment you open it. Filters and layouts are temporary: they use virtual tags, clearing happens with `clear_bank_filter`, on logout, or when the plugin stops, and closing the bank also drops them. A layout is 8 slots wide per row, and items you do not own appear as placeholders. Item names should match the in-game names; a name without a dose or charge count (for example "Prayer potion") matches every variant you own.

## Setup

1. Install the OSRS MCP plugin in RuneLite and log in.
2. Open the **OSRS MCP** side panel and copy the command or config it shows. It includes your auth token.

**Claude Code**

```
claude mcp add --transport http osrs http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"
```

**Claude Desktop**

Add this to `claude_desktop_config.json` (requires [Node.js](https://nodejs.org) for `npx`):

```json
{"mcpServers":{"osrs":{"command":"npx","args":["-y","mcp-remote","http://127.0.0.1:8765/mcp","--header","Authorization:${AUTH_HEADER}"],"env":{"AUTH_HEADER":"Bearer <token>"}}}}
```

Restart Claude Desktop after editing the config.

## Settings

Found under the plugin's settings in RuneLite.

| Setting | Default | Description |
|---|---|---|
| Port | `8765` | Port the MCP server listens on (127.0.0.1 only) |
| Auth token | generated | Bearer token MCP clients must send. Clear it to generate a new one. |
| Bank | on | Let the AI read your bank snapshot |
| Game messages | on | Let the AI read recent game messages. Player chat is never exposed. |
| Allow AI overlays | on | Let the AI highlight tiles/NPCs, set hint arrows and show hint text |
| Default overlay duration (s) | `120` | How long overlays stay visible when the AI doesn't specify (5 to 3600) |

## Privacy and safety

- **Localhost only.** The server binds to `127.0.0.1`. Nothing is reachable from your network.
- **Token required.** Every request needs `Authorization: Bearer <token>`.
- **Browser origins blocked.** Any `Origin` other than localhost is rejected, which blocks DNS rebinding from web pages.
- **Read-only.** The plugin never sends input or game actions. Overlays are drawn only in your client. GE offer assist only fills a price/quantity box when you click or press the hotkey; you press Enter and Confirm yourself.
- **No other players' data.** Other players' names are never exposed, and player chat is never exposed.
- **Your own data only, to your own AI client.** `get_player` includes your own character name. Data goes only to the MCP client you connect on your own machine; the plugin itself never contacts any external server.
- **Bank is a snapshot.** It is saved the last time you opened your bank, so it can be out of date. The assistant is told to mention its age.
- **Turn things off.** Disable the Bank, Game messages or Allow AI overlays settings to stop sharing that data or those overlays.

## Development

```
./gradlew run     # launch a dev RuneLite client with the plugin loaded
./gradlew test    # run unit tests
```

Log in to the dev client using the [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) instructions.

To check the server is up and list its tools:

```
curl -X POST http://127.0.0.1:8765/mcp -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## License

BSD-2-Clause
