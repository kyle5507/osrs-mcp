package com.osrsmcp.game;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * Kill counts and personal bests parsed from game messages, persisted per RS profile, and merged
 * with whatever RuneLite's Chat Commands plugin saved ("killcount" / "personalbest" config groups).
 */
@Slf4j
@Singleton
public class KillCountLog
{
	private static final String KEY = "killcounts";
	private static final String CC_KC_GROUP = "killcount";
	private static final String CC_PB_GROUP = "personalbest";
	private static final long ASSOCIATE_WINDOW_MS = 10_000;

	private static final String TIME = "(?:\\d+:)?\\d+:\\d+(?:\\.\\d+)?";
	private static final Pattern KC = Pattern.compile("^Your (.+?) (?:kill |harvest |lap )?count is: ([\\d,]+)\\.?$");
	private static final Pattern PB_NEW = Pattern.compile("(?i)duration:\\s*(" + TIME + ")\\s*\\(new personal best\\)");
	private static final Pattern PB_OLD = Pattern.compile("(?i)duration:\\s*(" + TIME + ")\\.?\\s*personal best:\\s*(" + TIME + ")");

	private static final class Entry
	{
		String name;
		int kc = -1;
		double pb = -1;
		long updated;
	}

	private final ConfigManager configManager;
	private final Gson gson;

	/** Keyed by lowercase boss name. */
	private final Map<String, Entry> entries = new TreeMap<>();
	private boolean loaded;

	private String lastBoss;
	private long lastBossTime;
	private double pendingPb = -1;
	private long pendingPbTime;

	@Inject
	public KillCountLog(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public synchronized void invalidate()
	{
		entries.clear();
		loaded = false;
		lastBoss = null;
		pendingPb = -1;
	}

	public synchronized void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = event.getMessage();
		if (msg == null)
		{
			return;
		}
		msg = Text.removeTags(msg).trim();
		long now = System.currentTimeMillis();

		Matcher m = KC.matcher(msg);
		if (m.matches())
		{
			String name = m.group(1).trim();
			if (name.startsWith("completed "))
			{
				name = name.substring("completed ".length());
			}
			int kc;
			try
			{
				kc = Integer.parseInt(m.group(2).replace(",", ""));
			}
			catch (NumberFormatException e)
			{
				return;
			}
			ensureLoaded();
			Entry e = entry(name);
			e.kc = kc;
			e.updated = now;
			lastBoss = name.toLowerCase(Locale.ROOT);
			lastBossTime = now;
			if (pendingPb >= 0 && now - pendingPbTime <= ASSOCIATE_WINDOW_MS)
			{
				e.pb = pendingPb;
			}
			pendingPb = -1;
			persist();
			return;
		}

		double pb = -1;
		m = PB_NEW.matcher(msg);
		if (m.find())
		{
			pb = parseTime(m.group(1));
		}
		else
		{
			m = PB_OLD.matcher(msg);
			if (m.find())
			{
				pb = parseTime(m.group(2));
			}
		}
		if (pb >= 0)
		{
			if (lastBoss != null && now - lastBossTime <= ASSOCIATE_WINDOW_MS)
			{
				ensureLoaded();
				Entry e = entries.get(lastBoss);
				if (e != null)
				{
					e.pb = pb;
					e.updated = now;
					persist();
				}
			}
			else
			{
				pendingPb = pb;
				pendingPbTime = now;
			}
		}
	}

	/** Parse "1:23.40" / "1:02:03" into seconds, or -1. */
	static double parseTime(String s)
	{
		try
		{
			String[] parts = s.split(":");
			double total = 0;
			for (String p : parts)
			{
				total = total * 60 + Double.parseDouble(p);
			}
			return total;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	public static String formatTime(double seconds)
	{
		long whole = (long) seconds;
		int hundredths = (int) Math.round((seconds - whole) * 100);
		if (hundredths == 100)
		{
			whole++;
			hundredths = 0;
		}
		long h = whole / 3600;
		long mi = (whole % 3600) / 60;
		long s = whole % 60;
		if (h > 0)
		{
			return String.format("%d:%02d:%02d.%02d", h, mi, s, hundredths);
		}
		return String.format("%d:%02d.%02d", mi, s, hundredths);
	}

	private Entry entry(String name)
	{
		return entries.computeIfAbsent(name.toLowerCase(Locale.ROOT), k ->
		{
			Entry e = new Entry();
			e.name = name;
			return e;
		});
	}

	/**
	 * Returns kill count records sorted by name. Each has boss, kc (if known), pbSeconds/pb (if known),
	 * lastUpdated (ISO, only for plugin-recorded data) and source ("osrs-mcp", "chat-commands" or "both").
	 */
	public synchronized List<JsonObject> snapshot(String filter)
	{
		ensureLoaded();
		Map<String, JsonObject> out = new TreeMap<>();
		for (Map.Entry<String, Entry> me : entries.entrySet())
		{
			Entry e = me.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("boss", e.name);
			if (e.kc >= 0)
			{
				o.addProperty("kc", e.kc);
			}
			if (e.pb >= 0)
			{
				o.addProperty("pbSeconds", Math.round(e.pb * 100) / 100.0);
				o.addProperty("pb", formatTime(e.pb));
			}
			if (e.updated > 0)
			{
				o.addProperty("lastUpdated", Instant.ofEpochMilli(e.updated).toString());
			}
			o.addProperty("source", "osrs-mcp");
			out.put(me.getKey(), o);
		}

		String profile = configManager.getRSProfileKey();
		if (profile != null)
		{
			try
			{
				mergeChatCommands(out, profile);
			}
			catch (RuntimeException ex)
			{
				log.debug("Could not read Chat Commands data", ex);
			}
		}

		String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
		List<JsonObject> list = new ArrayList<>();
		for (Map.Entry<String, JsonObject> me : out.entrySet())
		{
			if (f.isEmpty() || me.getKey().contains(f))
			{
				list.add(me.getValue());
			}
		}
		list.sort(Comparator.comparing(o -> o.get("boss").getAsString().toLowerCase(Locale.ROOT)));
		return list;
	}

	private void mergeChatCommands(Map<String, JsonObject> out, String profile)
	{
		List<String> kcKeys = configManager.getRSProfileConfigurationKeys(CC_KC_GROUP, profile, "");
		List<String> pbKeys = configManager.getRSProfileConfigurationKeys(CC_PB_GROUP, profile, "");
		if (kcKeys != null)
		{
			for (String key : kcKeys)
			{
				Integer kc = parseInt(configManager.getRSProfileConfiguration(CC_KC_GROUP, key));
				if (kc == null)
				{
					continue;
				}
				JsonObject o = out.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> fresh(key));
				if (!o.has("kc") || kc > o.get("kc").getAsInt())
				{
					o.addProperty("kc", kc);
				}
				markCc(o);
			}
		}
		if (pbKeys != null)
		{
			for (String key : pbKeys)
			{
				String v = configManager.getRSProfileConfiguration(CC_PB_GROUP, key);
				double pb;
				try
				{
					pb = v == null ? -1 : Double.parseDouble(v);
				}
				catch (NumberFormatException e)
				{
					continue;
				}
				if (pb <= 0)
				{
					continue;
				}
				JsonObject o = out.computeIfAbsent(key.toLowerCase(Locale.ROOT), k -> fresh(key));
				if (!o.has("pbSeconds"))
				{
					o.addProperty("pbSeconds", Math.round(pb * 100) / 100.0);
					o.addProperty("pb", formatTime(pb));
				}
				markCc(o);
			}
		}
	}

	private static JsonObject fresh(String key)
	{
		JsonObject o = new JsonObject();
		StringBuilder sb = new StringBuilder();
		boolean up = true;
		for (char c : key.toCharArray())
		{
			sb.append(up ? Character.toUpperCase(c) : c);
			up = c == ' ' || c == '-' || c == ':';
		}
		o.addProperty("boss", sb.toString());
		o.addProperty("source", "chat-commands");
		return o;
	}

	private static void markCc(JsonObject o)
	{
		if ("osrs-mcp".equals(o.get("source").getAsString()))
		{
			o.addProperty("source", "both");
		}
	}

	private static Integer parseInt(String v)
	{
		if (v == null)
		{
			return null;
		}
		try
		{
			return Integer.parseInt(v.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private void persist()
	{
		try
		{
			JsonObject root = new JsonObject();
			for (Entry e : entries.values())
			{
				JsonObject o = new JsonObject();
				o.addProperty("name", e.name);
				o.addProperty("kc", e.kc);
				o.addProperty("pb", e.pb);
				o.addProperty("t", e.updated);
				root.add(e.name.toLowerCase(Locale.ROOT), o);
			}
			configManager.setRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY, gson.toJson(root));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not persist kill counts", e);
		}
	}

	private void ensureLoaded()
	{
		if (loaded || configManager.getRSProfileKey() == null)
		{
			return;
		}
		loaded = true;
		try
		{
			String json = configManager.getRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY);
			if (json == null || json.isEmpty())
			{
				return;
			}
			JsonObject root = gson.fromJson(json, JsonElement.class).getAsJsonObject();
			for (Map.Entry<String, JsonElement> me : root.entrySet())
			{
				JsonObject o = me.getValue().getAsJsonObject();
				Entry e = new Entry();
				e.name = o.get("name").getAsString();
				e.kc = o.get("kc").getAsInt();
				e.pb = o.get("pb").getAsDouble();
				e.updated = o.get("t").getAsLong();
				entries.put(me.getKey(), e);
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load kill counts", e);
			entries.clear();
		}
	}
}
