package com.osrsmcp.bank;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import com.osrsmcp.game.BankCache;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.game.ItemResolver;
import com.osrsmcp.tools.Args;
import com.osrsmcp.tools.Schema;
import com.osrsmcp.tools.Tool;
import com.osrsmcp.tools.ToolException;
import com.osrsmcp.tools.ToolGroup;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.banktags.BankTag;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Bank filtering, recommended layouts and item highlights. Filters and layouts are shown through
 * RuneLite's Bank Tags plugin using temporary virtual tags (nothing is written to the player's
 * persistent tags; the temporary layout is removed again by clear_bank_filter).
 */
@Slf4j
@Singleton
public class BankTools implements ToolGroup
{
	static final String FILTER_TAG = "osrs-mcp";
	static final String LAYOUT_TAG = "osrs-mcp-layout";
	private static final int COLUMNS = 8;
	private static final int MAX_ROWS = 40;
	private static final int MAX_ITEMS = 500;
	private static final int MAX_TTL = 3600;
	private static final int MAX_APPLY_ATTEMPTS = 100;

	private static final String BANK_NOTE =
		"The player must have their bank open for it to show; if it is closed the request is held and applies automatically "
			+ "when they next open the bank. Use exact in-game item names (a name without a dose/charge, like 'Prayer potion', "
			+ "matches every variant the player owns).";

	private final Client client;
	private final ClientThread clientThread;
	private final ClientReader reader;
	private final ItemResolver resolver;
	private final ItemManager itemManager;
	private final BankCache bankCache;
	private final OsrsMcpConfig config;
	private final OverlayManager overlayManager;
	private final BankHighlightOverlay highlightOverlay;

	@Inject
	private BankTagsService bankTagsService;

	@Inject
	private TagManager tagManager;

	@Inject
	private LayoutManager layoutManager;

	private volatile Set<Integer> filterIds = Collections.emptySet();
	private volatile Set<Integer> layoutIds = Collections.emptySet();

	// client thread only
	private String pendingTag;
	private int pendingOptions;
	private int generation;
	private boolean layoutSaved;

	@Inject
	public BankTools(Client client, ClientThread clientThread, ClientReader reader, ItemResolver resolver,
		ItemManager itemManager, BankCache bankCache, OsrsMcpConfig config, OverlayManager overlayManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.reader = reader;
		this.resolver = resolver;
		this.itemManager = itemManager;
		this.bankCache = bankCache;
		this.config = config;
		this.overlayManager = overlayManager;
		this.highlightOverlay = new BankHighlightOverlay(config, itemManager);
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new FilterBank(), new ShowBankLayout(), new ClearBankFilter(), new HighlightBankItems());
	}

	@Override
	public void startUp()
	{
		overlayManager.add(highlightOverlay);
	}

	@Override
	public void shutDown()
	{
		overlayManager.remove(highlightOverlay);
		clientThread.invoke(this::clearAll);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && pendingTag != null)
		{
			scheduleApply();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			clearAll();
		}
	}

	// ---- client-thread helpers ----

	private boolean bankOpen()
	{
		Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		return w != null && !w.isHidden();
	}

	/** Open the tag now if the bank is open, otherwise remember it until the bank opens. */
	private boolean openOrPend(String tag, int options)
	{
		generation++;
		pendingTag = tag;
		pendingOptions = options;
		if (bankOpen())
		{
			scheduleApply();
			return true;
		}
		return false;
	}

	private void scheduleApply()
	{
		final int gen = generation;
		final int[] attempts = {0};
		clientThread.invokeLater(() ->
		{
			if (gen != generation || pendingTag == null)
			{
				return true;
			}
			if (!bankOpen())
			{
				// not fully loaded yet (or closed again): retry a while
				return ++attempts[0] >= MAX_APPLY_ATTEMPTS;
			}
			String tag = pendingTag;
			int options = pendingOptions;
			pendingTag = null;
			try
			{
				bankTagsService.openBankTag(tag, options);
			}
			catch (RuntimeException e)
			{
				log.warn("Could not open bank tag {}", tag, e);
			}
			return true;
		});
	}

	private void clearAll()
	{
		generation++;
		pendingTag = null;
		String active = bankTagsService.getActiveTag();
		if (active != null && (FILTER_TAG.equalsIgnoreCase(active) || LAYOUT_TAG.equalsIgnoreCase(active)))
		{
			bankTagsService.closeBankTag();
		}
		tagManager.unregisterTag(FILTER_TAG);
		tagManager.unregisterTag(LAYOUT_TAG);
		filterIds = Collections.emptySet();
		layoutIds = Collections.emptySet();
		if (layoutSaved)
		{
			layoutManager.removeLayout(LAYOUT_TAG);
			layoutSaved = false;
		}
		highlightOverlay.clear();
	}

	private BankTag virtualTag(boolean layout)
	{
		return itemId ->
		{
			Set<Integer> ids = layout ? layoutIds : filterIds;
			return ids.contains(itemId) || ids.contains(itemManager.canonicalize(itemId));
		};
	}

	private Set<Integer> withCanonical(Iterable<Integer> ids)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
			set.add(itemManager.canonicalize(id));
		}
		return Collections.unmodifiableSet(set);
	}

	/** Canonical ids present in the bank snapshot, or null if there is none. */
	private Set<Integer> snapshotIds()
	{
		int[][] snap = bankCache.items();
		if (snap == null)
		{
			return null;
		}
		Set<Integer> set = new HashSet<>();
		for (int[] it : snap)
		{
			set.add(itemManager.canonicalize(it[0]));
		}
		return set;
	}

	private boolean inSnapshot(Set<Integer> snapshot, int id)
	{
		return snapshot != null && snapshot.contains(itemManager.canonicalize(id));
	}

	// ---- argument helpers ----

	private static List<String> stringList(JsonElement e, String name) throws ToolException
	{
		List<String> out = new ArrayList<>();
		if (e == null || e.isJsonNull())
		{
			throw new ToolException("Missing required argument: " + name);
		}
		if (e.isJsonArray())
		{
			for (JsonElement x : e.getAsJsonArray())
			{
				out.add(x == null || x.isJsonNull() ? "" : x.getAsString());
			}
		}
		else if (e.isJsonPrimitive())
		{
			out.addAll(Arrays.asList(e.getAsString().split("[,\\n]")));
		}
		else
		{
			throw new ToolException(name + " must be an array of strings");
		}
		return out;
	}

	private static JsonObject itemsSchema(String desc, boolean withColor)
	{
		JsonObject root = Schema.object().build();
		JsonObject props = root.getAsJsonObject("properties");
		JsonObject items = new JsonObject();
		items.addProperty("type", "array");
		items.addProperty("description", desc);
		JsonObject str = new JsonObject();
		str.addProperty("type", "string");
		items.add("items", str);
		props.add("items", items);
		JsonArray req = new JsonArray();
		req.add("items");
		root.add("required", req);
		if (withColor)
		{
			addProp(props, "color", "string", "#RRGGBB or a color name (default cyan)");
			addProp(props, "ttl_seconds", "integer", "How long to show it (default from plugin settings, max 3600)");
		}
		return root;
	}

	private static void addProp(JsonObject props, String name, String type, String desc)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", desc);
		props.add(name, p);
	}

	private static JsonArray toJson(List<String> list)
	{
		JsonArray a = new JsonArray();
		for (String s : list)
		{
			a.add(s);
		}
		return a;
	}

	private static Color parseColor(String s) throws ToolException
	{
		if (s == null || s.trim().isEmpty())
		{
			return Color.CYAN;
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

	// ---- tools ----

	private class FilterBank implements Tool
	{
		@Override
		public String name()
		{
			return "filter_bank";
		}

		@Override
		public String description()
		{
			return "Filter the player's bank to show only the given items (uses RuneLite's Bank Tags plugin; nothing is saved to the player's tags). "
				+ BANK_NOTE + " The filter is temporary: it is cleared by clear_bank_filter or when the player closes the bank. "
				+ "Returns applied or pending, how many item ids matched, which names could not be resolved, and how many matched items are in the bank snapshot.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return itemsSchema("Item names (exact in-game names) or numeric item ids to show", false);
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			List<String> names = stringList(args.get("items"), "items");
			if (names.isEmpty() || names.size() > MAX_ITEMS)
			{
				throw new ToolException("items must contain between 1 and " + MAX_ITEMS + " entries");
			}
			return reader.readLoggedIn(() ->
			{
				ItemResolver.Result res = resolver.resolve(names, true);
				if (res.ids.isEmpty())
				{
					throw new ClientReader.ToolRuntimeException("None of the items could be resolved: " + res.unresolved
						+ ". Use exact in-game item names (items must be tradeable or owned).");
				}
				filterIds = withCanonical(res.ids);
				tagManager.registerTag(FILTER_TAG, virtualTag(false));
				boolean applied = openOrPend(FILTER_TAG, BankTagsService.OPTION_NO_LAYOUT | BankTagsService.OPTION_HIDE_TAG_NAME);

				Set<Integer> snap = snapshotIds();
				int found = 0;
				for (int id : res.ids)
				{
					if (inSnapshot(snap, id))
					{
						found++;
					}
				}
				JsonObject r = new JsonObject();
				r.addProperty("status", applied ? "applied" : "pending");
				if (!applied)
				{
					r.addProperty("note", "The bank is not open. The filter will apply when the player opens it.");
				}
				r.addProperty("matchedIds", res.ids.size());
				r.add("unresolved", toJson(res.unresolved));
				r.addProperty("itemsFoundInBank", found);
				r.addProperty("bankSnapshotAvailable", snap != null);
				return r;
			});
		}
	}

	private class ShowBankLayout implements Tool
	{
		@Override
		public String name()
		{
			return "show_bank_layout";
		}

		@Override
		public String description()
		{
			return "Show a recommended bank layout using RuneLite's Bank Tags layout feature. rows is an array of rows, each an array of up to "
				+ COLUMNS + " item names; the bank is " + COLUMNS + " columns wide, so row 0 is the top row and slot index = row*" + COLUMNS
				+ "+column. Use \"\" for an empty slot. Max " + MAX_ROWS + " rows. Items the player does not own appear as placeholders "
				+ "(the response lists them). " + BANK_NOTE + " The layout is temporary: it is removed by clear_bank_filter. "
				+ "The title is only echoed back in the response.";
		}

		@Override
		public JsonObject inputSchema()
		{
			JsonObject root = Schema.object().build();
			JsonObject props = root.getAsJsonObject("properties");
			JsonObject rows = new JsonObject();
			rows.addProperty("type", "array");
			rows.addProperty("description", "Bank rows, top to bottom; each row is an array of up to " + COLUMNS + " item names (\"\" = empty slot)");
			JsonObject row = new JsonObject();
			row.addProperty("type", "array");
			JsonObject str = new JsonObject();
			str.addProperty("type", "string");
			row.add("items", str);
			rows.add("items", row);
			props.add("rows", rows);
			addProp(props, "title", "string", "Optional name for this layout (shown in the tool response only)");
			JsonArray req = new JsonArray();
			req.add("rows");
			root.add("required", req);
			return root;
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			JsonElement rowsEl = args.get("rows");
			if (rowsEl == null || !rowsEl.isJsonArray() || rowsEl.getAsJsonArray().size() == 0)
			{
				throw new ToolException("rows must be a non-empty array of arrays of item names");
			}
			JsonArray rowsArr = rowsEl.getAsJsonArray();
			if (rowsArr.size() > MAX_ROWS)
			{
				throw new ToolException("At most " + MAX_ROWS + " rows are allowed");
			}
			List<String> flat = new ArrayList<>();
			for (JsonElement re : rowsArr)
			{
				if (!re.isJsonArray())
				{
					throw new ToolException("Each row must be an array of item names");
				}
				JsonArray row = re.getAsJsonArray();
				if (row.size() > COLUMNS)
				{
					throw new ToolException("A bank row has only " + COLUMNS + " slots; got " + row.size());
				}
				for (int c = 0; c < COLUMNS; c++)
				{
					JsonElement x = c < row.size() ? row.get(c) : null;
					flat.add(x == null || x.isJsonNull() ? "" : x.getAsString().trim());
				}
			}
			String title = Args.str(args, "title", null);
			return reader.readLoggedIn(() ->
			{
				List<String> cells = new ArrayList<>();
				List<Integer> cellPos = new ArrayList<>();
				for (int i = 0; i < flat.size(); i++)
				{
					if (!flat.get(i).isEmpty())
					{
						cells.add(flat.get(i));
						cellPos.add(i);
					}
				}
				if (cells.isEmpty())
				{
					throw new ClientReader.ToolRuntimeException("The layout has no items");
				}
				ItemResolver.Result res = resolver.resolve(cells, false);
				Layout layout = new Layout(LAYOUT_TAG);
				layout.resize(flat.size());
				Set<Integer> placed = new HashSet<>();
				List<String> duplicates = new ArrayList<>();
				List<Integer> ids = new ArrayList<>();
				for (int i = 0; i < cells.size(); i++)
				{
					int id = res.best.get(i);
					if (id < 0)
					{
						continue;
					}
					if (!placed.add(itemManager.canonicalize(id)))
					{
						duplicates.add(cells.get(i));
						continue;
					}
					layout.setItemAtPos(cellPos.get(i), id);
					ids.add(id);
				}
				if (ids.isEmpty())
				{
					throw new ClientReader.ToolRuntimeException("None of the items could be resolved: " + res.unresolved
						+ ". Use exact in-game item names.");
				}
				layoutIds = withCanonical(ids);
				tagManager.registerTag(LAYOUT_TAG, virtualTag(true));
				layoutManager.saveLayout(layout);
				layoutSaved = true;
				boolean applied = openOrPend(LAYOUT_TAG, BankTagsService.OPTION_HIDE_TAG_NAME);

				Set<Integer> snap = snapshotIds();
				List<String> notInBank = new ArrayList<>();
				for (int i = 0; i < cells.size(); i++)
				{
					int id = res.best.get(i);
					if (id >= 0 && !inSnapshot(snap, id))
					{
						notInBank.add(cells.get(i));
					}
				}
				JsonObject r = new JsonObject();
				r.addProperty("status", applied ? "applied" : "pending");
				if (!applied)
				{
					r.addProperty("note", "The bank is not open. The layout will apply when the player opens it.");
				}
				if (title != null)
				{
					r.addProperty("title", title);
				}
				r.addProperty("rows", rowsArr.size());
				r.addProperty("itemsPlaced", ids.size());
				r.add("unresolved", toJson(res.unresolved));
				r.add("duplicatesSkipped", toJson(duplicates));
				r.add("notInBankSnapshot", toJson(notInBank));
				r.addProperty("bankSnapshotAvailable", snap != null);
				return r;
			});
		}
	}

	private class ClearBankFilter implements Tool
	{
		@Override
		public String name()
		{
			return "clear_bank_filter";
		}

		@Override
		public String description()
		{
			return "Remove the bank filter / layout set by filter_bank or show_bank_layout (closes that view if it is showing), "
				+ "drops anything pending, and clears bank item highlights.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			reader.read(() ->
			{
				clearAll();
				return Boolean.TRUE;
			});
			JsonObject r = new JsonObject();
			r.addProperty("cleared", true);
			return r;
		}
	}

	private class HighlightBankItems implements Tool
	{
		@Override
		public String name()
		{
			return "highlight_bank_items";
		}

		@Override
		public String description()
		{
			return "Outline the given items with a colored box wherever they are shown in the bank or inventory (visual only; "
				+ "does not filter or move anything). Shows while the player has the bank/inventory visible; expires after ttl_seconds. "
				+ "Use exact in-game item names (a name without a dose/charge matches every variant the player owns). "
				+ "Removed by clear_bank_filter.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return itemsSchema("Item names (exact in-game names) or numeric item ids to outline", true);
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			if (!config.allowOverlays())
			{
				throw new ToolException("Overlays are disabled. The player can enable them in the OSRS MCP plugin settings (Overlays > Allow AI overlays).");
			}
			List<String> names = stringList(args.get("items"), "items");
			if (names.isEmpty() || names.size() > MAX_ITEMS)
			{
				throw new ToolException("items must contain between 1 and " + MAX_ITEMS + " entries");
			}
			Color color = parseColor(Args.str(args, "color", null));
			int ttl = Args.integer(args, "ttl_seconds", config.overlayTtl());
			if (ttl <= 0)
			{
				ttl = config.overlayTtl();
			}
			long ttlMs = Math.min(ttl, MAX_TTL) * 1000L;
			return reader.readLoggedIn(() ->
			{
				ItemResolver.Result res = resolver.resolve(names, true);
				if (res.ids.isEmpty())
				{
					throw new ClientReader.ToolRuntimeException("None of the items could be resolved: " + res.unresolved
						+ ". Use exact in-game item names.");
				}
				highlightOverlay.add(new BankHighlightOverlay.Entry(withCanonical(res.ids), color,
					System.currentTimeMillis() + ttlMs));
				JsonObject r = new JsonObject();
				r.addProperty("highlighted", true);
				r.addProperty("matchedIds", res.ids.size());
				r.add("unresolved", toJson(res.unresolved));
				r.addProperty("expiresInSeconds", ttlMs / 1000);
				return r;
			});
		}
	}
}
