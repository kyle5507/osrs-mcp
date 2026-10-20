package com.osrsmcp.overlay;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Shows the AI's current hint text. */
public class AiHintOverlay extends OverlayPanel
{
	private final AiOverlayState state;
	private final OsrsMcpConfig config;

	@Inject
	AiHintOverlay(AiOverlayState state, OsrsMcpConfig config)
	{
		this.state = state;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		AiOverlayState.Hint hint = state.hint();
		if (hint == null || !config.allowOverlays())
		{
			return null;
		}
		panelComponent.getChildren().add(TitleComponent.builder().text("AI hint").build());
		panelComponent.getChildren().add(LineComponent.builder().left(hint.text).build());
		return super.render(g);
	}
}
