package com.osrsmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import com.osrsmcp.game.BankCache;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.game.ItemInfo;
import com.osrsmcp.game.SessionStats;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.events.RuneScapeProfileChanged;

/** Player, skills, equipment, inventory, bank and session tools. */
@Singleton
public class AccountTools implements ToolGroup
{
	private static final String[] ACCOUNT_TYPES = {
		"none", "ironman", "ultimate", "hardcore", "group", "hardcore_group", "unranked_group"
	};
	private static final int INVENTORY_SIZE = 28;

	private final Client client;
	private final ClientReader reader;
	private final ItemInfo itemInfo;
	private final OsrsMcpConfig config;
	private final BankCache bankCache;
	private final SessionStats sessionStats;
	private final ItemManager itemManager;

	@Inject
	public AccountTools(Client client, ClientReader reader, ItemInfo itemInfo, OsrsMcpConfig config,
		BankCache bankCache, SessionStats sessionStats, ItemManager itemManager)
	{
		this.client = client;
		this.reader = reader;
		this.itemInfo = itemInfo;
		this.config = config;
		this.bankCache = bankCache;
		this.sessionStats = sessionStats;
		this.itemManager = itemManager;
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new GetPlayer(), new GetSkills(), new GetEquipment(), new GetInventory(),
			new GetBank(), new GetSessionStats());
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK)
		{
			bankCache.capture(event.getItemContainer());
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		bankCache.invalidate();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			sessionStats.reset();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		sessionStats.onTick(client);
	}

	private static Skill[] skills()
	{
		List<Skill> list = new ArrayList<>();
		for (Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
			{
				list.add(s);
			}
		}
		return list.toArray(new Skill[0]);
	}

	private static String key(Skill s)
	{
		return s.getName().toLowerCase(Locale.ROOT);
	}

	private static final String[] STAT_NAMES = {
		"astab", "aslash", "acrush", "amagic", "arange", "dstab", "dslash", "dcrush", "dmagic", "drange",
		"str", "rstr", "mdmg", "prayer", "aspeed"
	};
	private static final int MDMG = 12;
	private static final int ASPEED = 14;

	private static double[] statValues(ItemEquipmentStats e)
	{
		return new double[]{
			e.getAstab(), e.getAslash(), e.getAcrush(), e.getAmagic(), e.getArange(),
			e.getDstab(), e.getDslash(), e.getDcrush(), e.getDmagic(), e.getDrange(),
			e.getStr(), e.getRstr(), e.getMdmg(), e.getPrayer(), e.getAspeed()
		};
	}

	private static JsonObject statsJson(ItemEquipmentStats e)
	{
		double[] v = statValues(e);
		JsonObject o = new JsonObject();
		for (int i = 0; i < v.length; i++)
		{
			if (v[i] != 0)
			{
				if (i == MDMG)
				{
					o.addProperty(STAT_NAMES[i], v[i]);
				}
				else
				{
					o.addProperty(STAT_NAMES[i], (int) v[i]);
				}
			}
		}
		return o;
	}

	/** Adds every bonus except attack speed (not additive; set from the weapon separately). */
	private static void addTotals(double[] totals, ItemEquipmentStats e)
	{
		double[] v = statValues(e);
		for (int i = 0; i < v.length; i++)
		{
			if (i != ASPEED)
			{
				totals[i] += v[i];
			}
		}
	}

	private class GetPlayer implements Tool
	{
		@Override
		public String name()
		{
			return "get_player";
		}

		@Override
		public String description()
		{
			return "Current player status: name, combat level, world, account type, hitpoints, prayer, run energy, "
				+ "special attack, location, animation and current interaction target.";
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
				Player p = client.getLocalPlayer();
				JsonObject o = new JsonObject();
				o.addProperty("name", p.getName());
				o.addProperty("combatLevel", p.getCombatLevel());
				o.addProperty("world", client.getWorld());
				o.addProperty("membersWorld", client.getWorldType().contains(WorldType.MEMBERS));
				int type = client.getVarbitValue(VarbitID.IRONMAN);
				o.addProperty("accountType", type >= 0 && type < ACCOUNT_TYPES.length ? ACCOUNT_TYPES[type] : "unknown");

				JsonObject hp = new JsonObject();
				hp.addProperty("current", client.getBoostedSkillLevel(Skill.HITPOINTS));
				hp.addProperty("max", client.getRealSkillLevel(Skill.HITPOINTS));
				o.add("hitpoints", hp);
				JsonObject pray = new JsonObject();
				pray.addProperty("current", client.getBoostedSkillLevel(Skill.PRAYER));
				pray.addProperty("max", client.getRealSkillLevel(Skill.PRAYER));
				o.add("prayer", pray);
				o.addProperty("runEnergyPercent", client.getEnergy() / 100);
				o.addProperty("specialAttackPercent", client.getVarpValue(VarPlayerID.SA_ENERGY) / 10);

				WorldPoint wp = p.getWorldLocation();
				JsonObject loc = new JsonObject();
				loc.addProperty("x", wp.getX());
				loc.addProperty("y", wp.getY());
				loc.addProperty("plane", wp.getPlane());
				loc.addProperty("regionId", wp.getRegionID());
				o.add("location", loc);
				o.addProperty("inInstance", client.isInInstancedRegion());
				o.addProperty("animationId", p.getAnimation());
				Actor target = p.getInteracting();
				if (target != null && target.getName() != null)
				{
					o.addProperty("interacting", target.getName());
				}
				return o;
			});
		}
	}

	private class GetSkills implements Tool
	{
		@Override
		public String name()
		{
			return "get_skills";
		}

		@Override
		public String description()
		{
			return "All skill levels (real and boosted), XP, and XP remaining to the next level, plus total level and total XP.";
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
				JsonObject skillsObj = new JsonObject();
				long totalXp = 0;
				for (Skill s : skills())
				{
					int xp = client.getSkillExperience(s);
					int level = client.getRealSkillLevel(s);
					totalXp += xp;
					JsonObject o = new JsonObject();
					o.addProperty("level", level);
					o.addProperty("boosted", client.getBoostedSkillLevel(s));
					o.addProperty("xp", xp);
					if (level < Experience.MAX_REAL_LEVEL)
					{
						o.addProperty("xpToNextLevel", Experience.getXpForLevel(level + 1) - xp);
					}
					skillsObj.add(key(s), o);
				}
				JsonObject r = new JsonObject();
				r.add("skills", skillsObj);
				r.addProperty("totalLevel", client.getTotalLevel());
				r.addProperty("totalXp", totalXp);
				return r;
			});
		}
	}

	private class GetEquipment implements Tool
	{
		@Override
		public String name()
		{
			return "get_equipment";
		}

		@Override
		public String description()
		{
			return "Currently worn equipment by slot, with item names, GE and high-alch values, and totals. Each item has a \"stats\" object (non-zero equipment bonuses: "
				+ "astab/aslash/acrush/amagic/arange attack, dstab/dslash/dcrush/dmagic/drange defence, str, rstr ranged str, "
				+ "mdmg magic damage %, prayer, aspeed attack speed in ticks for weapons), and \"bonusTotals\" sums the bonuses across all worn items.";
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
				ItemContainer c = client.getItemContainer(InventoryID.WORN);
				JsonObject slots = new JsonObject();
				long totalGe = 0;
				long totalHa = 0;
				int count = 0;
				double[] totals = new double[STAT_NAMES.length];
				if (c != null)
				{
					Item[] items = c.getItems();
					for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
					{
						int idx = slot.getSlotIdx();
						if (idx >= items.length)
						{
							continue;
						}
						JsonObject d = itemInfo.describe(items[idx].getId(), items[idx].getQuantity());
						if (d == null)
						{
							continue;
						}
						ItemStats st = itemManager.getItemStats(items[idx].getId());
						ItemEquipmentStats eq = st == null ? null : st.getEquipment();
						if (eq != null)
						{
							d.add("stats", statsJson(eq));
							addTotals(totals, eq);
							if (slot == EquipmentInventorySlot.WEAPON)
							{
								totals[ASPEED] = eq.getAspeed();
							}
						}
						slots.add(slot.name().toLowerCase(Locale.ROOT), d);
						totalGe += d.get("geTotal").getAsLong();
						totalHa += d.get("haPrice").getAsLong() * items[idx].getQuantity();
						count++;
					}
				}
				JsonObject r = new JsonObject();
				r.add("slots", slots);
				r.addProperty("count", count);
				r.addProperty("totalGe", totalGe);
				r.addProperty("totalHa", totalHa);
				JsonObject bt = new JsonObject();
				for (int i = 0; i < STAT_NAMES.length; i++)
				{
					if (i == MDMG)
					{
						bt.addProperty(STAT_NAMES[i], Math.round(totals[i] * 10) / 10.0);
					}
					else
					{
						bt.addProperty(STAT_NAMES[i], (int) totals[i]);
					}
				}
				r.add("bonusTotals", bt);
				return r;
			});
		}
	}

	private class GetInventory implements Tool
	{
		@Override
		public String name()
		{
			return "get_inventory";
		}

		@Override
		public String description()
		{
			return "Items in the player inventory with names, quantities and values, plus free slot count.";
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
				ItemContainer c = client.getItemContainer(InventoryID.INV);
				Item[] items = c == null ? new Item[0] : c.getItems();
				int used = 0;
				for (Item it : items)
				{
					if (it.getId() > 0 && it.getQuantity() > 0)
					{
						used++;
					}
				}
				JsonObject r = itemInfo.describeAll(items);
				r.addProperty("freeSlots", INVENTORY_SIZE - used);
				return r;
			});
		}
	}

	private class GetBank implements Tool
	{
		@Override
		public String name()
		{
			return "get_bank";
		}

		@Override
		public String description()
		{
			return "Contents of the player bank from the last snapshot (taken whenever the bank is opened), "
				+ "sorted by total GE value. Optionally filter by name.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("query", "string", "Case-insensitive item name substring filter")
				.prop("limit", "integer", "Max items to return (default 200)")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			if (!config.exposeBank())
			{
				throw new ToolException("Bank access is disabled. The player can enable it in the OSRS MCP plugin settings (Data exposed to AI > Bank).");
			}
			String query = Args.str(args, "query", "").trim().toLowerCase(Locale.ROOT);
			int limit = Math.max(1, Args.integer(args, "limit", 200));
			return reader.read(() ->
			{
				int[][] snap = bankCache.items();
				if (snap == null)
				{
					throw new ClientReader.ToolRuntimeException("No bank snapshot yet. Ask the player to open their bank once in-game.");
				}
				long at = bankCache.capturedAt();
				JsonObject all = itemInfo.describeAll(snap);
				List<JsonObject> matched = new ArrayList<>();
				for (JsonElement e : all.getAsJsonArray("items"))
				{
					JsonObject o = e.getAsJsonObject();
					if (query.isEmpty() || o.get("name").getAsString().toLowerCase(Locale.ROOT).contains(query))
					{
						matched.add(o);
					}
				}
				matched.sort((a, b) -> Long.compare(b.get("geTotal").getAsLong(), a.get("geTotal").getAsLong()));
				JsonArray out = new JsonArray();
				for (int i = 0; i < matched.size() && i < limit; i++)
				{
					out.add(matched.get(i));
				}
				JsonObject r = new JsonObject();
				r.add("items", out);
				r.addProperty("matchedCount", matched.size());
				r.addProperty("returnedCount", out.size());
				r.addProperty("totalGe", all.get("totalGe").getAsLong());
				r.addProperty("snapshotAgeMinutes", Math.max(0, (System.currentTimeMillis() - at) / 60000));
				r.addProperty("capturedAt", Instant.ofEpochMilli(at).toString());
				return r;
			});
		}
	}

	private class GetSessionStats implements Tool
	{
		@Override
		public String name()
		{
			return "get_session_stats";
		}

		@Override
		public String description()
		{
			return "XP gained per skill since login (only skills with gains) and session duration.";
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
				if (!sessionStats.hasBaseline())
				{
					throw new ClientReader.ToolRuntimeException("Session has not started tracking yet; try again in a moment.");
				}
				JsonObject gains = new JsonObject();
				long total = 0;
				for (Skill s : skills())
				{
					int g = sessionStats.gained(client, s);
					if (g > 0)
					{
						gains.addProperty(key(s), g);
						total += g;
					}
				}
				long secs = (System.currentTimeMillis() - sessionStats.startMillis()) / 1000;
				JsonObject r = new JsonObject();
				r.add("xpGained", gains);
				r.addProperty("totalXpGained", total);
				r.addProperty("sessionSeconds", secs);
				r.addProperty("sessionMinutes", secs / 60);
				return r;
			});
		}
	}
}
