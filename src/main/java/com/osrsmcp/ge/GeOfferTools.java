package com.osrsmcp.ge;

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
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;

/**
 * Stage Grand Exchange offers for the player. The player places and confirms every offer themselves;
 * {@link GeAssist} only displays them and pre-fills values when the player clicks or presses the hotkey.
 */
@Singleton
public class GeOfferTools implements ToolGroup
{
	private static final String DISABLED = "GE offer assist is disabled in the OSRS MCP plugin settings";
	private static final double WARN_FRACTION = 0.25;
	private static final int MAX_BATCH = 16;

	private static final String PLAYER_NOTE =
		" The player places and confirms offers themselves; the plugin only shows the staged offers and pre-fills "
			+ "the price/quantity when the player clicks (or presses their hotkey) at the Grand Exchange.";

	private final Client client;
	private final ClientThread clientThread;
	private final ClientReader reader;
	private final ItemResolver resolver;
	private final ItemManager itemManager;
	private final BankCache bankCache;
	private final OsrsMcpConfig config;
	private final StagedOffers staged;
	private final GeAssist assist;

	@Inject
	public GeOfferTools(Client client, ClientThread clientThread, ClientReader reader, ItemResolver resolver,
		ItemManager itemManager, BankCache bankCache, OsrsMcpConfig config, StagedOffers staged, GeAssist assist)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.reader = reader;
		this.resolver = resolver;
		this.itemManager = itemManager;
		this.bankCache = bankCache;
		this.config = config;
		this.staged = staged;
		this.assist = assist;
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new StageOffer(), new StageOffers(), new ListStaged(), new RemoveStaged(),
			new ClearStaged());
	}

	@Override
	public void startUp()
	{
		assist.startUp();
	}

	@Override
	public void shutDown()
	{
		assist.shutDown();
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		staged.invalidate();
		clientThread.invokeLater(staged::refresh);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == net.runelite.api.GameState.LOGGED_IN)
		{
			clientThread.invokeLater(staged::refresh);
		}
	}

	// ---- helpers ----

	private void requireEnabled() throws ToolException
	{
		if (!config.geAssist())
		{
			throw new ToolException(DISABLED);
		}
	}

	private static String fmt(long n)
	{
		return String.format(Locale.ROOT, "%,d", n);
	}

	private static JsonObject toJson(StagedOffers.Offer o)
	{
		JsonObject j = new JsonObject();
		j.addProperty("id", o.id);
		j.addProperty("item", o.itemName);
		j.addProperty("itemId", o.itemId);
		j.addProperty("type", o.type());
		j.addProperty("quantity", o.quantity);
		j.addProperty("price", o.price);
		j.addProperty("total", o.price * o.quantity);
		j.addProperty("status", o.status);
		j.addProperty("createdAt", Instant.ofEpochMilli(o.createdAt).toString());
		if (o.placedAt > 0)
		{
			j.addProperty("placedAt", Instant.ofEpochMilli(o.placedAt).toString());
		}
		return j;
	}

	private static int countIn(ItemContainer c, ItemManager im, int id)
	{
		if (c == null)
		{
			return 0;
		}
		long n = 0;
		for (Item it : c.getItems())
		{
			if (it.getId() > 0 && im.canonicalize(it.getId()) == id)
			{
				n += it.getQuantity();
			}
		}
		return (int) Math.min(n, Integer.MAX_VALUE);
	}

	/** Must run on the client thread. */
	private JsonObject stageOne(String itemInput, String type, int quantity, long price) throws ToolException
	{
		boolean buy;
		if ("buy".equalsIgnoreCase(type))
		{
			buy = true;
		}
		else if ("sell".equalsIgnoreCase(type))
		{
			buy = false;
		}
		else
		{
			throw new ToolException("type must be \"buy\" or \"sell\"");
		}
		if (itemInput == null || itemInput.trim().isEmpty())
		{
			throw new ToolException("Missing item");
		}
		if (quantity < 1)
		{
			throw new ToolException("quantity must be at least 1");
		}
		if (price < 1)
		{
			throw new ToolException("price must be at least 1 gp");
		}
		ItemResolver.Result res = resolver.resolve(Collections.singletonList(itemInput.trim()), false);
		int resolved = res.best.get(0);
		if (resolved <= 0)
		{
			throw new ToolException("Could not find an item called '" + itemInput
				+ "'. Use the exact in-game name (lookup_item can search).");
		}
		int id = itemManager.canonicalize(resolved);
		ItemComposition comp = itemManager.getItemComposition(id);
		if (!comp.isTradeable())
		{
			throw new ToolException(comp.getName() + " is not tradeable on the Grand Exchange");
		}
		JsonObject out = new JsonObject();
		JsonArray warnings = new JsonArray();
		if (!buy)
		{
			int inInv = countIn(client.getItemContainer(InventoryID.INV), itemManager, id);
			int inBank = 0;
			int[][] snap = bankCache.items();
			if (snap != null)
			{
				for (int[] it : snap)
				{
					if (itemManager.canonicalize(it[0]) == id)
					{
						inBank += it[1];
					}
				}
			}
			if (inInv + inBank == 0 && !resolver.ownedNames().containsKey(id))
			{
				throw new ToolException("The player does not own " + comp.getName()
					+ " (not in inventory, equipment or the bank snapshot), so it cannot be sold.");
			}
			out.addProperty("inInventory", inInv);
			out.addProperty("inBankSnapshot", inBank);
			if (inInv < quantity)
			{
				warnings.add("Only " + fmt(inInv) + " in the inventory (" + fmt(inBank) + " in the last bank snapshot) but "
					+ fmt(quantity) + " to sell: have the player withdraw " + fmt(Math.max(0, quantity - inInv))
					+ " as notes before selling.");
			}
		}
		long guide = itemManager.getItemPrice(id);
		if (guide > 0)
		{
			out.addProperty("guidePrice", guide);
			if (!buy && price < guide * (1 - WARN_FRACTION))
			{
				warnings.add("Sell price " + fmt(price) + " is more than 25% below the guide price " + fmt(guide)
					+ " - check for a typo.");
			}
			else if (buy && price > guide * (1 + WARN_FRACTION))
			{
				warnings.add("Buy price " + fmt(price) + " is more than 25% above the guide price " + fmt(guide)
					+ " - check for a typo.");
			}
		}
		else
		{
			warnings.add("No GE guide price is known for this item, so the price could not be sanity-checked.");
		}
		StagedOffers.Offer o = staged.add(id, comp.getName(), buy, quantity, price);
		if (o == null)
		{
			throw new ToolException("At most " + StagedOffers.MAX
				+ " staged offers are allowed; remove some with remove_staged_offer or clear_staged_offers.");
		}
		assist.refresh();
		out.add("offer", toJson(o));
		if (warnings.size() > 0)
		{
			out.add("warnings", warnings);
		}
		out.addProperty("note", "Staged. The player places and confirms the offer themselves at the Grand Exchange.");
		return out;
	}

	// ---- tools ----

	private class StageOffer implements Tool
	{
		@Override
		public String name()
		{
			return "stage_ge_offer";
		}

		@Override
		public String description()
		{
			return "Stage one Grand Exchange buy or sell offer for the player (item name, type buy|sell, quantity, "
				+ "price each in gp). Returns the staged offer with the current GE guide price and warnings (price more than "
				+ "25% off the guide, not enough items in the inventory for a sell, etc.)." + PLAYER_NOTE;
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.required("item", "string", "Exact in-game item name (or item id)")
				.required("type", "string", "\"buy\" or \"sell\"")
				.required("quantity", "integer", "How many items")
				.required("price", "integer", "Price each in gp")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			requireEnabled();
			String item = Args.str(args, "item", "");
			String type = Args.str(args, "type", "");
			int qty = Args.requireInt(args, "quantity");
			long price = longArg(args, "price");
			return reader.readLoggedIn(() -> wrap(() -> stageOne(item, type, qty, price)));
		}
	}

	private static long longArg(JsonObject args, String key) throws ToolException
	{
		JsonElement e = args == null ? null : args.get(key);
		if (e == null || e.isJsonNull())
		{
			throw new ToolException("Missing required argument: " + key);
		}
		try
		{
			return Math.round(e.getAsDouble());
		}
		catch (NumberFormatException | UnsupportedOperationException ex)
		{
			throw new ToolException(key + " must be a number");
		}
	}

	private interface Staging
	{
		JsonObject run() throws ToolException;
	}

	/** Staging code throws checked ToolExceptions; carry them out of the reader supplier. */
	private static JsonObject wrap(Staging s)
	{
		try
		{
			return s.run();
		}
		catch (ToolException e)
		{
			throw new ClientReader.ToolRuntimeException(e.getMessage());
		}
	}

	private class StageOffers implements Tool
	{
		@Override
		public String name()
		{
			return "stage_ge_offers";
		}

		@Override
		public String description()
		{
			return "Stage several Grand Exchange offers at once (array of {item, type, quantity, price}, up to " + MAX_BATCH
				+ "). Each is validated like stage_ge_offer; offers that fail are reported in 'errors' while the rest are staged."
				+ PLAYER_NOTE;
		}

		@Override
		public JsonObject inputSchema()
		{
			JsonObject item = new JsonObject();
			item.addProperty("type", "object");
			JsonObject props = new JsonObject();
			props.add("item", prop("string", "Exact in-game item name (or item id)"));
			props.add("type", prop("string", "\"buy\" or \"sell\""));
			props.add("quantity", prop("integer", "How many items"));
			props.add("price", prop("integer", "Price each in gp"));
			item.add("properties", props);
			JsonArray req = new JsonArray();
			req.add("item");
			req.add("type");
			req.add("quantity");
			req.add("price");
			item.add("required", req);

			JsonObject offers = new JsonObject();
			offers.addProperty("type", "array");
			offers.addProperty("description", "Offers to stage");
			offers.add("items", item);

			JsonObject props2 = new JsonObject();
			props2.add("offers", offers);
			JsonObject schema = new JsonObject();
			schema.addProperty("type", "object");
			schema.add("properties", props2);
			JsonArray req2 = new JsonArray();
			req2.add("offers");
			schema.add("required", req2);
			return schema;
		}

		private JsonObject prop(String type, String description)
		{
			JsonObject p = new JsonObject();
			p.addProperty("type", type);
			p.addProperty("description", description);
			return p;
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			requireEnabled();
			JsonElement e = args == null ? null : args.get("offers");
			if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() == 0)
			{
				throw new ToolException("offers must be a non-empty array of {item, type, quantity, price}");
			}
			JsonArray in = e.getAsJsonArray();
			if (in.size() > MAX_BATCH)
			{
				throw new ToolException("At most " + MAX_BATCH + " offers per call");
			}
			return reader.readLoggedIn(() ->
			{
				JsonArray ok = new JsonArray();
				JsonArray errors = new JsonArray();
				for (int i = 0; i < in.size(); i++)
				{
					try
					{
						JsonObject o = in.get(i).getAsJsonObject();
						ok.add(stageOne(Args.str(o, "item", ""), Args.str(o, "type", ""),
							Args.requireInt(o, "quantity"), longArg(o, "price")));
					}
					catch (ToolException | RuntimeException ex)
					{
						JsonObject err = new JsonObject();
						err.addProperty("index", i);
						err.addProperty("error", ex.getMessage());
						errors.add(err);
					}
				}
				JsonObject r = new JsonObject();
				r.add("staged", ok);
				r.addProperty("stagedCount", ok.size());
				if (errors.size() > 0)
				{
					r.add("errors", errors);
				}
				r.addProperty("note", "The player places and confirms each offer themselves at the Grand Exchange.");
				return r;
			});
		}
	}

	private class ListStaged implements Tool
	{
		@Override
		public String name()
		{
			return "list_staged_offers";
		}

		@Override
		public String description()
		{
			return "List the staged Grand Exchange offers: pending ones, plus ones the player placed in the last hour "
				+ "(status 'placed', detected automatically when a matching offer appears in a GE slot).";
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
			return reader.readLoggedIn(() ->
			{
				JsonArray arr = new JsonArray();
				int pending = 0;
				for (StagedOffers.Offer o : staged.all())
				{
					arr.add(toJson(o));
					if (o.isPending())
					{
						pending++;
					}
				}
				JsonObject r = new JsonObject();
				r.add("offers", arr);
				r.addProperty("count", arr.size());
				r.addProperty("pending", pending);
				return r;
			});
		}
	}

	private class RemoveStaged implements Tool
	{
		@Override
		public String name()
		{
			return "remove_staged_offer";
		}

		@Override
		public String description()
		{
			return "Remove one staged Grand Exchange offer by id (from list_staged_offers).";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object().required("id", "integer", "Staged offer id").build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			requireEnabled();
			int id = Args.requireInt(args, "id");
			return reader.readLoggedIn(() ->
			{
				if (!staged.remove(id))
				{
					throw new ClientReader.ToolRuntimeException("No staged offer with id " + id);
				}
				assist.refresh();
				JsonObject r = new JsonObject();
				r.addProperty("removed", id);
				return r;
			});
		}
	}

	private class ClearStaged implements Tool
	{
		@Override
		public String name()
		{
			return "clear_staged_offers";
		}

		@Override
		public String description()
		{
			return "Remove all staged Grand Exchange offers (pending and placed).";
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
			return reader.readLoggedIn(() ->
			{
				int n = staged.clear();
				assist.refresh();
				JsonObject r = new JsonObject();
				r.addProperty("cleared", n);
				return r;
			});
		}
	}
}
