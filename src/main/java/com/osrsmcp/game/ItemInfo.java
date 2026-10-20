package com.osrsmcp.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/**
 * Turns item ids/quantities into JSON with names and prices.
 * Must be called on the client thread (inside {@link ClientReader#read}).
 */
@Singleton
public class ItemInfo
{
	private final ItemManager itemManager;

	@Inject
	public ItemInfo(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/** Returns null for empty slots and bank placeholders. */
	public JsonObject describe(int itemId, int quantity)
	{
		if (itemId <= 0 || quantity <= 0)
		{
			return null;
		}
		ItemComposition comp = itemManager.getItemComposition(itemId);
		if (comp.getPlaceholderTemplateId() != -1)
		{
			return null;
		}
		int canonical = itemManager.canonicalize(itemId);
		ItemComposition base = canonical == itemId ? comp : itemManager.getItemComposition(canonical);
		long ge = itemManager.getItemPrice(canonical);
		int ha = base.getHaPrice();

		JsonObject o = new JsonObject();
		o.addProperty("id", itemId);
		o.addProperty("name", base.getName());
		if (comp.getNote() != -1)
		{
			o.addProperty("noted", true);
		}
		o.addProperty("quantity", quantity);
		o.addProperty("gePrice", ge);
		o.addProperty("haPrice", ha);
		o.addProperty("geTotal", ge * quantity);
		return o;
	}

	/** Describe a list of items; returns {items:[...], count, totalGe, totalHa}. */
	public JsonObject describeAll(Item[] items)
	{
		int[][] pairs = new int[items == null ? 0 : items.length][];
		for (int i = 0; i < pairs.length; i++)
		{
			pairs[i] = new int[]{items[i].getId(), items[i].getQuantity()};
		}
		return describeAll(pairs);
	}

	/** Same as {@link #describeAll(Item[])} for {id, quantity} pairs. */
	public JsonObject describeAll(int[][] items)
	{
		JsonArray arr = new JsonArray();
		long totalGe = 0;
		long totalHa = 0;
		for (int[] it : items)
		{
			JsonObject o = describe(it[0], it[1]);
			if (o == null)
			{
				continue;
			}
			arr.add(o);
			totalGe += o.get("geTotal").getAsLong();
			totalHa += o.get("haPrice").getAsLong() * it[1];
		}
		JsonObject r = new JsonObject();
		r.add("items", arr);
		r.addProperty("count", arr.size());
		r.addProperty("totalGe", totalGe);
		r.addProperty("totalHa", totalHa);
		return r;
	}
}
