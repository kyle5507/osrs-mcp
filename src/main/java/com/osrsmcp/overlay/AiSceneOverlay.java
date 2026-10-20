package com.osrsmcp.overlay;

import com.osrsmcp.OsrsMcpConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/** Draws AI-requested tile and NPC highlights. Visual only. */
public class AiSceneOverlay extends Overlay
{
	private static final BasicStroke STROKE = new BasicStroke(2);

	private final Client client;
	private final AiOverlayState state;
	private final OsrsMcpConfig config;

	@Inject
	AiSceneOverlay(Client client, AiOverlayState state, OsrsMcpConfig config)
	{
		this.client = client;
		this.state = state;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.allowOverlays())
		{
			return null;
		}
		List<AiOverlayState.TileHighlight> tiles = state.tiles();
		List<AiOverlayState.NpcHighlight> npcs = state.npcs();
		if (tiles.isEmpty() && npcs.isEmpty())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		for (AiOverlayState.TileHighlight t : tiles)
		{
			if (t.point.getPlane() != wv.getPlane())
			{
				continue;
			}
			LocalPoint lp = LocalPoint.fromWorld(wv, t.point);
			if (lp == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}
			OverlayUtil.renderPolygon(g, poly, t.color);
			g.setColor(new Color(t.color.getRed(), t.color.getGreen(), t.color.getBlue(), 50));
			g.fill(poly);
			if (t.label != null)
			{
				Point p = Perspective.getCanvasTextLocation(client, g, lp, t.label, 0);
				if (p != null)
				{
					OverlayUtil.renderTextLocation(g, p, t.label, t.color);
				}
			}
		}
		if (!npcs.isEmpty())
		{
			for (NPC npc : client.getNpcs())
			{
				AiOverlayState.NpcHighlight h = match(npcs, npc);
				if (h == null)
				{
					continue;
				}
				Shape hull = npc.getConvexHull();
				if (hull != null)
				{
					OverlayUtil.renderPolygon(g, hull, h.color, STROKE);
				}
				if (h.label != null)
				{
					Point p = npc.getCanvasTextLocation(g, h.label, npc.getLogicalHeight() + 40);
					if (p != null)
					{
						OverlayUtil.renderTextLocation(g, p, h.label, h.color);
					}
				}
			}
		}
		return null;
	}

	static boolean matches(AiOverlayState.NpcHighlight h, NPC npc)
	{
		if (h.name != null)
		{
			String n = npc.getName();
			return n != null && n.equalsIgnoreCase(h.name);
		}
		return npc.getId() == h.id;
	}

	private static AiOverlayState.NpcHighlight match(List<AiOverlayState.NpcHighlight> list, NPC npc)
	{
		for (AiOverlayState.NpcHighlight h : list)
		{
			if (matches(h, npc))
			{
				return h;
			}
		}
		return null;
	}
}
