package com.osrsmcp.game;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Session-only ring buffer of NPC / event loot. Fed from the client thread by
 * NpcLootReceived and the Loot Tracker's LootReceived events. Never records player (PvP) loot.
 */
@Singleton
public class LootLog
{
	private static final int CAPACITY = 200;
	private static final long DEDUPE_MS = 2000;

	/** One recorded drop. Items are canonicalized {id, qty} pairs sorted by id. */
	public static final class Drop
	{
		public final String source;
		public final int combatLevel;
		public final long time;
		public final int[][] items;

		Drop(String source, int combatLevel, long time, int[][] items)
		{
			this.source = source;
			this.combatLevel = combatLevel;
			this.time = time;
			this.items = items;
		}
	}

	private final ItemManager itemManager;
	private final Deque<Drop> drops = new ArrayDeque<>(CAPACITY);

	@Inject
	public LootLog(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/** Must be called on the client thread (canonicalizes item ids). */
	public void add(String source, int combatLevel, Collection<ItemStack> stacks)
	{
		if (source == null || stacks == null)
		{
			return;
		}
		Map<Integer, Integer> merged = new TreeMap<>();
		for (ItemStack s : stacks)
		{
			if (s.getId() <= 0 || s.getQuantity() <= 0)
			{
				continue;
			}
			merged.merge(itemManager.canonicalize(s.getId()), s.getQuantity(), Integer::sum);
		}
		int[][] items = new int[merged.size()][];
		int i = 0;
		for (Map.Entry<Integer, Integer> e : merged.entrySet())
		{
			items[i++] = new int[]{e.getKey(), e.getValue()};
		}
		long now = System.currentTimeMillis();
		synchronized (drops)
		{
			// Both NpcLootReceived and LootReceived fire for one kill: keep a single entry.
			java.util.Iterator<Drop> it = drops.descendingIterator();
			while (it.hasNext())
			{
				Drop d = it.next();
				if (now - d.time > DEDUPE_MS)
				{
					break;
				}
				if (d.source.equalsIgnoreCase(source) && sameItems(d.items, items))
				{
					return;
				}
			}
			if (drops.size() >= CAPACITY)
			{
				drops.removeFirst();
			}
			drops.addLast(new Drop(source, combatLevel, now, items));
		}
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

	/** Oldest first. */
	public List<Drop> all()
	{
		synchronized (drops)
		{
			return new ArrayList<>(drops);
		}
	}

	public void clear()
	{
		synchronized (drops)
		{
			drops.clear();
		}
	}
}
