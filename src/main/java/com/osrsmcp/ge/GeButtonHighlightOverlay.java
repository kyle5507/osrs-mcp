package com.osrsmcp.ge;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Outlines the offer-setup "..." buttons for quantity/price while they don't match the
 * staged offer, so the player knows which box to open (then click "Set price"/"Set quantity").
 */
class GeButtonHighlightOverlay extends Overlay
{
	// Child indexes of GeOffers.SETUP, as used by the Flipping Copilot hub plugin (GrandExchange.java)
	private static final int SETUP_QUANTITY_BUTTON_CHILD = 51;
	private static final int SETUP_PRICE_BUTTON_CHILD = 54;

	private static final Color OUTLINE = new Color(255, 200, 0);
	private static final Color FILL = new Color(255, 200, 0, 40);
	private static final BasicStroke STROKE = new BasicStroke(2);

	private final Client client;
	private final GeAssist assist;
	private final OsrsMcpConfig config;

	GeButtonHighlightOverlay(Client client, GeAssist assist, OsrsMcpConfig config)
	{
		this.client = client;
		this.assist = assist;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		GeAssist.View v = assist.view();
		if (!config.geAssist() || !v.setupOpen || v.match == null)
		{
			return null;
		}
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden())
		{
			return null;
		}
		if (v.quantity != v.match.quantity)
		{
			outline(g, setup.getChild(SETUP_QUANTITY_BUTTON_CHILD));
		}
		if (v.price != v.match.price)
		{
			outline(g, setup.getChild(SETUP_PRICE_BUTTON_CHILD));
		}
		return null;
	}

	private static void outline(Graphics2D g, Widget button)
	{
		if (button == null || button.isHidden())
		{
			return;
		}
		Rectangle b = button.getBounds();
		g.setColor(FILL);
		g.fill(b);
		g.setColor(OUTLINE);
		g.setStroke(STROKE);
		g.draw(b);
	}
}
