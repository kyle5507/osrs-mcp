package com.osrsmcp.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Reads the farming and bird house data saved by RuneLite's built-in Time Tracking plugin
 * (config group "timetracking", per RS profile) through ConfigManager.
 *
 * <p>Formats (verified against the 1.13.1 client bytecode):
 * <ul>
 * <li>Patch: key "regionId.varbitId", value "varbitValue:unixSeconds".</li>
 * <li>Patch compost: key "regionId.varbitId.compost", value COMPOST / SUPERCOMPOST / ULTRACOMPOST.</li>
 * <li>Patch protected: key "regionId.varbitId.protected".</li>
 * <li>Bird house: key "varpId" (1626-1629), value "varpValue:unixSeconds".</li>
 * <li>Misc: autoweed (ON/OFF/UNOWNED), farmTickOffset, farmTickOffsetPrecision, contract.</li>
 * </ul>
 * Crop growth state is not decoded: the decoder (PatchImplementation.forVarbitValue) is package-private
 * in RuneLite and reflection is not allowed, so raw varbit values are returned with a heuristic hint.
 */
@Slf4j
@Singleton
public class FarmingTimers
{
	private static final String GROUP = "timetracking";
	private static final Pattern PATCH_KEY = Pattern.compile("\\d+\\.\\d+");
	private static final Pattern SUB_KEY = Pattern.compile("(\\d+\\.\\d+)\\.(compost|protected)");
	private static final Pattern BIRD_KEY = Pattern.compile("\\d{4}");
	private static final long BIRD_HOUSE_SECONDS = 50 * 60;

	/** Bird house item order, indexed by (varpValue - 1) / 3. From BirdHouse enum in 1.13.1. */
	private static final String[] BIRD_HOUSE_TYPES = {
		"Bird House", "Oak Bird House", "Willow Bird House", "Teak Bird House", "Maple Bird House",
		"Mahogany Bird House", "Yew Bird House", "Magic Bird House", "Redwood Bird House"
	};

	private final ConfigManager configManager;

	@Inject
	public FarmingTimers(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** Returns null when no RS profile is loaded. */
	public JsonObject read(String filter)
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return null;
		}
		String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
		long now = Instant.now().getEpochSecond();

		List<String> keys = configManager.getRSProfileConfigurationKeys(GROUP, profile, "");
		if (keys == null)
		{
			keys = Collections.emptyList();
		}

		JsonArray patches = new JsonArray();
		JsonArray birdHouses = new JsonArray();
		JsonObject misc = new JsonObject();
		List<String> sorted = new ArrayList<>(keys);
		Collections.sort(sorted);
		for (String key : sorted)
		{
			String value = configManager.getRSProfileConfiguration(GROUP, key);
			if (value == null)
			{
				continue;
			}
			try
			{
				if (PATCH_KEY.matcher(key).matches())
				{
					JsonObject o = patch(key, value, now);
					if (o != null && matches(o, f))
					{
						patches.add(o);
					}
				}
				else if (BIRD_KEY.matcher(key).matches())
				{
					JsonObject o = birdHouse(key, value, now);
					if (o != null && ("bird house".contains(f) || "birdhouse".contains(f) || matches(o, f)))
					{
						birdHouses.add(o);
					}
				}
				else if (key.equals("autoweed") || key.equals("farmTickOffset") || key.equals("farmTickOffsetPrecision"))
				{
					misc.addProperty(key, value);
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Skipping unreadable time tracking key {}", key, e);
			}
		}

		// Attach compost / protection info to patch rows.
		for (String key : sorted)
		{
			java.util.regex.Matcher m = SUB_KEY.matcher(key);
			if (!m.matches())
			{
				continue;
			}
			String value = configManager.getRSProfileConfiguration(GROUP, key);
			for (int i = 0; i < patches.size(); i++)
			{
				JsonObject p = patches.get(i).getAsJsonObject();
				if (p.get("key").getAsString().equals(m.group(1)) && value != null)
				{
					p.addProperty(m.group(2).equals("compost") ? "compost" : "protected", value.replace("\"", ""));
				}
			}
		}

		JsonObject out = new JsonObject();
		out.addProperty("note", "Data only exists for patches and bird houses visited while RuneLite's Time Tracking plugin "
			+ "was enabled; the 'age' is time since that was last recorded, not since planting. Crop growth state is raw "
			+ "(rawValue is the patch varbit); no crop/growth decoding is available to this plugin.");
		out.add("patches", patches);
		out.addProperty("patchCount", patches.size());
		out.add("birdHouses", birdHouses);
		out.add("settings", misc);
		return out;
	}

	private static boolean matches(JsonObject o, String filter)
	{
		if (filter.isEmpty())
		{
			return true;
		}
		for (String field : new String[] {"patch", "region", "type"})
		{
			if (o.has(field) && o.get(field).getAsString().toLowerCase(Locale.ROOT).contains(filter))
			{
				return true;
			}
		}
		return o.get("key").getAsString().contains(filter);
	}

	private static JsonObject patch(String key, String value, long now)
	{
		String[] v = value.split(":");
		if (v.length != 2)
		{
			return null;
		}
		int raw = Integer.parseInt(v[0].trim());
		long ts = Long.parseLong(v[1].trim());
		JsonObject o = new JsonObject();
		o.addProperty("key", key);
		String[] ids = key.split("\\.");
		FarmingRegions.Patch p = FarmingRegions.lookup(key);
		if (p != null)
		{
			o.addProperty("patch", p.displayName());
			o.addProperty("region", p.region);
			o.addProperty("type", p.type.toLowerCase(Locale.ROOT).replace('_', ' '));
		}
		else
		{
			o.addProperty("patch", "Unknown patch (region " + ids[0] + ")");
		}
		o.addProperty("regionId", Integer.parseInt(ids[0]));
		o.addProperty("varbitId", Integer.parseInt(ids[1]));
		o.addProperty("rawValue", raw);
		if (p != null && !p.type.contains("COMPOST") && raw <= 3)
		{
			o.addProperty("hint", "Raw value 0-3 usually means weeds or an empty/cleared patch (heuristic).");
		}
		else
		{
			o.addProperty("hint", "Raw varbit value: higher values are usually later growth stages; exact meaning depends on the crop.");
		}
		o.addProperty("lastUpdated", Instant.ofEpochSecond(ts).toString());
		o.addProperty("ageSeconds", Math.max(0, now - ts));
		o.addProperty("age", duration(now - ts));
		return o;
	}

	private static JsonObject birdHouse(String key, String value, long now)
	{
		String[] v = value.split(":");
		if (v.length != 2)
		{
			return null;
		}
		int varp = Integer.parseInt(key);
		if (varp < 1626 || varp > 1629)
		{
			return null;
		}
		int raw = Integer.parseInt(v[0].trim());
		long ts = Long.parseLong(v[1].trim());
		JsonObject o = new JsonObject();
		o.addProperty("key", key);
		o.addProperty("spot", spot(varp));
		o.addProperty("varpId", varp);
		o.addProperty("rawValue", raw);
		String state;
		if (raw == 0)
		{
			state = "empty";
		}
		else
		{
			int idx = (raw - 1) / 3;
			if (idx >= 0 && idx < BIRD_HOUSE_TYPES.length)
			{
				o.addProperty("houseType", BIRD_HOUSE_TYPES[idx]);
			}
			state = raw % 3 == 0 ? "seeded" : "built (not seeded)";
		}
		o.addProperty("state", state);
		o.addProperty("recordedAt", Instant.ofEpochSecond(ts).toString());
		o.addProperty("ageSeconds", Math.max(0, now - ts));
		if (state.equals("seeded"))
		{
			long ready = ts + BIRD_HOUSE_SECONDS;
			o.addProperty("estimatedReadyAt", Instant.ofEpochSecond(ready).toString());
			if (ready <= now)
			{
				o.addProperty("ready", true);
				o.addProperty("readySince", duration(now - ready));
			}
			else
			{
				o.addProperty("ready", false);
				o.addProperty("secondsUntilReady", ready - now);
				o.addProperty("readyIn", duration(ready - now));
			}
		}
		return o;
	}

	private static String spot(int varp)
	{
		switch (varp)
		{
			case 1626:
				return "Mushroom Meadow (North)";
			case 1627:
				return "Mushroom Meadow (South)";
			case 1628:
				return "Verdant Valley (Northeast)";
			default:
				return "Verdant Valley (Southwest)";
		}
	}

	static String duration(long seconds)
	{
		seconds = Math.max(0, seconds);
		long d = seconds / 86400;
		long h = (seconds % 86400) / 3600;
		long m = (seconds % 3600) / 60;
		if (d > 0)
		{
			return d + "d " + h + "h";
		}
		if (h > 0)
		{
			return h + "h " + m + "m";
		}
		return m + "m";
	}
}
