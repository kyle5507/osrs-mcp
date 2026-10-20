package com.osrsmcp.bank;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/** Outlines AI-requested items in the bank and inventory. Visual only. */
class BankHighlightOverlay extends WidgetItemOverlay
{
	private static final BasicStroke STROKE = new BasicStroke(2);

	static class Entry
	{
		final Set<Integer> ids;
		final Color color;
		final long expiresAt;

		Entry(Set<Integer> ids, Color color, long expiresAt)
		{
			this.ids = ids;
			this.color = color;
			this.expiresAt = expiresAt;
		}
	}

	private final OsrsMcpConfig config;
	private final ItemManager itemManager;
	private final List<Entry> entries = new CopyOnWriteArrayList<>();

	BankHighlightOverlay(OsrsMcpConfig config, ItemManager itemManager)
	{
		this.config = config;
		this.itemManager = itemManager;
		showOnBank();
		showOnInventory();
	}

	void add(Entry entry)
	{
		entries.add(entry);
	}

	void clear()
	{
		entries.clear();
	}

	int size()
	{
		return entries.size();
	}

	@Override
	public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem widgetItem)
	{
		if (entries.isEmpty() || !config.allowOverlays())
		{
			return;
		}
		long now = System.currentTimeMillis();
		entries.removeIf(e -> e.expiresAt <= now);
		for (Entry e : entries)
		{
			if (e.ids.contains(itemId) || e.ids.contains(itemManager.canonicalize(itemId)))
			{
				Rectangle bounds = widgetItem.getCanvasBounds();
				g.setColor(e.color);
				g.setStroke(STROKE);
				g.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
				g.setColor(new Color(e.color.getRed(), e.color.getGreen(), e.color.getBlue(), 45));
				g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
				return;
			}
		}
	}
}
