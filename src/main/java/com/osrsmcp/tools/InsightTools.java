package com.osrsmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.game.GeHistoryLog;
import com.osrsmcp.game.GeHistoryScraper;
import com.osrsmcp.game.ItemInfo;
import com.osrsmcp.game.KillCountLog;
import com.osrsmcp.game.LootLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.loottracker.LootRecordType;

/** Insight tools: GE offers, item lookup, kill counts, recent loot, combat achievements, active state. */
@Singleton
public class InsightTools implements ToolGroup
{
	private static final String[] SPELLBOOKS = {"standard", "ancient", "lunar", "arceuus"};
	private static final int[] CA_TIER_STATUS = {
		VarbitID.CA_TIER_STATUS_EASY, VarbitID.CA_TIER_STATUS_MEDIUM, VarbitID.CA_TIER_STATUS_HARD,
		VarbitID.CA_TIER_STATUS_ELITE, VarbitID.CA_TIER_STATUS_MASTER, VarbitID.CA_TIER_STATUS_GRANDMASTER
	};
	private static final int[] CA_THRESHOLD = {
		VarbitID.CA_THRESHOLD_EASY, VarbitID.CA_THRESHOLD_MEDIUM, VarbitID.CA_THRESHOLD_HARD,
		VarbitID.CA_THRESHOLD_ELITE, VarbitID.CA_THRESHOLD_MASTER, VarbitID.CA_THRESHOLD_GRANDMASTER
	};
	private static final String[] CA_TIERS = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	private static final int[] POUCH_TYPE = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};
	private static final int[] POUCH_QTY = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};
	private static final int VENOM_MIN = 1_000_000;
	/** RuneLite's PoisonPlugin convention: at or below this the player is immune to venom as well as poison. */
	private static final int VENOM_IMMUNE_MAX = -38;

	private final Client client;
	private final ClientReader reader;
	private final ItemInfo itemInfo;
	private final ItemManager itemManager;
	private final KillCountLog killCounts;
	private final LootLog lootLog;
	private final GeHistoryLog geHistory;
	private final GeHistoryScraper geScraper;

	@Inject
	public InsightTools(Client client, ClientReader reader, ItemInfo itemInfo, ItemManager itemManager,
		KillCountLog killCounts, LootLog lootLog, GeHistoryLog geHistory, GeHistoryScraper geScraper)
	{
		this.client = client;
		this.reader = reader;
		this.itemInfo = itemInfo;
		this.itemManager = itemManager;
		this.killCounts = killCounts;
		this.lootLog = lootLog;
		this.geHistory = geHistory;
		this.geScraper = geScraper;
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new GetGeOffers(), new LookupItem(), new GetKillCounts(), new GetRecentLoot(),
			new GetCombatAchievements(), new GetActiveState(), new GetGeHistory());
	}

	@Override
	public void startUp()
	{
		geHistory.init(client.getGameState());
	}

	@Override
	public void shutDown()
	{
		geHistory.clear();
		lootLog.clear();
		killCounts.invalidate();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		killCounts.onChatMessage(event);
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		killCounts.invalidate();
		geHistory.onProfileChanged();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		geHistory.onOfferChanged(event.getSlot(), event.getOffer());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		geHistory.onGameStateChanged(event.getGameState());
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		geHistory.onGameTick();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		geScraper.onWidgetLoaded(event.getGroupId());
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (event.getNpc() == null)
		{
			return;
		}
		lootLog.add(event.getNpc().getName(), event.getNpc().getCombatLevel(), event.getItems());
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		// Player (PvP) loot is never recorded.
		if (event.getType() == LootRecordType.PLAYER)
		{
			return;
		}
		lootLog.add(event.getName(), event.getCombatLevel(), event.getItems());
	}

	private static String lower(Skill s)
	{
		return s.getName().toLowerCase(Locale.ROOT);
	}

	private class GetGeOffers implements Tool
	{
		@Override
		public String name()
		{
			return "get_ge_offers";
		}

		@Override
		public String description()
		{
			return "The player's active Grand Exchange offers (up to 8 slots; empty slots are skipped): slot, state "
				+ "(BUYING/SELLING/BOUGHT/SOLD/CANCELLED_BUY/CANCELLED_SELL), item, offer price each, total quantity, "
				+ "quantity filled, gp spent/received so far, and the current GE guide price for comparison. "
				+ "Requires being logged in.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			return reader.readLoggedIn(() ->
			{
				JsonArray arr = new JsonArray();
				GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
				for (int slot = 0; offers != null && slot < offers.length; slot++)
				{
					GrandExchangeOffer offer = offers[slot];
					if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
					{
						continue;
					}
					GrandExchangeOfferState st = offer.getState();
					boolean buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
						|| st == GrandExchangeOfferState.CANCELLED_BUY;
					int id = offer.getItemId();
					JsonObject o = new JsonObject();
					o.addProperty("slot", slot);
					o.addProperty("state", st.name());
					o.addProperty("type", buy ? "buy" : "sell");
					o.addProperty("itemId", id);
					o.addProperty("item", itemManager.getItemComposition(id).getName());
					o.addProperty("price", offer.getPrice());
					o.addProperty("totalQuantity", offer.getTotalQuantity());
					o.addProperty("quantityFilled", offer.getQuantitySold());
					o.addProperty("remaining", offer.getTotalQuantity() - offer.getQuantitySold());
					o.addProperty("spent", offer.getSpent());
					long guide = itemManager.getItemPrice(id);
					o.addProperty("guidePrice", guide);
					if (guide > 0)
					{
						o.addProperty("priceVsGuidePercent", Math.round((offer.getPrice() - guide) * 1000.0 / guide) / 10.0);
					}
					arr.add(o);
				}
				JsonObject r = new JsonObject();
				r.add("offers", arr);
				r.addProperty("count", arr.size());
				return r;
			});
		}
	}

	private class LookupItem implements Tool
	{
		@Override
		public String name()
		{
			return "lookup_item";
		}

		@Override
		public String description()
		{
			return "Search items by name (any item, not only owned ones). Returns id, name, GE guide price, OSRS Wiki "
				+ "real-time price, high-alchemy value, members flag and GE buy limit when known. Exact name matches "
				+ "come first. Uses RuneLite's local price data; if nothing is returned the item data may not be "
				+ "loaded yet (try again after logging in).";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.required("name", "string", "Item name or part of it")
				.prop("limit", "integer", "Max results (default 5)")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			String name = Args.str(args, "name", "").trim();
			if (name.isEmpty())
			{
				throw new ToolException("Missing required argument: name");
			}
			int limit = Math.max(1, Math.min(50, Args.integer(args, "limit", 5)));
			return reader.read(() ->
			{
				List<ItemPrice> found = new ArrayList<>(itemManager.search(name));
				// stable sort: exact matches first, rest keep search order
				List<ItemPrice> ordered = new ArrayList<>();
				for (ItemPrice p : found)
				{
					if (p.getName().equalsIgnoreCase(name))
					{
						ordered.add(p);
					}
				}
				for (ItemPrice p : found)
				{
					if (!p.getName().equalsIgnoreCase(name))
					{
						ordered.add(p);
					}
				}
				JsonArray arr = new JsonArray();
				for (int i = 0; i < ordered.size() && i < limit; i++)
				{
					ItemPrice p = ordered.get(i);
					ItemComposition comp = itemManager.getItemComposition(p.getId());
					JsonObject o = new JsonObject();
					o.addProperty("id", p.getId());
					o.addProperty("name", p.getName());
					o.addProperty("gePrice", p.getPrice());
					o.addProperty("wikiPrice", itemManager.getWikiPrice(p));
					o.addProperty("haPrice", comp.getHaPrice());
					o.addProperty("members", comp.isMembers());
					ItemStats stats = itemManager.getItemStats(p.getId());
					if (stats != null && stats.getGeLimit() > 0)
					{
						o.addProperty("geLimit", stats.getGeLimit());
					}
					arr.add(o);
				}
				JsonObject r = new JsonObject();
				r.add("results", arr);
				r.addProperty("totalMatches", ordered.size());
				if (arr.size() == 0)
				{
					r.addProperty("note", "No items matched. Item/price data may not be loaded yet, or the name is wrong.");
				}
				return r;
			});
		}
	}

	private class GetKillCounts implements Tool
	{
		@Override
		public String name()
		{
			return "get_kill_counts";
		}

		@Override
		public String description()
		{
			return "Boss / raid kill counts and personal best times, sorted by name. Sources: counts this plugin saw "
				+ "the game print while it was running (\"Your X kill count is: N\", \"Your completed X count is: N\", "
				+ "and \"Fight duration\" PB lines), plus anything RuneLite's Chat Commands plugin saved for this account. "
				+ "A boss only appears once its count has been printed (or Chat Commands saved it), so missing bosses "
				+ "are unknown, not zero. Each entry has kc, pb (formatted) and pbSeconds when known, lastUpdated, and source.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("filter", "string", "Case-insensitive boss name substring filter")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			String filter = Args.str(args, "filter", "");
			// config reads are in-memory but keep game-profile access on the client thread
			List<JsonObject> list = reader.read(() -> killCounts.snapshot(filter));
			JsonArray arr = new JsonArray();
			for (JsonObject o : list)
			{
				arr.add(o);
			}
			JsonObject r = new JsonObject();
			r.add("bosses", arr);
			r.addProperty("count", arr.size());
			return r;
		}
	}

	private class GetRecentLoot implements Tool
	{
		@Override
		public String name()
		{
			return "get_recent_loot";
		}

		@Override
		public String description()
		{
			return "Recent NPC/event drops recorded since the plugin started this session (not persisted; max 200 kept; "
				+ "player/PvP loot is never recorded). Returns the newest drops first with items and GE values, plus a "
				+ "summary grouped by source (kills, total value, top items) over everything recorded that matches the filter.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("limit", "integer", "Max drops to list (default 20)")
				.prop("source", "string", "Case-insensitive source (NPC/event) name substring filter")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			int limit = Math.max(1, Math.min(200, Args.integer(args, "limit", 20)));
			String source = Args.str(args, "source", "").trim().toLowerCase(Locale.ROOT);
			List<LootLog.Drop> all = lootLog.all();
			return reader.read(() ->
			{
				long now = System.currentTimeMillis();
				JsonArray list = new JsonArray();
				Map<String, long[]> groupStats = new LinkedHashMap<>(); // kills, totalValue
				Map<String, Map<String, long[]>> groupItems = new HashMap<>(); // name -> {qty, value}
				int matched = 0;
				for (int i = all.size() - 1; i >= 0; i--)
				{
					LootLog.Drop d = all.get(i);
					if (!source.isEmpty() && !d.source.toLowerCase(Locale.ROOT).contains(source))
					{
						continue;
					}
					matched++;
					JsonObject described = itemInfo.describeAll(d.items);
					long value = described.get("totalGe").getAsLong();
					long[] gs = groupStats.computeIfAbsent(d.source, k -> new long[2]);
					gs[0]++;
					gs[1] += value;
					Map<String, long[]> items = groupItems.computeIfAbsent(d.source, k -> new HashMap<>());
					for (JsonElement e : described.getAsJsonArray("items"))
					{
						JsonObject it = e.getAsJsonObject();
						long[] acc = items.computeIfAbsent(it.get("name").getAsString(), k -> new long[2]);
						acc[0] += it.get("quantity").getAsLong();
						acc[1] += it.get("geTotal").getAsLong();
					}
					if (list.size() < limit)
					{
						JsonObject o = new JsonObject();
						o.addProperty("source", d.source);
						if (d.combatLevel > 0)
						{
							o.addProperty("combatLevel", d.combatLevel);
						}
						o.addProperty("time", Instant.ofEpochMilli(d.time).toString());
						o.addProperty("secondsAgo", Math.max(0, (now - d.time) / 1000));
						o.add("items", described.getAsJsonArray("items"));
						o.addProperty("totalGe", value);
						list.add(o);
					}
				}
				JsonArray summary = new JsonArray();
				for (Map.Entry<String, long[]> e : groupStats.entrySet())
				{
					JsonObject s = new JsonObject();
					s.addProperty("source", e.getKey());
					s.addProperty("kills", e.getValue()[0]);
					s.addProperty("totalValue", e.getValue()[1]);
					List<Map.Entry<String, long[]>> top = new ArrayList<>(groupItems.get(e.getKey()).entrySet());
					top.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
					JsonArray topArr = new JsonArray();
					for (int i = 0; i < top.size() && i < 5; i++)
					{
						JsonObject t = new JsonObject();
						t.addProperty("name", top.get(i).getKey());
						t.addProperty("quantity", top.get(i).getValue()[0]);
						t.addProperty("totalGe", top.get(i).getValue()[1]);
						topArr.add(t);
					}
					s.add("topItems", topArr);
					summary.add(s);
				}
				JsonObject r = new JsonObject();
				r.add("drops", list);
				r.addProperty("matchedDrops", matched);
				r.addProperty("recordedDrops", all.size());
				r.add("summaryBySource", summary);
				return r;
			});
		}
	}

	private class GetCombatAchievements implements Tool
	{
		@Override
		public String name()
		{
			return "get_combat_achievements";
		}

		@Override
		public String description()
		{
			return "Combat Achievements progress: total CA points, the points threshold of each tier (easy..grandmaster), "
				+ "the highest tier whose threshold the points meet (derived), and each tier's raw status varbit value. "
				+ "The raw status meaning is not documented by the API (likely 0 = locked, higher = unlocked/claimed); "
				+ "treat it as informational. Values may read 0 until the Combat Achievements interface has been opened "
				+ "once since login.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			return reader.readLoggedIn(() ->
			{
				int points = client.getVarbitValue(VarbitID.CA_POINTS);
				JsonObject tiers = new JsonObject();
				String reached = "none";
				for (int i = 0; i < CA_TIERS.length; i++)
				{
					int threshold = client.getVarbitValue(CA_THRESHOLD[i]);
					JsonObject t = new JsonObject();
					t.addProperty("pointsThreshold", threshold);
					t.addProperty("rawStatus", client.getVarbitValue(CA_TIER_STATUS[i]));
					if (threshold > 0)
					{
						t.addProperty("thresholdMet", points >= threshold);
						if (points >= threshold)
						{
							reached = CA_TIERS[i];
						}
						else if (!t.has("pointsToGo"))
						{
							t.addProperty("pointsToGo", threshold - points);
						}
					}
					tiers.add(CA_TIERS[i], t);
				}
				JsonObject r = new JsonObject();
				r.addProperty("points", points);
				r.addProperty("highestTierByPoints", reached);
				r.add("tiers", tiers);
				r.addProperty("note", "rawStatus semantics are unverified; thresholds/points are the reliable fields.");
				return r;
			});
		}
	}

	private class GetActiveState implements Tool
	{
		@Override
		public String name()
		{
			return "get_active_state";
		}

		@Override
		public String description()
		{
			return "Live combat-related state, read-only: active prayers, current spellbook, rune pouch contents, "
				+ "boosted/drained skills (boosted level != real level), poison/venom/immunity state, and whether the "
				+ "player is currently interacting with an NPC target.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			return reader.readLoggedIn(() ->
			{
				JsonObject r = new JsonObject();

				JsonArray prayers = new JsonArray();
				for (Prayer p : Prayer.values())
				{
					if (client.isPrayerActive(p))
					{
						prayers.add(p.name().toLowerCase(Locale.ROOT));
					}
				}
				r.add("activePrayers", prayers);

				int book = client.getVarbitValue(VarbitID.SPELLBOOK);
				r.addProperty("spellbook", book >= 0 && book < SPELLBOOKS.length ? SPELLBOOKS[book] : "unknown(" + book + ")");

				r.add("runePouch", runePouch());

				JsonObject boosts = new JsonObject();
				for (Skill s : Skill.values())
				{
					if (s == Skill.OVERALL)
					{
						continue;
					}
					int real = client.getRealSkillLevel(s);
					int boosted = client.getBoostedSkillLevel(s);
					if (real != boosted)
					{
						JsonObject b = new JsonObject();
						b.addProperty("real", real);
						b.addProperty("current", boosted);
						b.addProperty("diff", boosted - real);
						boosts.add(lower(s), b);
					}
				}
				r.add("boostedSkills", boosts);

				int poison = client.getVarpValue(VarPlayerID.POISON);
				JsonObject ps = new JsonObject();
				ps.addProperty("raw", poison);
				String state;
				if (poison >= VENOM_MIN)
				{
					state = "venomed";
				}
				else if (poison > 0)
				{
					state = "poisoned";
				}
				else if (poison <= VENOM_IMMUNE_MAX)
				{
					state = "immune_to_venom_and_poison";
				}
				else if (poison < 0)
				{
					state = "immune_to_poison";
				}
				else
				{
					state = "none";
				}
				ps.addProperty("state", state);
				r.add("poison", ps);

				Actor target = client.getLocalPlayer().getInteracting();
				JsonObject combat = new JsonObject();
				boolean npcTarget = target instanceof net.runelite.api.NPC;
				combat.addProperty("inCombat", target != null);
				if (npcTarget && target.getName() != null)
				{
					combat.addProperty("target", target.getName());
				}
				else if (target != null)
				{
					// never name other players
					combat.addProperty("targetIsPlayer", true);
				}
				r.add("combat", combat);
				return r;
			});
		}

		private JsonObject runePouch()
		{
			JsonObject out = new JsonObject();
			JsonArray runes = new JsonArray();
			EnumComposition runeEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
			for (int i = 0; i < POUCH_TYPE.length; i++)
			{
				int type = client.getVarbitValue(POUCH_TYPE[i]);
				int qty = client.getVarbitValue(POUCH_QTY[i]);
				if (type == 0 || qty <= 0)
				{
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("slot", i + 1);
				o.addProperty("typeIndex", type);
				int itemId = runeEnum == null ? -1 : runeEnum.getIntValue(type);
				if (itemId > 0)
				{
					o.addProperty("itemId", itemId);
					o.addProperty("rune", itemManager.getItemComposition(itemId).getName());
				}
				o.addProperty("quantity", qty);
				runes.add(o);
			}
			out.add("runes", runes);
			out.addProperty("note", "Read from game variables; reflects the last pouch contents even if no pouch is currently carried.");
			return out;
		}
	}

	private class GetGeHistory implements Tool
	{
		@Override
		public String name()
		{
			return "get_ge_history";
		}

		@Override
		public String description()
		{
			return "The player's own Grand Exchange trade history (completed buys/sells, plus cancelled offers that "
				+ "partly filled), newest first, with a per-item summary (bought/sold quantity and coins, average "
				+ "prices, estimated tax and estimated profit). Limits: the game exposes no history API, so this only "
				+ "knows trades that completed while the plugin was running (including offers that finished while "
				+ "logged out, flagged completedWhileOffline with approximate time), plus up to 20 rows imported "
				+ "whenever the player opens the in-game GE History tab (source history-tab, no timestamp). It is not "
				+ "a complete account history. Works while logged out if a profile was loaded earlier this session.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("item", "string", "Case-insensitive item name substring filter")
				.prop("type", "string", "\"buy\" or \"sell\"")
				.prop("limit", "integer", "Max trades to list (default 50, max 500); the summary covers all matches")
				.prop("days", "integer", "Only trades recorded within the last N days (imported rows without a time are excluded)")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			String item = Args.str(args, "item", "").trim().toLowerCase(Locale.ROOT);
			String type = Args.str(args, "type", "").trim().toLowerCase(Locale.ROOT);
			if (!type.isEmpty() && !type.equals("buy") && !type.equals("sell"))
			{
				throw new ToolException("type must be \"buy\" or \"sell\"");
			}
			int limit = Math.max(1, Math.min(500, Args.integer(args, "limit", 50)));
			Integer days = Args.optInt(args, "days");
			return reader.read(() ->
			{
				if (!geHistory.hasProfile())
				{
					throw new ClientReader.ToolRuntimeException("Not logged in. Log in to the game in RuneLite first so the account's trade log can be loaded.");
				}
				List<GeHistoryLog.Trade> all = geHistory.snapshot();
				long now = System.currentTimeMillis();
				long cutoff = days == null ? Long.MIN_VALUE : now - days * 86_400_000L;

				long recordingSince = 0;
				int live = 0;
				int imported = 0;
				for (GeHistoryLog.Trade t : all)
				{
					if (GeHistoryLog.SOURCE_LIVE.equals(t.source))
					{
						live++;
						if (recordingSince == 0 || t.time < recordingSince)
						{
							recordingSince = t.time;
						}
					}
					else
					{
						imported++;
					}
				}

				JsonArray list = new JsonArray();
				Map<Integer, long[]> sums = new LinkedHashMap<>(); // boughtQty, boughtCoins, soldQty, soldCoins, soldTax
				Map<Integer, String> names = new HashMap<>();
				int matched = 0;
				for (GeHistoryLog.Trade t : all)
				{
					if (!item.isEmpty() && !t.itemName.toLowerCase(Locale.ROOT).contains(item))
					{
						continue;
					}
					if (type.equals("buy") && !t.buy || type.equals("sell") && t.buy)
					{
						continue;
					}
					if (days != null && (t.time <= 0 || t.time < cutoff))
					{
						continue;
					}
					matched++;
					long[] acc = sums.computeIfAbsent(t.itemId, k -> new long[4]);
					names.put(t.itemId, t.itemName);
					if (t.buy)
					{
						acc[0] += t.quantity;
						acc[1] += t.totalCoins;
					}
					else
					{
						acc[2] += t.quantity;
						acc[3] += t.totalCoins;
					}
					if (list.size() < limit)
					{
						JsonObject o = new JsonObject();
						if (t.time > 0)
						{
							o.addProperty("time", Instant.ofEpochMilli(t.time).toString());
							o.addProperty("secondsAgo", Math.max(0, (now - t.time) / 1000));
						}
						o.addProperty("item", t.itemName);
						o.addProperty("itemId", t.itemId);
						o.addProperty("type", t.buy ? "buy" : "sell");
						o.addProperty("quantity", t.quantity);
						if (t.offerPrice > 0)
						{
							o.addProperty("offerPrice", t.offerPrice);
						}
						o.addProperty("totalCoins", t.totalCoins);
						o.addProperty("avgPrice", t.quantity > 0 ? t.totalCoins / t.quantity : 0);
						o.addProperty("cancelled", t.cancelled);
						o.addProperty("source", t.source);
						if (t.completedWhileOffline)
						{
							o.addProperty("completedWhileOffline", true);
						}
						list.add(o);
					}
				}

				List<Map.Entry<Integer, long[]>> entries = new ArrayList<>(sums.entrySet());
				entries.sort((a, b) -> Long.compare(b.getValue()[1] + b.getValue()[3], a.getValue()[1] + a.getValue()[3]));
				JsonArray summary = new JsonArray();
				for (Map.Entry<Integer, long[]> e : entries)
				{
					long[] a = e.getValue();
					JsonObject s = new JsonObject();
					s.addProperty("item", names.get(e.getKey()));
					s.addProperty("itemId", e.getKey());
					s.addProperty("boughtQty", a[0]);
					s.addProperty("boughtCoins", a[1]);
					if (a[0] > 0)
					{
						s.addProperty("avgBuy", a[1] / a[0]);
					}
					s.addProperty("soldQty", a[2]);
					s.addProperty("soldCoins", a[3]);
					if (a[2] > 0)
					{
						s.addProperty("avgSell", a[3] / a[2]);
					}
					if (a[0] > 0 && a[2] > 0)
					{
						long avgBuy = a[1] / a[0];
						s.addProperty("estimatedProfit", a[3] - avgBuy * a[2]);
					}
					summary.add(s);
				}

				JsonObject bySource = new JsonObject();
				bySource.addProperty("live", live);
				bySource.addProperty("history-tab", imported);

				JsonObject r = new JsonObject();
				r.add("trades", list);
				r.addProperty("matchedTrades", matched);
				r.addProperty("recordedTrades", all.size());
				r.add("summaryByItem", summary);
				r.add("countsBySource", bySource);
				if (recordingSince > 0)
				{
					r.addProperty("recordingSince", Instant.ofEpochMilli(recordingSince).toString());
				}
				r.addProperty("note", "Sell totals (totalCoins, soldCoins, avgSell) are what the player received, already net "
					+ "of the 2% GE tax; do not subtract tax again. estimatedProfit = soldCoins - avgBuy * soldQty, only for "
					+ "items with both buys and sells. History-tab rows have no timestamp and cannot show offer prices.");
				return r;
			});
		}

	}
}
