package com.osrsmcp.ge;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.Set;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/** Outlines inventory items that have a pending staged sell offer while the GE is open. */
class GeSellHighlightOverlay extends WidgetItemOverlay
{
	private static final BasicStroke STROKE = new BasicStroke(2);
	private static final Color COLOR = new Color(0xE0A020);

	private final GeAssist assist;
	private final OsrsMcpConfig config;
	private final StagedOffers staged;
	private final ItemManager itemManager;

	GeSellHighlightOverlay(GeAssist assist, OsrsMcpConfig config, StagedOffers staged, ItemManager itemManager)
	{
		this.assist = assist;
		this.config = config;
		this.staged = staged;
		this.itemManager = itemManager;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem widgetItem)
	{
		if (!config.geAssist() || !assist.view().geOpen)
		{
			return;
		}
		Set<Integer> sells = staged.pendingSellItemIds();
		if (sells.isEmpty() || !sells.contains(itemManager.canonicalize(itemId)))
		{
			return;
		}
		Rectangle b = widgetItem.getCanvasBounds();
		g.setColor(COLOR);
		g.setStroke(STROKE);
		g.drawRect(b.x, b.y, b.width, b.height);
		g.setColor(new Color(COLOR.getRed(), COLOR.getGreen(), COLOR.getBlue(), 45));
		g.fillRect(b.x, b.y, b.width, b.height);
	}
}
