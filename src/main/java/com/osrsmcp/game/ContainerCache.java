package com.osrsmcp.game;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;

/**
 * Snapshots of the player's own non-bank storage containers (looting bag, seed box, tackle box,
 * POH costume room, ...). Taken whenever the container changes, persisted per RS profile,
 * one config key per container.
 */
@Slf4j
@Singleton
public class ContainerCache
{
	private static final String KEY_PREFIX = "containerSnapshot_";

	/** Container id to display name, in display order. */
	private static final Map<Integer, String> TRACKED;

	static
	{
		Map<Integer, String> m = new LinkedHashMap<>();
		m.put(InventoryID.LOOTING_BAG, "Looting bag");
		m.put(InventoryID.SEED_BOX, "Seed box");
		m.put(InventoryID.SEED_VAULT, "Seed vault");
		m.put(InventoryID.TACKLE_BOX, "Tackle box");
		m.put(InventoryID.FORESTRY_KIT, "Forestry kit");
		m.put(InventoryID.HUNTSMANS_KIT, "Huntsman's kit");
		m.put(InventoryID.BONDS_POUCH, "Bonds pouch");
		m.put(InventoryID.DIZANAS_QUIVER_AMMO, "Dizana's quiver ammo");
		m.put(InventoryID.BARBARIAN_KNAPSACK, "Barbarian knapsack");
		m.put(InventoryID.POH_COSTUMES, "POH costume room (general)");
		m.put(InventoryID.POH_COSTUME_ROOM_MAGIC_WARDROBE_INV, "POH magic wardrobe");
		m.put(InventoryID.POH_COSTUME_ROOM_ARMOUR_INV, "POH armour case");
		m.put(InventoryID.POH_COSTUME_ROOM_AME_INV, "POH costume room: AME case");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_0_INV, "POH treasure trail wardrobe (set 0)");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_1_INV, "POH treasure trail wardrobe (set 1)");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_1A_INV, "POH treasure trail wardrobe (set 1a)");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_2_INV, "POH treasure trail wardrobe (set 2)");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_2A_INV, "POH treasure trail wardrobe (set 2a)");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_3_INV, "POH treasure trail wardrobe (set 3)");
		m.put(InventoryID.POH_COSTUME_ROOM_TREASURE_TRAIL_3A_INV, "POH treasure trail wardrobe (set 3a)");
		m.put(InventoryID.POH_COSTUME_ROOM_CAPES_INV, "POH cape rack");
		m.put(InventoryID.POH_COSTUME_ROOM_CAPES_INV_PAGE2, "POH cape rack (page 2)");
		m.put(InventoryID.POH_COSTUME_ROOM_HOLIDAY_ITEMS_INV, "POH toy box (holiday items)");
		TRACKED = Collections.unmodifiableMap(m);
	}

	private final ConfigManager configManager;
	private final Gson gson;

	private final Map<Integer, Snapshot> snapshots = new HashMap<>();
	private boolean loaded;

	public static class Snapshot
	{
		public final int[][] items;
		public final long capturedAt;

		Snapshot(int[][] items, long capturedAt)
		{
			this.items = items;
			this.capturedAt = capturedAt;
		}
	}

	@Inject
	public ContainerCache(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public static Map<Integer, String> tracked()
	{
		return TRACKED;
	}

	public static boolean isTracked(int containerId)
	{
		return TRACKED.containsKey(containerId);
	}

	/** Called on the client thread when a container changes or is found loaded. Ignores untracked ids. */
	public synchronized void capture(int containerId, ItemContainer container)
	{
		if (container == null || !TRACKED.containsKey(containerId))
		{
			return;
		}
		ensureLoaded();
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
		Snapshot snap = new Snapshot(result, System.currentTimeMillis());
		Snapshot old = snapshots.get(containerId);
		snapshots.put(containerId, snap);
		if (old == null || !sameItems(old.items, result))
		{
			persist(containerId, snap);
		}
	}

	/** Forget in-memory state (profile switched); it is reloaded lazily. */
	public synchronized void invalidate()
	{
		snapshots.clear();
		loaded = false;
	}

	/** Returns the snapshot or null if this container was never seen on this profile. */
	public synchronized Snapshot get(int containerId)
	{
		ensureLoaded();
		return snapshots.get(containerId);
	}

	private static boolean sameItems(int[][] a, int[][] b)
	{
		if (a.length != b.length)
		{
			return false;
		}
		for (int i = 0; i < a.length; i++)
		{
			if (a[i][0] != b[i][0] || a[i][1] != b[i][1])
			{
				return false;
			}
		}
		return true;
	}

	private void persist(int containerId, Snapshot snap)
	{
		try
		{
			JsonArray arr = new JsonArray();
			for (int[] it : snap.items)
			{
				JsonArray p = new JsonArray();
				p.add(it[0]);
				p.add(it[1]);
				arr.add(p);
			}
			JsonObject o = new JsonObject();
			o.addProperty("t", snap.capturedAt);
			o.add("items", arr);
			configManager.setRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY_PREFIX + containerId, gson.toJson(o));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not persist container snapshot {}", containerId, e);
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
		for (int id : TRACKED.keySet())
		{
			if (snapshots.containsKey(id))
			{
				continue;
			}
			try
			{
				String json = configManager.getRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY_PREFIX + id);
				if (json == null || json.isEmpty())
				{
					continue;
				}
				JsonObject o = gson.fromJson(json, JsonElement.class).getAsJsonObject();
				JsonArray arr = o.getAsJsonArray("items");
				int[][] result = new int[arr.size()][];
				for (int i = 0; i < result.length; i++)
				{
					JsonArray p = arr.get(i).getAsJsonArray();
					result[i] = new int[]{p.get(0).getAsInt(), p.get(1).getAsInt()};
				}
				snapshots.put(id, new Snapshot(result, o.get("t").getAsLong()));
			}
			catch (RuntimeException e)
			{
				log.debug("Could not load container snapshot {}", id, e);
			}
		}
	}
}
