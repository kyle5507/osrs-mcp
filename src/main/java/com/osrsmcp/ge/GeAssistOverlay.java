package com.osrsmcp.ge;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Lists pending staged offers while the GE is open, with OK/X checks on the offer setup screen. */
class GeAssistOverlay extends OverlayPanel
{
	private static final Color OK = new Color(0x40C040);
	private static final Color BAD = new Color(0xE04040);
	private static final Color HINT = new Color(255, 200, 0);

	private final GeAssist assist;
	private final OsrsMcpConfig config;
	private final StagedOffers staged;

	GeAssistOverlay(GeAssist assist, OsrsMcpConfig config, StagedOffers staged)
	{
		this.assist = assist;
		this.config = config;
		this.staged = staged;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		GeAssist.View v = assist.view();
		List<StagedOffers.Offer> pending = staged.pending();
		if (!config.geAssist() || !v.geOpen || pending.isEmpty())
		{
			return null;
		}
		panelComponent.getChildren().add(TitleComponent.builder().text("Staged GE offers").build());
		for (StagedOffers.Offer o : pending)
		{
			boolean current = v.match != null && v.match.id == o.id;
			panelComponent.getChildren().add(LineComponent.builder()
				.left((o.buy ? "BUY " : "SELL ") + String.format("%,d", o.quantity) + " × " + o.itemName
					+ " @ " + String.format("%,d", o.price))
				.leftColor(current ? Color.WHITE : Color.LIGHT_GRAY)
				.build());
		}
		if (v.setupOpen && v.item > 0)
		{
			StagedOffers.Offer m = v.match;
			if (m == null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("This item/type is not staged").leftColor(BAD).build());
			}
			else
			{
				check("Item", true);
				check("Type", true);
				boolean qtyOk = v.quantity == m.quantity;
				boolean priceOk = v.price == m.price;
				check("Quantity (" + String.format("%,d", m.quantity) + ")", qtyOk);
				check("Price (" + String.format("%,d", m.price) + ")", priceOk);
				if (!qtyOk)
				{
					hint("Click the highlighted ... by Quantity,");
					hint("then the yellow 'Set quantity' line, Enter");
				}
				else if (!priceOk)
				{
					hint("Click the highlighted ... by Price,");
					hint("then the yellow 'Set price' line, Enter");
				}
				else
				{
					hint("All set - click Confirm");
				}
			}
		}
		return super.render(g);
	}

	private void hint(String text)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(HINT).build());
	}

	private void check(String label, boolean ok)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(label).right(ok ? "OK" : "X").rightColor(ok ? OK : BAD).build());
	}
}
