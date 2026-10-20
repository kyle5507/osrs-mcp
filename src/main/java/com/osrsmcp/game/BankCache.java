package com.osrsmcp.game;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.config.ConfigManager;

/** Snapshot of the bank taken whenever the bank container changes, persisted per RS profile. */
@Slf4j
@Singleton
public class BankCache
{
	private static final String KEY = "bankSnapshot";

	private final ConfigManager configManager;
	private final Gson gson;

	private int[][] items;
	private long capturedAt;
	private boolean loaded;

	@Inject
	public BankCache(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Called on the client thread when the bank container changes. */
	public synchronized void capture(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		Item[] all = container.getItems();
		int n = 0;
		int[][] tmp = new int[all.length][];
		for (Item it : all)
		{
			if (it.getId() > 0 && it.getQuantity() > 0)
			{
				tmp[n++] = new int[]{it.getId(), it.getQuantity()};
			}
		}
		int[][] result = new int[n][];
		System.arraycopy(tmp, 0, result, 0, n);
		items = result;
		capturedAt = System.currentTimeMillis();
		loaded = true;
		persist();
	}

	/** Forget in-memory state (profile switched); it is reloaded lazily. */
	public synchronized void invalidate()
	{
		items = null;
		capturedAt = 0;
		loaded = false;
	}

	/** Returns {id, qty} pairs or null if no snapshot exists. */
	public synchronized int[][] items()
	{
		ensureLoaded();
		return items;
	}

	public synchronized long capturedAt()
	{
		ensureLoaded();
		return capturedAt;
	}

	private void persist()
	{
		try
		{
			JsonArray arr = new JsonArray();
			for (int[] it : items)
			{
				JsonArray p = new JsonArray();
				p.add(it[0]);
				p.add(it[1]);
				arr.add(p);
			}
			JsonObject o = new JsonObject();
			o.addProperty("t", capturedAt);
			o.add("items", arr);
			configManager.setRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY, gson.toJson(o));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not persist bank snapshot", e);
		}
	}

	private void ensureLoaded()
	{
		if (loaded)
		{
			return;
		}
		if (configManager.getRSProfileKey() == null)
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
			JsonObject o = gson.fromJson(json, JsonElement.class).getAsJsonObject();
			JsonArray arr = o.getAsJsonArray("items");
			int[][] result = new int[arr.size()][];
			for (int i = 0; i < result.length; i++)
			{
				JsonArray p = arr.get(i).getAsJsonArray();
				result[i] = new int[]{p.get(0).getAsInt(), p.get(1).getAsInt()};
			}
			items = result;
			capturedAt = o.get("t").getAsLong();
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load bank snapshot", e);
			items = null;
		}
	}
}
