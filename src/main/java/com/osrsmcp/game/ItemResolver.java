package com.osrsmcp.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Turns item names or numeric ids into item ids. Must be called on the client thread.
 *
 * <p>Resolution order per input: numeric id; exact name among owned items (bank snapshot,
 * inventory, equipment); "name(" variants among owned items (so "Prayer potion" finds every
 * dose); exact name via {@link ItemManager#search} (tradeable items).
 */
@Singleton
public class ItemResolver
{
	private static final int MAX_SEARCH_RESULTS = 50;

	private final Client client;
	private final ItemManager itemManager;
	private final BankCache bankCache;

	@Inject
	public ItemResolver(Client client, ItemManager itemManager, BankCache bankCache)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.bankCache = bankCache;
	}

	public static class Result
	{
		/** Resolved ids, deduped, in input order. For filters this holds every owned variant. */
		public final List<Integer> ids = new ArrayList<>();
		/** For each input (same index as the input list) the single best id, or -1 if unresolved. */
		public final List<Integer> best = new ArrayList<>();
		public final List<String> unresolved = new ArrayList<>();
	}

	/**
	 * @param allVariants true to return every owned variant (filters); false to return only the
	 * best single variant per input in {@link Result#ids} (layout slots)
	 */
	public Result resolve(List<String> inputs, boolean allVariants)
	{
		Map<Integer, String> owned = ownedNames();
		Result result = new Result();
		Set<Integer> seen = new LinkedHashSet<>();
		for (String raw : inputs)
		{
			List<Integer> found = resolveOne(raw == null ? "" : raw.trim(), owned);
			if (found.isEmpty())
			{
				result.unresolved.add(raw);
				result.best.add(-1);
				continue;
			}
			int best = pickBest(found);
			result.best.add(best);
			if (allVariants)
			{
				for (int id : found)
				{
					if (seen.add(id))
					{
						result.ids.add(id);
					}
				}
			}
			else if (seen.add(best))
			{
				result.ids.add(best);
			}
		}
		return result;
	}

	/** All owned item ids (bank snapshot, inventory, equipment) mapped to lower-case names. */
	public Map<Integer, String> ownedNames()
	{
		Map<Integer, String> owned = new LinkedHashMap<>();
		int[][] snap = bankCache.items();
		if (snap != null)
		{
			for (int[] it : snap)
			{
				addOwned(owned, it[0]);
			}
		}
		addContainer(owned, client.getItemContainer(InventoryID.INV));
		addContainer(owned, client.getItemContainer(InventoryID.WORN));
		return owned;
	}

	private void addContainer(Map<Integer, String> owned, ItemContainer c)
	{
		if (c == null)
		{
			return;
		}
		for (Item it : c.getItems())
		{
			addOwned(owned, it.getId());
		}
	}

	private void addOwned(Map<Integer, String> owned, int id)
	{
		if (id <= 0)
		{
			return;
		}
		if (owned.containsKey(id))
		{
			return;
		}
		ItemComposition comp = itemManager.getItemComposition(id);
		if (comp.getPlaceholderTemplateId() != -1 || comp.getNote() != -1)
		{
			// placeholders and noted copies: the unnoted/real item is what we want
			return;
		}
		String name = comp.getName();
		if (name == null || name.isEmpty() || "null".equals(name))
		{
			return;
		}
		owned.put(id, name.toLowerCase(Locale.ROOT));
	}

	private List<Integer> resolveOne(String input, Map<Integer, String> owned)
	{
		List<Integer> out = new ArrayList<>();
		if (input.isEmpty())
		{
			return out;
		}
		if (input.matches("[0-9]{1,6}"))
		{
			int id = Integer.parseInt(input);
			if (id > 0)
			{
				out.add(id);
			}
			return out;
		}
		String lower = input.toLowerCase(Locale.ROOT);
		for (Map.Entry<Integer, String> e : owned.entrySet())
		{
			if (e.getValue().equals(lower))
			{
				out.add(e.getKey());
			}
		}
		if (!out.isEmpty())
		{
			return out;
		}
		String prefix = lower + "(";
		for (Map.Entry<Integer, String> e : owned.entrySet())
		{
			if (e.getValue().startsWith(prefix))
			{
				out.add(e.getKey());
			}
		}
		if (!out.isEmpty())
		{
			return out;
		}
		List<ItemPrice> results = itemManager.search(input);
		if (results != null)
		{
			int n = 0;
			for (ItemPrice p : results)
			{
				if (n++ >= MAX_SEARCH_RESULTS)
				{
					break;
				}
				if (p.getName() != null && p.getName().equalsIgnoreCase(input))
				{
					out.add(itemManager.canonicalize(p.getId()));
				}
			}
		}
		return out;
	}

	/** Highest trailing "(n)" dose/charge wins; otherwise the first (owned order). */
	private int pickBest(List<Integer> ids)
	{
		int best = ids.get(0);
		int bestNum = trailingNumber(best);
		for (int id : ids)
		{
			int n = trailingNumber(id);
			if (n > bestNum)
			{
				best = id;
				bestNum = n;
			}
		}
		return best;
	}

	private int trailingNumber(int id)
	{
		String name = itemManager.getItemComposition(id).getName();
		if (name == null || !name.endsWith(")"))
		{
			return -1;
		}
		int open = name.lastIndexOf('(');
		if (open < 0)
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(name.substring(open + 1, name.length() - 1));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}
