package com.osrsmcp.overlay;

import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/** Thread-safe store of AI-requested overlays. Written by HTTP threads, read each frame. */
@Singleton
public class AiOverlayState
{
	public static final class TileHighlight
	{
		public final WorldPoint point;
		public final String label;
		public final Color color;
		public final long expiresAt;

		TileHighlight(WorldPoint point, String label, Color color, long expiresAt)
		{
			this.point = point;
			this.label = label;
			this.color = color;
			this.expiresAt = expiresAt;
		}
	}

	public static final class NpcHighlight
	{
		/** Lower-cased name, or null when matching by id. */
		public final String name;
		public final int id;
		public final String label;
		public final Color color;
		public final long expiresAt;

		NpcHighlight(String name, int id, String label, Color color, long expiresAt)
		{
			this.name = name;
			this.id = id;
			this.label = label;
			this.color = color;
			this.expiresAt = expiresAt;
		}
	}

	public static final class Hint
	{
		public final String text;
		public final long expiresAt;

		Hint(String text, long expiresAt)
		{
			this.text = text;
			this.expiresAt = expiresAt;
		}
	}

	private static final int MAX_ENTRIES = 100;

	private final List<TileHighlight> tiles = new CopyOnWriteArrayList<>();
	private final List<NpcHighlight> npcs = new CopyOnWriteArrayList<>();
	private final AtomicReference<Hint> hint = new AtomicReference<>();
	private volatile boolean hintArrowSet;

	public void addTile(WorldPoint point, String label, Color color, long ttlMs)
	{
		purge();
		tiles.removeIf(t -> t.point.equals(point));
		if (tiles.size() >= MAX_ENTRIES)
		{
			tiles.remove(0);
		}
		tiles.add(new TileHighlight(point, label, color, System.currentTimeMillis() + ttlMs));
	}

	public void addNpc(String name, int id, String label, Color color, long ttlMs)
	{
		purge();
		String n = name == null ? null : name.toLowerCase();
		npcs.removeIf(h -> h.id == id && (h.name == null ? n == null : h.name.equals(n)));
		if (npcs.size() >= MAX_ENTRIES)
		{
			npcs.remove(0);
		}
		npcs.add(new NpcHighlight(n, id, label, color, System.currentTimeMillis() + ttlMs));
	}

	public void setHint(String text, long ttlMs)
	{
		hint.set(new Hint(text, System.currentTimeMillis() + ttlMs));
	}

	/** Live (unexpired) tiles; expired entries are dropped. */
	public List<TileHighlight> tiles()
	{
		purge();
		return tiles;
	}

	public List<NpcHighlight> npcs()
	{
		purge();
		return npcs;
	}

	public Hint hint()
	{
		Hint h = hint.get();
		if (h != null && h.expiresAt <= System.currentTimeMillis())
		{
			hint.compareAndSet(h, null);
			return null;
		}
		return h;
	}

	public boolean isHintArrowSet()
	{
		return hintArrowSet;
	}

	public void setHintArrowSet(boolean set)
	{
		hintArrowSet = set;
	}

	/** Clears tiles, NPCs and hint text (not the hint arrow flag; the caller owns that). */
	public void clear()
	{
		tiles.clear();
		npcs.clear();
		hint.set(null);
	}

	private void purge()
	{
		long now = System.currentTimeMillis();
		if (!tiles.isEmpty())
		{
			tiles.removeIf(t -> t.expiresAt <= now);
		}
		if (!npcs.isEmpty())
		{
			npcs.removeIf(n -> n.expiresAt <= now);
		}
	}
}
