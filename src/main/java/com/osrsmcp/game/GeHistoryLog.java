package com.osrsmcp.game;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * The player's own Grand Exchange trade history: trades recorded live from offer events plus rows imported from
 * the in-game GE History tab. Persisted per RS profile (newest first, capped). All methods are synchronized;
 * the event entry points must be called on the client thread.
 */
@Slf4j
@Singleton
public class GeHistoryLog
{
	public static final int MAX_TRADES = 1000;
	private static final String KEY_TRADES = "geHistory";
	private static final String KEY_SLOTS = "geSlots";
	/** Offer events are held back this many game ticks after login so the RS profile is loaded. */
	private static final int SETTLE_TICKS = 2;

	public static final String SOURCE_LIVE = "live";
	public static final String SOURCE_TAB = "history-tab";

	/** One completed (or partially filled then cancelled) trade. */
	public static final class Trade
	{
		public long time; // epoch ms, 0 = unknown (imported rows)
		public int itemId;
		public String itemName;
		public boolean buy;
		public int quantity;
		public long offerPrice; // -1 unknown
		public long totalCoins;
		public boolean cancelled;
		public String source;
		public boolean completedWhileOffline;
		public long importedAt;
		public int rowIndex = -1;
	}

	/** Immutable copy of an offer's state. */
	private static final class Snap
	{
		int itemId;
		GrandExchangeOfferState state;
		int sold;
		int total;
		long price;
		long spent;
		String name;

		String signature()
		{
			return itemId + ":" + state.name() + ":" + sold + ":" + spent + ":" + total;
		}
	}

	private final ConfigManager configManager;
	private final Gson gson;
	private final ItemManager itemManager;

	private final List<Trade> trades = new ArrayList<>();
	private final Map<Integer, String> slotSigs = new HashMap<>();
	private final Set<Integer> seenSlots = new HashSet<>();
	private final Map<Integer, Snap> pending = new HashMap<>();
	private boolean loaded;
	private boolean loggedIn;
	private int ticksSinceLogin = SETTLE_TICKS;

	@Inject
	public GeHistoryLog(ConfigManager configManager, Gson gson, ItemManager itemManager)
	{
		this.configManager = configManager;
		this.gson = gson;
		this.itemManager = itemManager;
	}

	/** Call from the plugin start with the current game state. */
	public synchronized void init(GameState state)
	{
		loggedIn = state == GameState.LOGGED_IN;
		ticksSinceLogin = SETTLE_TICKS;
	}

	public synchronized void clear()
	{
		invalidate();
		seenSlots.clear();
		pending.clear();
	}

	/** Forget in-memory data so it is reloaded for the (possibly different) RS profile. */
	public synchronized void invalidate()
	{
		trades.clear();
		slotSigs.clear();
		loaded = false;
	}

	public synchronized void onGameStateChanged(GameState state)
	{
		if (state == GameState.LOGGED_IN)
		{
			loggedIn = true;
			ticksSinceLogin = 0;
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.LOGGING_IN
			|| state == GameState.CONNECTION_LOST)
		{
			loggedIn = false;
			seenSlots.clear();
			if (state == GameState.LOGIN_SCREEN)
			{
				pending.clear();
			}
		}
	}

	public synchronized void onGameTick()
	{
		if (ticksSinceLogin < SETTLE_TICKS)
		{
			ticksSinceLogin++;
		}
		flushPending();
	}

	public synchronized void onProfileChanged()
	{
		invalidate();
		flushPending();
	}

	public synchronized void onOfferChanged(int slot, GrandExchangeOffer offer)
	{
		if (offer == null || slot < 0)
		{
			return;
		}
		Snap s = new Snap();
		s.state = offer.getState();
		s.itemId = offer.getItemId();
		s.sold = offer.getQuantitySold();
		s.total = offer.getTotalQuantity();
		s.price = offer.getPrice();
		s.spent = offer.getSpent();
		if (isCompletion(s))
		{
			s.name = itemName(s.itemId);
		}
		pending.put(slot, s);
		flushPending();
	}

	private static boolean isCompletion(Snap s)
	{
		switch (s.state)
		{
			case BOUGHT:
			case SOLD:
				return true;
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				return s.sold > 0;
			default:
				return false;
		}
	}

	private String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (RuntimeException e)
		{
			return "Item " + itemId;
		}
	}

	private void flushPending()
	{
		if (pending.isEmpty() || !loggedIn || ticksSinceLogin < SETTLE_TICKS || configManager.getRSProfileKey() == null)
		{
			return;
		}
		ensureLoaded();
		boolean changed = false;
		boolean slotsChanged = false;
		List<Integer> order = new ArrayList<>(pending.keySet());
		Collections.sort(order);
		for (int slot : order)
		{
			Snap s = pending.get(slot);
			boolean firstThisSession = seenSlots.add(slot);
			String stored = slotSigs.get(slot);
			if (s.state == GrandExchangeOfferState.EMPTY)
			{
				if (stored != null)
				{
					slotSigs.remove(slot);
					slotsChanged = true;
				}
				continue;
			}
			String sig = s.signature();
			if (sig.equals(stored))
			{
				continue;
			}
			slotSigs.put(slot, sig);
			slotsChanged = true;
			if (isCompletion(s))
			{
				record(s, firstThisSession);
				changed = true;
			}
		}
		pending.clear();
		if (changed)
		{
			persistTrades();
		}
		if (slotsChanged)
		{
			persistSlots();
		}
	}

	private void record(Snap s, boolean offline)
	{
		boolean buy = s.state == GrandExchangeOfferState.BOUGHT || s.state == GrandExchangeOfferState.CANCELLED_BUY;
		Trade t = new Trade();
		t.time = System.currentTimeMillis();
		t.itemId = s.itemId;
		t.itemName = s.name != null ? s.name : itemName(s.itemId);
		t.buy = buy;
		t.quantity = s.sold;
		t.offerPrice = s.price;
		t.totalCoins = s.spent;
		t.cancelled = s.state == GrandExchangeOfferState.CANCELLED_BUY || s.state == GrandExchangeOfferState.CANCELLED_SELL;
		t.source = SOURCE_LIVE;
		t.completedWhileOffline = offline;
		// a live record carries more information than a tab import of the same trade: replace it
		String k = key(t);
		for (Iterator<Trade> it = trades.iterator(); it.hasNext(); )
		{
			Trade o = it.next();
			if (SOURCE_TAB.equals(o.source) && key(o).equals(k))
			{
				it.remove();
				break;
			}
		}
		trades.add(0, t);
		trim();
	}

	/**
	 * Add rows scraped from the GE History tab (in on-screen order). Rows already known (live or previously
	 * imported) are skipped by count, so re-opening the tab never duplicates. Returns the number added.
	 */
	public synchronized int importRows(List<Trade> rows)
	{
		if (configManager.getRSProfileKey() == null || rows.isEmpty())
		{
			return 0;
		}
		ensureLoaded();
		Map<String, Integer> existing = new HashMap<>();
		for (Trade t : trades)
		{
			existing.merge(key(t), 1, Integer::sum);
		}
		Map<String, Integer> seenInRows = new HashMap<>();
		List<Trade> add = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (int i = 0; i < rows.size(); i++)
		{
			Trade r = rows.get(i);
			String k = key(r);
			int n = seenInRows.merge(k, 1, Integer::sum);
			// the n-th occurrence of this signature in the tab is new only if we hold fewer than n already
			if (existing.getOrDefault(k, 0) >= n)
			{
				continue;
			}
			r.source = SOURCE_TAB;
			r.importedAt = now;
			r.rowIndex = i;
			r.time = 0;
			add.add(r);
		}
		if (add.isEmpty())
		{
			return 0;
		}
		// newer than older imports, older than every live trade
		int pos = 0;
		while (pos < trades.size() && !SOURCE_TAB.equals(trades.get(pos).source))
		{
			pos++;
		}
		trades.addAll(pos, add);
		trim();
		persistTrades();
		return add.size();
	}

	private static String key(Trade t)
	{
		return t.itemId + ":" + (t.buy ? "b" : "s") + ":" + t.quantity + ":" + t.totalCoins;
	}

	private void trim()
	{
		while (trades.size() > MAX_TRADES)
		{
			trades.remove(trades.size() - 1);
		}
	}

	/** True when an RS profile is loaded so the persisted log can be read. */
	public synchronized boolean hasProfile()
	{
		return configManager.getRSProfileKey() != null;
	}

	/** Newest-first copy of all trades. */
	public synchronized List<Trade> snapshot()
	{
		ensureLoaded();
		return new ArrayList<>(trades);
	}

	private void persistTrades()
	{
		try
		{
			JsonArray arr = new JsonArray();
			for (Trade t : trades)
			{
				JsonObject o = new JsonObject();
				o.addProperty("t", t.time);
				o.addProperty("id", t.itemId);
				o.addProperty("n", t.itemName);
				o.addProperty("b", t.buy);
				o.addProperty("q", t.quantity);
				o.addProperty("p", t.offerPrice);
				o.addProperty("c", t.totalCoins);
				o.addProperty("x", t.cancelled);
				o.addProperty("s", t.source);
				o.addProperty("o", t.completedWhileOffline);
				o.addProperty("ia", t.importedAt);
				o.addProperty("r", t.rowIndex);
				arr.add(o);
			}
			configManager.setRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY_TRADES, gson.toJson(arr));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not persist GE history", e);
		}
	}

	private void persistSlots()
	{
		try
		{
			JsonObject o = new JsonObject();
			for (Map.Entry<Integer, String> e : slotSigs.entrySet())
			{
				o.addProperty(String.valueOf(e.getKey()), e.getValue());
			}
			configManager.setRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY_SLOTS, gson.toJson(o));
		}
		catch (RuntimeException e)
		{
			log.debug("Could not persist GE slot state", e);
		}
	}

	private void ensureLoaded()
	{
		if (loaded || configManager.getRSProfileKey() == null)
		{
			return;
		}
		loaded = true;
		trades.clear();
		slotSigs.clear();
		try
		{
			String json = configManager.getRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY_TRADES);
			if (json != null && !json.isEmpty())
			{
				for (JsonElement e : gson.fromJson(json, JsonElement.class).getAsJsonArray())
				{
					JsonObject o = e.getAsJsonObject();
					Trade t = new Trade();
					t.time = o.get("t").getAsLong();
					t.itemId = o.get("id").getAsInt();
					t.itemName = o.get("n").getAsString();
					t.buy = o.get("b").getAsBoolean();
					t.quantity = o.get("q").getAsInt();
					t.offerPrice = o.get("p").getAsLong();
					t.totalCoins = o.get("c").getAsLong();
					t.cancelled = o.get("x").getAsBoolean();
					t.source = o.get("s").getAsString();
					t.completedWhileOffline = o.get("o").getAsBoolean();
					t.importedAt = o.get("ia").getAsLong();
					t.rowIndex = o.get("r").getAsInt();
					trades.add(t);
				}
			}
			String slots = configManager.getRSProfileConfiguration(OsrsMcpConfig.GROUP, KEY_SLOTS);
			if (slots != null && !slots.isEmpty())
			{
				for (Map.Entry<String, JsonElement> e : gson.fromJson(slots, JsonElement.class).getAsJsonObject().entrySet())
				{
					slotSigs.put(Integer.parseInt(e.getKey()), e.getValue().getAsString());
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("Could not load GE history", e);
			trades.clear();
			slotSigs.clear();
		}
	}
}
