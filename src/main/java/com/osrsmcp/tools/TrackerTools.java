package com.osrsmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.game.FarmingTimers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;

/** Farming / bird house timers and item charges, read from RuneLite's built-in plugins' saved data, plus live potion timers. */
@Singleton
public class TrackerTools implements ToolGroup
{
	private static final double TICK_SECONDS = 0.6;
	private static final String CHARGE_GROUP = "itemCharge";

	private final Client client;
	private final ClientReader reader;
	private final ConfigManager configManager;
	private final FarmingTimers farmingTimers;

	@Inject
	public TrackerTools(Client client, ClientReader reader, ConfigManager configManager, FarmingTimers farmingTimers)
	{
		this.client = client;
		this.reader = reader;
		this.configManager = configManager;
		this.farmingTimers = farmingTimers;
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new GetFarmingTimers(), new GetChargesAndTimers());
	}

	private final class GetFarmingTimers implements Tool
	{
		@Override
		public String name()
		{
			return "get_farming_timers";
		}

		@Override
		public String description()
		{
			return "Farming patches and bird houses as saved by RuneLite's built-in Time Tracking plugin, with the last time each was "
				+ "recorded. Only patches/bird houses visited while Time Tracking was enabled appear, so the list can be incomplete or stale. "
				+ "Crop type/growth state is NOT decoded: each patch has a raw varbit value (rawValue) plus patch name, region, patch type, "
				+ "and age since last seen. Bird houses include type, state (empty/built/seeded) and a ready-after-50-minutes estimate. "
				+ "Works while logged out as long as a RuneScape profile is loaded.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("filter", "string", "Optional case-insensitive substring of a patch, region or type name (e.g. 'falador', 'herb', 'tree')")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			JsonObject out = farmingTimers.read(Args.str(args, "filter", ""));
			if (out == null)
			{
				throw new ToolException("No RuneScape profile is loaded yet. Log in to the game once in RuneLite.");
			}
			return out;
		}
	}

	private final class GetChargesAndTimers implements Tool
	{
		@Override
		public String name()
		{
			return "get_charges_and_timers";
		}

		@Override
		public String description()
		{
			return "Item charges saved by RuneLite's Item Charges plugin (e.g. bracelets, necklaces, rings; only items the plugin has "
				+ "seen) and live timers: stamina, antifire, super antifire, overload, imbued heart, magic imbue, dragonfire shield, "
				+ "teleblock, divine potions (raw), home and minigame teleport cooldowns. Live timers need the player logged in; "
				+ "charges are readable whenever a RuneScape profile is loaded. Timers are approximate (the game only exposes coarse units).";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			JsonObject out = new JsonObject();
			out.add("charges", charges());
			try
			{
				out.add("timers", reader.readLoggedIn(TrackerTools.this::timers));
			}
			catch (ToolException e)
			{
				out.add("timers", new JsonArray());
				out.addProperty("timersNote", e.getMessage());
			}
			return out;
		}
	}

	private JsonElement charges()
	{
		JsonObject result = new JsonObject();
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			result.addProperty("note", "No RuneScape profile loaded; no charge data available.");
			result.add("items", new JsonArray());
			return result;
		}
		List<String> keys = configManager.getRSProfileConfigurationKeys(CHARGE_GROUP, profile, "");
		List<String> sorted = keys == null ? new ArrayList<>() : new ArrayList<>(keys);
		Collections.sort(sorted);
		JsonArray items = new JsonArray();
		for (String key : sorted)
		{
			String value = configManager.getRSProfileConfiguration(CHARGE_GROUP, key);
			if (value == null)
			{
				continue;
			}
			int charges;
			try
			{
				charges = Integer.parseInt(value.trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("key", key);
			o.addProperty("name", friendly(key));
			o.addProperty("charges", charges);
			items.add(o);
		}
		result.add("items", items);
		result.addProperty("note", "Last charge counts seen by the Item Charges plugin; may be outdated if the item was used while it was disabled.");
		return result;
	}

	/** camelCase -> "Camel case" words. */
	static String friendly(String key)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < key.length(); i++)
		{
			char c = key.charAt(i);
			if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(key.charAt(i - 1)))
			{
				sb.append(' ');
			}
			sb.append(i == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
		}
		return sb.toString().replace('_', ' ');
	}

	private JsonArray timers()
	{
		JsonArray out = new JsonArray();
		if (client.getVarbitValue(VarbitID.STAMINA_ACTIVE) == 1)
		{
			// RuneLite TimersAndBuffs: STAMINA_DURATION * 10 game ticks.
			ticks(out, "Stamina", client.getVarbitValue(VarbitID.STAMINA_DURATION) * 10, "exact");
		}
		approx(out, "Antifire", client.getVarbitValue(VarbitID.ANTIFIRE_POTION), 30);
		approx(out, "Super antifire", client.getVarbitValue(VarbitID.SUPER_ANTIFIRE_POTION), 20);
		approx(out, "Overload (potion)", client.getVarbitValue(VarbitID.NZONE_OVERLOAD_POTION_EFFECTS), 25);
		approx(out, "Overload (raids)", client.getVarbitValue(VarbitID.RAIDS_OVERLOAD_TIMER), 25);
		ticks(out, "Imbued heart cooldown", client.getVarbitValue(VarbitID.IMBUED_HEART_TIMER) * 10, "exact");
		ticks(out, "Magic imbue", client.getVarbitValue(VarbitID.MAGIC_IMBUE_ACTIVE) * 10, "exact");
		ticks(out, "Dragonfire shield cooldown", client.getVarbitValue(VarbitID.DRAGONFIRE_SHIELD_RECHARGE) * 8, "exact");
		int tb = client.getVarbitValue(VarbitID.TELEBLOCK_CYCLES);
		if (tb > 100)
		{
			// RuneLite uses (value - 100) game ticks; values <= 100 are not an active block.
			ticks(out, "Teleblock", tb - 100, "exact");
		}

		raw(out, "Divine attack potion", client.getVarbitValue(VarbitID.DIVINEATTACK_POTION_TIME));
		raw(out, "Divine strength potion", client.getVarbitValue(VarbitID.DIVINESTRENGTH_POTION_TIME));
		raw(out, "Divine defence potion", client.getVarbitValue(VarbitID.DIVINEDEFENCE_POTION_TIME));
		raw(out, "Divine ranging potion", client.getVarbitValue(VarbitID.DIVINERANGE_POTION_TIME));
		raw(out, "Divine magic potion", client.getVarbitValue(VarbitID.DIVINEMAGIC_POTION_TIME));
		raw(out, "Divine super combat potion", client.getVarbitValue(VarbitID.DIVINECOMBAT_POTION_TIME));
		raw(out, "Divine bastion potion", client.getVarbitValue(VarbitID.DIVINEBASTION_POTION_TIME));
		raw(out, "Divine battlemage potion", client.getVarbitValue(VarbitID.DIVINEBATTLEMAGE_POTION_TIME));

		// These varps hold the last teleport time in minutes since the epoch (per RuneLite's TimersAndBuffs).
		teleportCooldown(out, "Home teleport cooldown", client.getVarpValue(VarPlayerID.AIDE_TELE_TIMER), 30);
		teleportCooldown(out, "Minigame teleport cooldown", client.getVarpValue(VarPlayerID.SLUG2_REGIONUID), 20);

		return out;
	}

	private static void ticks(JsonArray out, String name, int ticks, String precision)
	{
		if (ticks <= 0)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("secondsRemaining", Math.round(ticks * TICK_SECONDS));
		o.addProperty("ticksRemaining", ticks);
		o.addProperty("precision", precision);
		out.add(o);
	}

	/** Value counts whole doses of 'unit' ticks; remaining time is between (value-1)*unit and value*unit ticks. */
	private static void approx(JsonArray out, String name, int value, int unit)
	{
		if (value <= 0)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("secondsRemainingMax", Math.round(value * unit * TICK_SECONDS));
		o.addProperty("secondsRemainingMin", Math.round((value - 1) * unit * TICK_SECONDS));
		o.addProperty("rawValue", value);
		o.addProperty("precision", "within " + Math.round(unit * TICK_SECONDS) + "s (varbit counts " + unit + "-tick steps)");
		out.add(o);
	}

	private static void raw(JsonArray out, String name, int value)
	{
		if (value <= 0)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("rawValue", value);
		o.addProperty("precision", "raw varbit value, unit unverified");
		out.add(o);
	}

	private static void teleportCooldown(JsonArray out, String name, int lastMinutes, int cooldownMinutes)
	{
		if (lastMinutes <= 0)
		{
			return;
		}
		long nowMinutes = System.currentTimeMillis() / 60000L;
		long remainingMinutes = lastMinutes + cooldownMinutes - nowMinutes;
		if (remainingMinutes <= 0)
		{
			return;
		}
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("secondsRemaining", remainingMinutes * 60);
		o.addProperty("precision", "minute resolution; assumes your computer clock matches the game's");
		out.add(o);
	}
}
