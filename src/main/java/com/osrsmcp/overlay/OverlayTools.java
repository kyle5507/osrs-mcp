package com.osrsmcp.overlay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.tools.Args;
import com.osrsmcp.tools.Schema;
import com.osrsmcp.tools.Tool;
import com.osrsmcp.tools.ToolException;
import com.osrsmcp.tools.ToolGroup;
import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayManager;

/** Tools that let the AI draw visual-only hints in the client. */
@Singleton
public class OverlayTools implements ToolGroup
{
	private static final String DISABLED = "AI overlays are disabled in the OSRS MCP plugin settings";
	private static final int MAX_TTL = 3600;
	private static final int MAX_TEXT = 300;
	private static final int MAX_LABEL = 40;
	private static final Color DEFAULT_COLOR = Color.CYAN;

	private final Client client;
	private final ClientThread clientThread;
	private final ClientReader reader;
	private final OsrsMcpConfig config;
	private final OverlayManager overlayManager;
	private final AiOverlayState state;
	private final AiSceneOverlay sceneOverlay;
	private final AiHintOverlay hintOverlay;

	@Inject
	public OverlayTools(Client client, ClientThread clientThread, ClientReader reader, OsrsMcpConfig config,
		OverlayManager overlayManager, AiOverlayState state, AiSceneOverlay sceneOverlay, AiHintOverlay hintOverlay)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.reader = reader;
		this.config = config;
		this.overlayManager = overlayManager;
		this.state = state;
		this.sceneOverlay = sceneOverlay;
		this.hintOverlay = hintOverlay;
	}

	@Override
	public void startUp()
	{
		overlayManager.add(sceneOverlay);
		overlayManager.add(hintOverlay);
	}

	@Override
	public void shutDown()
	{
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(hintOverlay);
		clearAll();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (OsrsMcpConfig.GROUP.equals(event.getGroup()) && "allowOverlays".equals(event.getKey())
			&& !config.allowOverlays())
		{
			clearAll();
		}
	}

	private void clearAll()
	{
		state.clear();
		clearOwnHintArrow();
	}

	private void clearOwnHintArrow()
	{
		if (state.isHintArrowSet())
		{
			state.setHintArrowSet(false);
			clientThread.invoke(client::clearHintArrow);
		}
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(highlightTile(), highlightNpc(), setHintArrow(), clearHintArrow(), showHint(), clearOverlays());
	}

	// ---- helpers ----

	private void requireEnabled() throws ToolException
	{
		if (!config.allowOverlays())
		{
			throw new ToolException(DISABLED);
		}
	}

	private long ttlMs(JsonObject args)
	{
		int ttl = Args.integer(args, "ttl_seconds", config.overlayTtl());
		if (ttl <= 0)
		{
			ttl = config.overlayTtl();
		}
		return Math.min(ttl, MAX_TTL) * 1000L;
	}

	private static String clip(String s, int max)
	{
		if (s == null)
		{
			return null;
		}
		s = s.trim();
		if (s.isEmpty())
		{
			return null;
		}
		return s.length() > max ? s.substring(0, max) : s;
	}

	static Color parseColor(String s) throws ToolException
	{
		if (s == null || s.trim().isEmpty())
		{
			return DEFAULT_COLOR;
		}
		String c = s.trim().toLowerCase(Locale.ROOT);
		switch (c)
		{
			case "red":
				return Color.RED;
			case "green":
				return Color.GREEN;
			case "blue":
				return new Color(0x3399FF);
			case "yellow":
				return Color.YELLOW;
			case "orange":
				return Color.ORANGE;
			case "cyan":
				return Color.CYAN;
			case "magenta":
				return Color.MAGENTA;
			case "pink":
				return Color.PINK;
			case "purple":
				return new Color(0x9933FF);
			case "white":
				return Color.WHITE;
			case "black":
				return Color.BLACK;
			default:
				break;
		}
		String hex = c.startsWith("#") ? c.substring(1) : c;
		if (hex.length() == 6)
		{
			try
			{
				return new Color(Integer.parseInt(hex, 16));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		throw new ToolException("Unrecognised color '" + s + "'. Use #RRGGBB or a name like red, green, blue, yellow, orange, cyan, magenta, purple, white.");
	}

	private JsonObject ok(String message)
	{
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("message", message);
		return o;
	}

	// ---- tools ----

	private Tool highlightTile()
	{
		return new Tool()
		{
			@Override
			public String name()
			{
				return "highlight_tile";
			}

			@Override
			public String description()
			{
				return "Draw a colored highlight (and optional label) on a world tile in the player's client. Visual only. "
					+ "Plane defaults to the player's current plane. Expires after ttl_seconds.";
			}

			@Override
			public JsonObject inputSchema()
			{
				return Schema.object()
					.required("x", "integer", "World tile X")
					.required("y", "integer", "World tile Y")
					.prop("plane", "integer", "Plane (0-3); defaults to the player's plane")
					.prop("label", "string", "Short text drawn on the tile")
					.prop("color", "string", "#RRGGBB or a color name (default cyan)")
					.prop("ttl_seconds", "integer", "How long to show it (default from plugin settings, max 3600)")
					.build();
			}

			@Override
			public JsonElement call(JsonObject args) throws ToolException
			{
				requireEnabled();
				int x = Args.requireInt(args, "x");
				int y = Args.requireInt(args, "y");
				Integer planeArg = Args.optInt(args, "plane");
				int plane = planeArg != null ? planeArg : reader.readLoggedIn(() -> client.getLocalPlayer().getWorldLocation().getPlane());
				if (plane < 0 || plane > 3)
				{
					throw new ToolException("plane must be 0-3");
				}
				Color color = parseColor(Args.str(args, "color", null));
				String label = clip(Args.str(args, "label", null), MAX_LABEL);
				state.addTile(new WorldPoint(x, y, plane), label, color, ttlMs(args));
				return ok("Highlighted tile (" + x + ", " + y + ", " + plane + ")");
			}
		};
	}

	private Tool highlightNpc()
	{
		return new Tool()
		{
			@Override
			public String name()
			{
				return "highlight_npc";
			}

			@Override
			public String description()
			{
				return "Outline every visible NPC matching a name (case-insensitive) or id, with an optional label. Visual only. "
					+ "Provide name or id. Returns how many matching NPCs are currently visible.";
			}

			@Override
			public JsonObject inputSchema()
			{
				return Schema.object()
					.prop("name", "string", "NPC name (case-insensitive exact match)")
					.prop("id", "integer", "NPC id")
					.prop("label", "string", "Short text drawn above the NPC")
					.prop("color", "string", "#RRGGBB or a color name (default cyan)")
					.prop("ttl_seconds", "integer", "How long to show it (default from plugin settings, max 3600)")
					.build();
			}

			@Override
			public JsonElement call(JsonObject args) throws ToolException
			{
				requireEnabled();
				String name = clip(Args.str(args, "name", null), 80);
				Integer id = Args.optInt(args, "id");
				if (name == null && id == null)
				{
					throw new ToolException("Provide either name or id");
				}
				Color color = parseColor(Args.str(args, "color", null));
				String label = clip(Args.str(args, "label", null), MAX_LABEL);
				state.addNpc(name, name != null ? -1 : id, label, color, ttlMs(args));

				final String fname = name;
				final int fid = id == null ? -1 : id;
				int visible = reader.read(() ->
				{
					int n = 0;
					for (NPC npc : client.getNpcs())
					{
						if (fname != null)
						{
							if (npc.getName() != null && npc.getName().equalsIgnoreCase(fname))
							{
								n++;
							}
						}
						else if (npc.getId() == fid)
						{
							n++;
						}
					}
					return n;
				});
				JsonObject o = ok("Highlighting NPC " + (name != null ? name : "id " + id));
				o.addProperty("visible_matches", visible);
				return o;
			}
		};
	}

	private Tool setHintArrow()
	{
		return new Tool()
		{
			@Override
			public String name()
			{
				return "set_hint_arrow";
			}

			@Override
			public String description()
			{
				return "Show the in-game yellow hint arrow over a world tile. Visual only. Replaces any existing hint arrow; "
					+ "use clear_hint_arrow to remove it.";
			}

			@Override
			public JsonObject inputSchema()
			{
				return Schema.object()
					.required("x", "integer", "World tile X")
					.required("y", "integer", "World tile Y")
					.prop("plane", "integer", "Plane (0-3); defaults to the player's plane")
					.build();
			}

			@Override
			public JsonElement call(JsonObject args) throws ToolException
			{
				requireEnabled();
				int x = Args.requireInt(args, "x");
				int y = Args.requireInt(args, "y");
				Integer planeArg = Args.optInt(args, "plane");
				int plane = reader.readLoggedIn(() ->
				{
					int p = planeArg != null ? planeArg : client.getLocalPlayer().getWorldLocation().getPlane();
					if (p < 0 || p > 3)
					{
						throw new ClientReader.ToolRuntimeException("plane must be 0-3");
					}
					client.setHintArrow(new WorldPoint(x, y, p));
					return p;
				});
				state.setHintArrowSet(true);
				return ok("Hint arrow set at (" + x + ", " + y + ", " + plane + ")");
			}
		};
	}

	private Tool clearHintArrow()
	{
		return new Tool()
		{
			@Override
			public String name()
			{
				return "clear_hint_arrow";
			}

			@Override
			public String description()
			{
				return "Remove the in-game hint arrow.";
			}

			@Override
			public JsonObject inputSchema()
			{
				return Schema.none();
			}

			@Override
			public JsonElement call(JsonObject args) throws ToolException
			{
				requireEnabled();
				state.setHintArrowSet(false);
				reader.read(() ->
				{
					client.clearHintArrow();
					return Boolean.TRUE;
				});
				return ok("Hint arrow cleared");
			}
		};
	}

	private Tool showHint()
	{
		return new Tool()
		{
			@Override
			public String name()
			{
				return "show_hint";
			}

			@Override
			public String description()
			{
				return "Show a short text hint in an \"AI hint\" box in the top-left of the client (max ~300 characters). "
					+ "Replaces any previous hint. Visual only.";
			}

			@Override
			public JsonObject inputSchema()
			{
				return Schema.object()
					.required("text", "string", "Hint text (max 300 characters)")
					.prop("ttl_seconds", "integer", "How long to show it (default from plugin settings, max 3600)")
					.build();
			}

			@Override
			public JsonElement call(JsonObject args) throws ToolException
			{
				requireEnabled();
				String text = clip(Args.str(args, "text", null), MAX_TEXT);
				if (text == null)
				{
					throw new ToolException("Missing required argument: text");
				}
				state.setHint(text, ttlMs(args));
				return ok("Hint shown");
			}
		};
	}

	private Tool clearOverlays()
	{
		return new Tool()
		{
			@Override
			public String name()
			{
				return "clear_overlays";
			}

			@Override
			public String description()
			{
				return "Remove all AI overlays: tile highlights, NPC highlights, hint text and the hint arrow (if the AI set it).";
			}

			@Override
			public JsonObject inputSchema()
			{
				return Schema.none();
			}

			@Override
			public JsonElement call(JsonObject args) throws ToolException
			{
				requireEnabled();
				clearAll();
				return ok("All AI overlays cleared");
			}
		};
	}
}
