package com.osrsmcp;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrsmcp.bank.BankTools;
import com.osrsmcp.ge.GeOfferTools;
import com.osrsmcp.overlay.OverlayTools;
import com.osrsmcp.server.McpDispatcher;
import com.osrsmcp.server.McpHttpServer;
import com.osrsmcp.server.ServerStatus;
import com.osrsmcp.tools.AccountTools;
import com.osrsmcp.tools.InsightTools;
import com.osrsmcp.tools.ProgressExtraTools;
import com.osrsmcp.tools.TrackerTools;
import com.osrsmcp.tools.ProgressTools;
import com.osrsmcp.tools.Tool;
import com.osrsmcp.tools.ToolGroup;
import com.osrsmcp.tools.ToolRegistry;
import com.osrsmcp.ui.OsrsMcpPanel;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDependency(BankTagsPlugin.class)
@PluginDescriptor(
	name = "OSRS MCP",
	description = "Local MCP server so AI assistants (Claude, etc.) can read your stats, gear, bank and quests",
	tags = {"mcp", "ai", "claude", "llm", "assistant", "advisor"}
)
public class OsrsMcpPlugin extends Plugin
{
	@Inject
	private OsrsMcpConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Gson gson;

	@Inject
	private AccountTools accountTools;

	@Inject
	private ProgressTools progressTools;

	@Inject
	private InsightTools insightTools;

	@Inject
	private ProgressExtraTools progressExtraTools;

	@Inject
	private TrackerTools trackerTools;

	@Inject
	private BankTools bankTools;

	@Inject
	private GeOfferTools geOfferTools;

	@Inject
	private OverlayTools overlayTools;

	@Inject
	private OsrsMcpPanel panel;

	private final ToolRegistry registry = new ToolRegistry();
	private List<ToolGroup> groups;
	private McpHttpServer server;
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		ensureToken();

		groups = Arrays.asList(accountTools, progressTools, insightTools, progressExtraTools, trackerTools, bankTools, geOfferTools, overlayTools);
		for (ToolGroup group : groups)
		{
			eventBus.register(group);
			group.startUp();
			for (Tool tool : group.tools())
			{
				registry.register(tool);
			}
		}

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("OSRS MCP")
			.icon(icon)
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		startServer();
	}

	@Override
	protected void shutDown()
	{
		stopServer();
		clientToolbar.removeNavigation(navButton);
		for (ToolGroup group : groups)
		{
			group.shutDown();
			eventBus.unregister(group);
		}
		registry.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OsrsMcpConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("port".equals(event.getKey()) || OsrsMcpConfig.AUTH_TOKEN_KEY.equals(event.getKey()))
		{
			// Token cleared by the user: generate a new one (this re-fires ConfigChanged)
			if (ensureToken())
			{
				return;
			}
			stopServer();
			startServer();
		}
	}

	private void startServer()
	{
		McpDispatcher dispatcher = new McpDispatcher(gson, registry);
		server = new McpHttpServer(config.port(), config::authToken, dispatcher, gson);
		String error = null;
		try
		{
			server.start();
		}
		catch (IOException e)
		{
			log.warn("Failed to start OSRS MCP server on port {}", config.port(), e);
			error = "Could not listen on port " + config.port() + ": " + e.getMessage();
			server = null;
		}
		panel.update(new ServerStatus(server != null, config.port(), config.authToken(), error, registry.all().size()));
	}

	private void stopServer()
	{
		if (server != null)
		{
			server.stop();
			server = null;
		}
	}

	/** Generates an auth token if none is set. Returns true if one was generated. */
	private boolean ensureToken()
	{
		String token = config.authToken();
		if (token != null && !token.trim().isEmpty())
		{
			return false;
		}
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes)
		{
			sb.append(String.format("%02x", b));
		}
		configManager.setConfiguration(OsrsMcpConfig.GROUP, OsrsMcpConfig.AUTH_TOKEN_KEY, sb.toString());
		return true;
	}

	@Provides
	OsrsMcpConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsMcpConfig.class);
	}
}
