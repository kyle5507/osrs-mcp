package com.osrsmcp.ge;

import com.google.gson.Gson;
import com.osrsmcp.OsrsMcpConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * GE offers staged by the AI, persisted per RS profile. Thread-safe. The player still places every
 * offer themselves; this is only the list the plugin helps them fill in.
 */
@Slf4j
@Singleton
public class StagedOffers
{
	static final String KEY = "stagedOffers";
	static final int MAX = 16;
	private static final long PLACED_KEEP_MS = 60L * 60 * 1000;

	public static final String PENDING = "pending";
	public static final String PLACED = "placed";

	/** Plain data holder (Gson serialisable). Instances handed out are copies. */
	public static class Offer
	{
		public int id;
		public int itemId;
		public String itemName;
		public boolean buy;
		public int quantity;
		public long price;
		public long createdAt;
		public String status = PENDING;
		public long placedAt;

		Offer copy()
		{
			Offer o = new Offer();
			o.id = id;
			o.itemId = itemId;
			o.itemName = itemName;
			o.buy = buy;
			o.quantity = quantity;
			o.price = price;
			o.createdAt = createdAt;
			o.status = status;
			o.placedAt = placedAt;
			return o;
		}

		public String type()
		{
			return buy ? "buy" : "sell";
		}

		public boolean isPending()
		{
			return PENDING.equals(status);
		}
	}

	private final ConfigManager configManager;
	private final Gson gson;

	private final List<Offer> offers = new ArrayList<>();
	private boolean loaded;
	private int nextId = 1;

	private volatile List<Offer> pendingSnapshot = Collections.emptyList();
	private volatile Set<Integer> pendingSellIds = Collections.emptySet();

	@Inject
	public StagedOffers(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Forget in-memory state (profile switched); reloaded lazily. */
	public synchronized void invalidate()
	{
		offers.clear();
		loaded = false;
		publish();
	}

	/** Pending offers (immutable copies); cheap enough to call every frame. */
	public List<Offer> pending()
	{
		return pendingSnapshot;
	}

	/** Item ids of pending sell offers. */
	public Set<Integer> pendingSellItemIds()
	{
		return pendingSellIds;
	}

	/** All offers: pending first, then placed ones from the last hour. */
	public synchronized List<Offer> all()
	{
		ensureLoaded();
		prune();
		List<Offer> out = new ArrayList<>();
		for (Offer o : offers)
		{
			out.add(o.copy());
		}
		return out;
	}

	/** @return the new offer, or null if the list is full. */
	public synchronized Offer add(int itemId, String itemName, boolean buy, int quantity, long price)
	{
		ensureLoaded();
		prune();
		if (offers.size() >= MAX)
		{
			return null;
		}
		Offer o = new Offer();
		o.id = nextId++;
		o.itemId = itemId;
		o.itemName = itemName;
		o.buy = buy;
		o.quantity = quantity;
		o.price = price;
		o.createdAt = System.currentTimeMillis();
		offers.add(o);
		persist();
		return o.copy();
	}

	public synchronized boolean remove(int id)
	{
		ensureLoaded();
		boolean removed = offers.removeIf(o -> o.id == id);
		if (removed)
		{
			persist();
		}
		return removed;
	}

	public synchronized int clear()
	{
		ensureLoaded();
		int n = offers.size();
		offers.clear();
		persist();
		return n;
	}

	public synchronized boolean markPlaced(int id)
	{
		ensureLoaded();
		for (Offer o : offers)
		{
			if (o.id == id && o.isPending())
			{
				o.status = PLACED;
				o.placedAt = System.currentTimeMillis();
				persist();
				return true;
			}
		}
		return false;
	}

	/** Make sure the pending snapshot is loaded (call from the client thread on login/startup). */
	public synchronized void refresh()
	{
		ensureLoaded();
		prune();
		publish();
	}

	private void prune()
	{
		long now = System.currentTimeMillis();
		boolean changed = false;
		Iterator<Offer> it = offers.iterator();
		while (it.hasNext())
		{
			Offer o = it.next();
			if (!o.isPending() && now - o.placedAt > PLACED_KEEP_MS)
			{
				it.remove();
				changed = true;
			}
		}
		if (changed)
		{
			persist();
		}
	}

	private void publish()
	{
		List<Offer> pend = new ArrayList<>();
		Set<Integer> sells = new HashSet<>();
		for (Offer o : offers)
		{
			if (o.isPending())
			{
				pend.add(o.copy());
				if (!o.buy)
				{
					sells.add(o.itemId);
				}
			}
		}
		pendingSnapshot = Collections.unmodifiableList(pend);
		pendingSellIds = Collections.unmodifiableSet(sells);
	}

	private void persist()
	{
		publish();
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		try
		{
			configManager.setRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY, gson.toJson(offers));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not persist staged offers", e);
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
				publish();
				return;
			}
			Offer[] arr = gson.fromJson(json, Offer[].class);
			offers.clear();
			int max = 0;
			if (arr != null)
			{
				for (Offer o : arr)
				{
					if (o != null && o.itemId > 0)
					{
						offers.add(o);
						max = Math.max(max, o.id);
					}
				}
			}
			nextId = max + 1;
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load staged offers", e);
			offers.clear();
		}
		publish();
	}
}
