package com.osrsmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.game.ContainerCache;
import com.osrsmcp.game.ItemInfo;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/** Minigame points, non-bank containers, and assorted account progress counters. */
@Singleton
public class ProgressExtraTools implements ToolGroup
{
	private static final int KINGDOM_MAX_APPROVAL = 127;
	private static final String KINGDOM_GROUP = "kingdomofmiscellania";

	private static final String[] POH_LOCATIONS = {
		"Rimmington", "Taverley", "Pollnivneach", "Hosidius", "Rellekka", "Brimhaven", "Yanille", "Prifddinas"
	};

	private final Client client;
	private final ClientReader reader;
	private final ItemInfo itemInfo;
	private final ContainerCache containerCache;
	private final ConfigManager configManager;

	@Inject
	public ProgressExtraTools(Client client, ClientReader reader, ItemInfo itemInfo, ContainerCache containerCache,
		ConfigManager configManager)
	{
		this.client = client;
		this.reader = reader;
		this.itemInfo = itemInfo;
		this.containerCache = containerCache;
		this.configManager = configManager;
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new GetMinigamePoints(), new GetContainers(), new GetAccountProgress());
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (ContainerCache.isTracked(event.getContainerId()))
		{
			containerCache.capture(event.getContainerId(), event.getItemContainer());
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		containerCache.invalidate();
	}

	private Integer kingdomConfigInt(String key)
	{
		try
		{
			String s = configManager.getRSProfileConfiguration(KINGDOM_GROUP, key);
			return s == null || s.isEmpty() ? null : Integer.valueOf(s.trim());
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	// ---------------------------------------------------------------- get_minigame_points

	private class GetMinigamePoints implements Tool
	{
		@Override
		public String name()
		{
			return "get_minigame_points";
		}

		@Override
		public String description()
		{
			return "Minigame / reward points and coffers: Kingdom of Miscellania approval (raw /127 and %) and coffer, "
				+ "Barbarian Assault points per role, Nightmare Zone points, Blast Furnace coffer, Tithe Farm reward points, "
				+ "Soul Wars zeal, Giants' Foundry points, Mastering Mixology points. Values are the game's current stored "
				+ "values; Miscellania values only update while the player is in the kingdom.";
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

				// Kingdom of Miscellania (mirrors RuneLite's KingdomPlugin: percent = approval * 100 / 127)
				JsonObject k = new JsonObject();
				int approval = client.getVarbitValue(VarbitID.MISC_APPROVAL);
				int coffer = client.getVarbitValue(VarbitID.MISC_COFFERS);
				String source = "game varbits";
				if (approval == 0 && coffer == 0)
				{
					Integer cfgApproval = kingdomConfigInt("approval");
					Integer cfgCoffer = kingdomConfigInt("coffer");
					if (cfgApproval != null && cfgCoffer != null)
					{
						approval = cfgApproval;
						coffer = cfgCoffer;
						source = "RuneLite Kingdom plugin stored value (from the last Miscellania visit; may be out of date)";
					}
					else
					{
						source = "unknown - varbits read 0 outside Miscellania and no stored value; visit Miscellania once";
					}
				}
				k.addProperty("approvalRaw", approval);
				k.addProperty("approvalMaxRaw", KINGDOM_MAX_APPROVAL);
				k.addProperty("approvalPercent", approval * 100 / KINGDOM_MAX_APPROVAL);
				k.addProperty("cofferCoins", coffer);
				k.addProperty("source", source);
				JsonObject res = new JsonObject();
				res.addProperty("wood", client.getVarbitValue(VarbitID.MISC_POINTS_WOOD));
				res.addProperty("herb", client.getVarbitValue(VarbitID.MISC_POINTS_HERB));
				res.addProperty("fish", client.getVarbitValue(VarbitID.MISC_POINTS_FISH));
				res.addProperty("mine", client.getVarbitValue(VarbitID.MISC_POINTS_MINE));
				res.addProperty("rarewood", client.getVarbitValue(VarbitID.MISC_POINTS_RAREWOOD));
				res.addProperty("farm", client.getVarbitValue(VarbitID.MISC_POINTS_FARM));
				k.add("resourcePointsRaw", res);
				k.addProperty("note", "resourcePointsRaw are the raw MISC_POINTS_* varbits (meaning unverified in-game)");
				r.add("kingdomOfMiscellania", k);

				JsonObject ba = new JsonObject();
				baRole(ba, "attacker", VarbitID.BARBASSAULT_POINTS_ATTACKER_BASE, VarbitID.BARBASSAULT_POINTS_ATTACKER_EXTRA);
				baRole(ba, "collector", VarbitID.BARBASSAULT_POINTS_COLLECTOR_BASE, VarbitID.BARBASSAULT_POINTS_COLLECTOR_EXTRA);
				baRole(ba, "healer", VarbitID.BARBASSAULT_POINTS_HEALER_BASE, VarbitID.BARBASSAULT_POINTS_HEALER_EXTRA);
				baRole(ba, "defender", VarbitID.BARBASSAULT_POINTS_DEFENDER_BASE, VarbitID.BARBASSAULT_POINTS_DEFENDER_EXTRA);
				ba.addProperty("note", "base and extra are separate varbits; 'total' is base + extra (assumed combination, unverified)");
				r.add("barbarianAssault", ba);

				JsonObject nz = new JsonObject();
				nz.addProperty("rewardPoints", client.getVarpValue(VarPlayerID.NZONE_REWARDPOINTS));
				nz.addProperty("currentPoints", client.getVarbitValue(VarbitID.NZONE_CURRENTPOINTS));
				r.add("nightmareZone", nz);

				JsonObject bf = new JsonObject();
				bf.addProperty("cofferCoins", client.getVarbitValue(VarbitID.BR_COFFER));
				r.add("blastFurnace", bf);

				JsonObject tithe = new JsonObject();
				tithe.addProperty("rewardPoints", client.getVarbitValue(VarbitID.HOSIDIUS_TITHE_REWARDPOINTS));
				r.add("titheFarm", tithe);

				JsonObject sw = new JsonObject();
				sw.addProperty("zeal", client.getVarpValue(VarPlayerID.SOUL_WARS_ZEAL));
				r.add("soulWars", sw);

				JsonObject gf = new JsonObject();
				gf.addProperty("rewardShopPoints", client.getVarpValue(VarPlayerID.GIANTS_FOUNDRY_REWARD_SHOP_POINTS));
				r.add("giantsFoundry", gf);

				JsonObject mix = new JsonObject();
				mix.addProperty("lyePoints", client.getVarpValue(VarPlayerID.MIXOLOGY_LYE_POINTS));
				mix.addProperty("agaPoints", client.getVarpValue(VarPlayerID.MIXOLOGY_AGA_POINTS));
				mix.addProperty("moxPoints", client.getVarpValue(VarPlayerID.MIXOLOGY_MOX_POINTS));
				r.add("masteringMixology", mix);

				r.addProperty("notIncluded", "Pest Control, Mahogany Homes, Guardians of the Rift, Last Man Standing and "
					+ "Volcanic Mine points have no clearly-named game variable and are not exposed.");
				return r;
			});
		}

		private void baRole(JsonObject ba, String role, int baseVarbit, int extraVarbit)
		{
			int base = client.getVarbitValue(baseVarbit);
			int extra = client.getVarbitValue(extraVarbit);
			JsonObject o = new JsonObject();
			o.addProperty("base", base);
			o.addProperty("extra", extra);
			o.addProperty("total", base + extra);
			ba.add(role, o);
		}
	}

	// ---------------------------------------------------------------- get_containers

	private class GetContainers implements Tool
	{
		@Override
		public String name()
		{
			return "get_containers";
		}

		@Override
		public String description()
		{
			return "Contents of the player's own non-bank storage: looting bag, seed box, seed vault, tackle box, forestry kit, "
				+ "huntsman's kit, bonds pouch, Dizana's quiver ammo, barbarian knapsack and POH costume room cases. "
				+ "Snapshots are taken when each container is opened or changes, so each result has an age; containers never "
				+ "seen need to be opened once in-game. Optional name filter.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("name", "string", "Case-insensitive substring of the container name, e.g. 'seed' or 'looting'")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			String filter = Args.str(args, "name", "").trim().toLowerCase(Locale.ROOT);
			return reader.readLoggedIn(() ->
			{
				JsonArray out = new JsonArray();
				long now = System.currentTimeMillis();
				for (Map.Entry<Integer, String> e : ContainerCache.tracked().entrySet())
				{
					int id = e.getKey();
					String name = e.getValue();
					if (!filter.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(filter))
					{
						continue;
					}
					ItemContainer live = client.getItemContainer(id);
					if (live != null)
					{
						containerCache.capture(id, live);
					}
					ContainerCache.Snapshot snap = containerCache.get(id);
					JsonObject c = new JsonObject();
					c.addProperty("name", name);
					c.addProperty("containerId", id);
					if (snap == null)
					{
						c.addProperty("status", "never seen - open it once");
					}
					else
					{
						JsonObject d = itemInfo.describeAll(snap.items);
						c.add("items", d.getAsJsonArray("items"));
						c.addProperty("count", d.get("count").getAsInt());
						c.addProperty("totalGe", d.get("totalGe").getAsLong());
						c.addProperty("totalHa", d.get("totalHa").getAsLong());
						c.addProperty("snapshotAgeMinutes", Math.max(0, (now - snap.capturedAt) / 60000));
						c.addProperty("currentlyLoaded", live != null);
					}
					out.add(c);
				}
				JsonObject r = new JsonObject();
				r.add("containers", out);
				if (out.size() == 0)
				{
					r.addProperty("note", "No container name matches '" + filter + "'.");
				}
				return r;
			});
		}
	}

	// ---------------------------------------------------------------- get_account_progress

	private static final class Total
	{
		final String label;
		final int varp;

		Total(String label, int varp)
		{
			this.label = label;
			this.varp = varp;
		}
	}

	private static final Total[] TOTALS = {
		new Total("barrowsChests", VarPlayerID.TOTAL_BARROWS_CHESTS),
		new Total("wintertodtKills", VarPlayerID.TOTAL_WINTERTODT_KILLS),
		new Total("zulrahKills", VarPlayerID.TOTAL_SNAKEBOSS_KILLS),
		new Total("temporossKills", VarPlayerID.TOTAL_TEMPOROSS_KILLS),
		new Total("guardiansOfTheRiftKills", VarPlayerID.TOTAL_GOTR_KILLS),
		new Total("zalcanoKills", VarPlayerID.TOTAL_ZALCANO_KILLS),
		new Total("mimicKills", VarPlayerID.TOTAL_MIMIC_KILLS),
		new Total("nightmareKills", VarPlayerID.TOTAL_NIGHTMARE_KILLS),
		new Total("nexKills", VarPlayerID.TOTAL_NEX_KILLS),
		new Total("vorkathKills", VarPlayerID.TOTAL_VORKATH_KILLS),
		new Total("cerberusKills", VarPlayerID.TOTAL_CERBERUS_KILLS),
		new Total("abyssalSireKills", VarPlayerID.TOTAL_ABYSSALSIRE_KILLS),
		new Total("krakenKills", VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS),
		new Total("thermonuclearSmokeDevilKills", VarPlayerID.TOTAL_THERMY_KILLS),
		new Total("hydraKills", VarPlayerID.TOTAL_HYDRABOSS_KILLS),
		new Total("sarachnisKills", VarPlayerID.TOTAL_SARACHNIS_KILLS),
		new Total("kalphiteQueenKills", VarPlayerID.TOTAL_KALPHITE_KILLS),
		new Total("giantMoleKills", VarPlayerID.TOTAL_MOLE_KILLS),
		new Total("kingBlackDragonKills", VarPlayerID.TOTAL_KBD_KILLS),
		new Total("corporealBeastKills", VarPlayerID.TOTAL_CORP_KILLS),
		new Total("callistoKills", VarPlayerID.TOTAL_CALLISTO_KILLS),
		new Total("venenatisKills", VarPlayerID.TOTAL_VENENATIS_KILLS),
		new Total("vetionKills", VarPlayerID.TOTAL_VETION_KILLS),
		new Total("scorpiaKills", VarPlayerID.TOTAL_SCORPIA_KILLS),
		new Total("fightCavesJadKills", VarPlayerID.TOTAL_JAD_KILLS),
		new Total("infernoZukKills", VarPlayerID.TOTAL_ZUK_KILLS),
		new Total("bryophytaKills", VarPlayerID.TOTAL_BRYOPHYTA_KILLS),
		new Total("hesporiKills", VarPlayerID.TOTAL_HESPORI_KILLS),
		new Total("muspahKills", VarPlayerID.TOTAL_MUSPAH_KILLS),
		new Total("dukeSucellusKills", VarPlayerID.TOTAL_DUKE_SUCELLUS_KILLS),
		new Total("leviathanKills", VarPlayerID.TOTAL_LEVIATHAN_KILLS),
		new Total("whispererKills", VarPlayerID.TOTAL_WHISPERER_KILLS),
		new Total("vardorvisKills", VarPlayerID.TOTAL_VARDORVIS_KILLS),
		new Total("araxxorKills", VarPlayerID.TOTAL_ARAXXOR_KILLS),
		new Total("xericChambersCompleted", VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS),
		new Total("xericChallengeModeCompleted", VarPlayerID.TOTAL_COMPLETED_XERICCHAMBERS_CHALLENGE),
		new Total("theatreOfBloodCompleted", VarPlayerID.TOTAL_COMPLETED_THEATREOFBLOOD),
		new Total("tombsOfAmascutCompleted", VarPlayerID.TOTAL_COMPLETED_TOMBSOFAMASCUT),
		new Total("gauntletCompleted", VarPlayerID.TOTAL_COMPLETED_GAUNTLET),
		new Total("corruptedGauntletCompleted", VarPlayerID.TOTAL_COMPLETED_GAUNTLET_HM),
		new Total("colosseumWavesCompleted", VarPlayerID.TOTAL_COLOSSEUM_WAVES_COMPLETED),
	};

	private class GetAccountProgress implements Tool
	{
		@Override
		public String name()
		{
			return "get_account_progress";
		}

		@Override
		public String description()
		{
			return "Account-wide progress counters straight from game variables: total level, carry weight, collection log "
				+ "count, clue scrolls completed (total plus raw per-tier counters), Barrows chests, Wintertodt/Zulrah/other "
				+ "boss and raid completion totals (zero entries omitted), prayer unlocks (Rigour/Augury/Preserve) and "
				+ "player-owned house location.";
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
				r.addProperty("totalLevel", client.getTotalLevel());
				r.addProperty("weightKg", client.getWeight());

				JsonObject clog = new JsonObject();
				clog.addProperty("logged", client.getVarpValue(VarPlayerID.COLLECTION_COUNT));
				clog.addProperty("max", client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX));
				clog.addProperty("note", "Same variable RuneLite's !clog uses; may read 0 until the collection log has been opened once after login.");
				r.add("collectionLog", clog);

				JsonObject clues = new JsonObject();
				clues.addProperty("total", client.getVarpValue(VarPlayerID.COMPLETED_CLUES_TOTAL));
				JsonObject tiers = new JsonObject();
				tiers.addProperty("COMPLETED_CLUES", client.getVarpValue(VarPlayerID.COMPLETED_CLUES));
				tiers.addProperty("COMPLETED_CLUES1", client.getVarpValue(VarPlayerID.COMPLETED_CLUES1));
				tiers.addProperty("COMPLETED_CLUES2", client.getVarpValue(VarPlayerID.COMPLETED_CLUES2));
				tiers.addProperty("COMPLETED_CLUES3", client.getVarpValue(VarPlayerID.COMPLETED_CLUES3));
				tiers.addProperty("COMPLETED_CLUES4", client.getVarpValue(VarPlayerID.COMPLETED_CLUES4));
				tiers.addProperty("COMPLETED_CLUES5", client.getVarpValue(VarPlayerID.COMPLETED_CLUES5));
				clues.add("perTierRaw", tiers);
				clues.addProperty("note", "The tier of each raw counter is not documented, so they are NOT labelled. "
					+ "Compare against the in-game clue scroll log to map them.");
				r.add("clueScrollsCompleted", clues);

				JsonObject totals = new JsonObject();
				for (Total t : TOTALS)
				{
					int v = client.getVarpValue(t.varp);
					if (v != 0)
					{
						totals.addProperty(t.label, v);
					}
				}
				r.add("completionTotals", totals);

				JsonObject prayers = new JsonObject();
				prayers.addProperty("rigourUnlocked", client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED) != 0);
				prayers.addProperty("auguryUnlocked", client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED) != 0);
				prayers.addProperty("preserveUnlocked", client.getVarbitValue(VarbitID.PRAYER_PRESERVE_UNLOCKED) != 0);
				r.add("prayerUnlocks", prayers);

				JsonObject poh = new JsonObject();
				int loc = client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION);
				poh.addProperty("locationRaw", loc);
				poh.addProperty("location", loc >= 0 && loc < POH_LOCATIONS.length ? POH_LOCATIONS[loc] : "unknown (raw value " + loc + ")");
				poh.addProperty("note", "Location name is a best-guess mapping of the raw varbit (0=Rimmington ... 7=Prifddinas); 0 may also mean no house.");
				r.add("playerOwnedHouse", poh);
				return r;
			});
		}
	}
}
