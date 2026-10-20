package com.osrsmcp.game;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Best-effort import of the in-game Grand Exchange History tab into {@link GeHistoryLog}. The widget layout is
 * not documented, so the parser is tolerant and always logs the raw widget tree at debug level.
 */
@Slf4j
@Singleton
public class GeHistoryScraper
{
	private static final int MAX_ATTEMPTS = 12;
	private static final Pattern TYPE = Pattern.compile("(?i)^\\s*(bought|sold)\\b");
	private static final Pattern COINS = Pattern.compile("([\\d,]+)\\s*(?:coins|gp)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern EACH = Pattern.compile("([\\d,]+)\\s*(?:coins|gp)?\\s*each|each[:\\s]*([\\d,]+)", Pattern.CASE_INSENSITIVE);

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final GeHistoryLog historyLog;

	private int attempts;

	@Inject
	public GeHistoryScraper(Client client, ClientThread clientThread, ItemManager itemManager, GeHistoryLog historyLog)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.historyLog = historyLog;
	}

	/** Call from a WidgetLoaded subscriber (client thread). */
	public void onWidgetLoaded(int groupId)
	{
		if (groupId != InterfaceID.GE_HISTORY)
		{
			return;
		}
		attempts = 0;
		// the list is populated by a script after the interface loads: retry for a few ticks
		clientThread.invokeLater(this::scrapeOnce);
	}

	/** Returns true when finished (stop retrying). */
	private boolean scrapeOnce()
	{
		attempts++;
		try
		{
			Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
			if (list == null)
			{
				return attempts >= MAX_ATTEMPTS;
			}
			List<Widget> flat = new ArrayList<>();
			collect(list, flat, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), 0);
			boolean hasItems = false;
			for (Widget w : flat)
			{
				if (w.getItemId() > 0)
				{
					hasItems = true;
					break;
				}
			}
			boolean last = attempts >= MAX_ATTEMPTS;
			if (!hasItems && !last)
			{
				return false;
			}
			logTree(list, flat);
			List<GeHistoryLog.Trade> rows = parse(flat);
			int added = historyLog.importRows(rows);
			log.debug("GE history tab: {} widgets, parsed {} rows, imported {} new (attempt {})",
				flat.size(), rows.size(), added, attempts);
			return true;
		}
		catch (RuntimeException e)
		{
			log.debug("GE history scrape failed", e);
			return true;
		}
	}

	private static void collect(Widget w, List<Widget> out, Set<Widget> seen, int depth)
	{
		if (w == null || depth > 6 || !seen.add(w))
		{
			return;
		}
		if (depth > 0)
		{
			out.add(w);
		}
		collectAll(w.getStaticChildren(), out, seen, depth);
		collectAll(w.getDynamicChildren(), out, seen, depth);
		collectAll(w.getNestedChildren(), out, seen, depth);
	}

	private static void collectAll(Widget[] arr, List<Widget> out, Set<Widget> seen, int depth)
	{
		if (arr == null)
		{
			return;
		}
		for (Widget c : arr)
		{
			collect(c, out, seen, depth + 1);
		}
	}

	private static void logTree(Widget list, List<Widget> flat)
	{
		if (!log.isDebugEnabled())
		{
			return;
		}
		log.debug("GE history tab raw widget tree: list id={} type={} childCount={}", list.getId(), list.getType(), flat.size());
		for (int i = 0; i < flat.size(); i++)
		{
			Widget w = flat.get(i);
			log.debug("GE history widget[{}] id={} idx={} type={} hidden={} itemId={} qty={} text='{}' name='{}'",
				i, w.getId(), w.getIndex(), w.getType(), w.isHidden(), w.getItemId(), w.getItemQuantity(),
				w.getText() == null ? "" : Text.removeTags(w.getText()), w.getName() == null ? "" : Text.removeTags(w.getName()));
		}
	}

	private static final class RowBuilder
	{
		int itemId = -1;
		int quantity;
		Boolean buy;
		final List<String> texts = new ArrayList<>();
	}

	/** Group the flattened widgets into rows and extract trades. Rows that cannot be parsed are skipped. */
	private List<GeHistoryLog.Trade> parse(List<Widget> flat)
	{
		List<RowBuilder> rows = new ArrayList<>();
		RowBuilder cur = new RowBuilder();
		for (Widget w : flat)
		{
			if (w.isHidden())
			{
				continue;
			}
			if (w.getItemId() > 0)
			{
				if (cur.itemId > 0)
				{
					rows.add(cur);
					cur = new RowBuilder();
				}
				cur.itemId = w.getItemId();
				cur.quantity = Math.max(1, w.getItemQuantity());
				continue;
			}
			String text = w.getText();
			if (text == null || text.trim().isEmpty())
			{
				continue;
			}
			text = Text.removeTags(text).trim();
			Matcher tm = TYPE.matcher(text);
			if (tm.find())
			{
				if (cur.buy != null)
				{
					rows.add(cur);
					cur = new RowBuilder();
				}
				cur.buy = tm.group(1).toLowerCase(Locale.ROOT).equals("bought");
			}
			cur.texts.add(text);
		}
		rows.add(cur);

		List<GeHistoryLog.Trade> out = new ArrayList<>();
		for (RowBuilder r : rows)
		{
			GeHistoryLog.Trade t = toTrade(r);
			if (t != null)
			{
				out.add(t);
			}
		}
		return out;
	}

	private GeHistoryLog.Trade toTrade(RowBuilder r)
	{
		if (r.itemId <= 0 || r.buy == null)
		{
			return null;
		}
		long total = -1;
		long each = -1;
		for (String text : r.texts)
		{
			Matcher em = EACH.matcher(text);
			if (each < 0 && em.find())
			{
				each = parseNum(em.group(1) != null ? em.group(1) : em.group(2));
			}
			// strip "(= 12 each)"-style fragments so the per-item price is not mistaken for the total
			String rest = EACH.matcher(text).replaceAll("");
			Matcher cm = COINS.matcher(rest);
			if (total < 0 && cm.find())
			{
				total = parseNum(cm.group(1));
			}
		}
		if (total < 0 && each >= 0)
		{
			total = each * r.quantity;
		}
		if (total <= 0)
		{
			return null;
		}
		GeHistoryLog.Trade t = new GeHistoryLog.Trade();
		t.itemId = r.itemId;
		t.itemName = itemManager.getItemComposition(r.itemId).getName();
		t.buy = r.buy;
		t.quantity = r.quantity;
		t.totalCoins = total;
		t.offerPrice = each;
		t.cancelled = false;
		return t;
	}

	private static long parseNum(String s)
	{
		try
		{
			return Long.parseLong(s.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}
}
