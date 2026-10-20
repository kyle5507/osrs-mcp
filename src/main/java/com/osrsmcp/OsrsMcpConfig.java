package com.osrsmcp;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(OsrsMcpConfig.GROUP)
public interface OsrsMcpConfig extends Config
{
	String GROUP = "osrs-mcp";
	String AUTH_TOKEN_KEY = "authToken";

	@ConfigSection(
		name = "Server",
		description = "Local MCP server settings",
		position = 0
	)
	String serverSection = "server";

	@ConfigSection(
		name = "Data exposed to AI",
		description = "Choose which game data MCP clients can read",
		position = 1
	)
	String dataSection = "data";

	@ConfigSection(
		name = "Overlays",
		description = "Visual hints the AI can draw in your client",
		position = 2
	)
	String overlaySection = "overlays";

	@Range(min = 1024, max = 65535)
	@ConfigItem(
		keyName = "port",
		name = "Port",
		description = "Port the MCP server listens on (127.0.0.1 only)",
		section = serverSection,
		position = 0
	)
	default int port()
	{
		return 8765;
	}

	@ConfigItem(
		keyName = AUTH_TOKEN_KEY,
		name = "Auth token",
		description = "Bearer token MCP clients must send. Generated automatically; clear it to generate a new one.",
		secret = true,
		section = serverSection,
		position = 1
	)
	default String authToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "exposeBank",
		name = "Bank",
		description = "Let the AI read your bank (snapshot taken whenever you open it)",
		section = dataSection,
		position = 0
	)
	default boolean exposeBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "exposeChat",
		name = "Game messages",
		description = "Let the AI read recent game messages (drops, level-ups, kill counts). Player chat is never exposed.",
		section = dataSection,
		position = 1
	)
	default boolean exposeChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowOverlays",
		name = "Allow AI overlays",
		description = "Let the AI highlight tiles/NPCs, set hint arrows and show hint text",
		section = overlaySection,
		position = 0
	)
	default boolean allowOverlays()
	{
		return true;
	}

	@Range(min = 5, max = 3600)
	@ConfigItem(
		keyName = "overlayTtl",
		name = "Default overlay duration (s)",
		description = "How long AI overlays stay visible when the AI doesn't specify",
		section = overlaySection,
		position = 1
	)
	default int overlayTtl()
	{
		return 120;
	}

	@ConfigSection(
		name = "GE offer assist",
		description = "Help placing GE offers the AI staged for you. You always confirm offers yourself.",
		position = 3
	)
	String geAssistSection = "geAssist";

	@ConfigItem(
		keyName = "geAssist",
		name = "Enable GE offer assist",
		description = "Show staged offers at the GE, put the staged item in GE search results, and offer click-to-fill for price and quantity",
		section = geAssistSection,
		position = 0
	)
	default boolean geAssist()
	{
		return true;
	}

	@ConfigItem(
		keyName = "geAssistHotkey",
		name = "Fill hotkey",
		description = "Optional key that fills the staged price/quantity while the GE price or quantity box is open",
		section = geAssistSection,
		position = 1
	)
	default Keybind geAssistHotkey()
	{
		return Keybind.NOT_SET;
	}
}
