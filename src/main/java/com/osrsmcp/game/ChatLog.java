package com.osrsmcp.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.util.Text;

/**
 * Ring buffer of recent system/game messages. Only system message types are ever stored;
 * public, private, clan and friends chat from other players is dropped on the floor.
 */
@Singleton
public class ChatLog
{
	private static final int CAPACITY = 200;

	/** The only types we keep. Deliberately excludes every player-authored chat type. */
	private static final Set<ChatMessageType> ALLOWED = EnumSet.of(
		ChatMessageType.GAMEMESSAGE,
		ChatMessageType.SPAM,
		ChatMessageType.ENGINE,
		ChatMessageType.CONSOLE,
		ChatMessageType.MESBOX,
		ChatMessageType.LEVELUPMESSAGE,
		ChatMessageType.WELCOME,
		ChatMessageType.ITEM_EXAMINE,
		ChatMessageType.NPC_EXAMINE,
		ChatMessageType.OBJECT_EXAMINE);

	private static final class Entry
	{
		final long time;
		final ChatMessageType type;
		final String message;

		Entry(long time, ChatMessageType type, String message)
		{
			this.time = time;
			this.type = type;
			this.message = message;
		}
	}

	private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);

	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();
		if (!ALLOWED.contains(type))
		{
			return;
		}
		String msg = event.getMessage();
		if (msg == null)
		{
			return;
		}
		msg = Text.removeTags(msg);
		synchronized (entries)
		{
			if (entries.size() >= CAPACITY)
			{
				entries.removeFirst();
			}
			entries.addLast(new Entry(System.currentTimeMillis(), type, msg));
		}
	}

	public void clear()
	{
		synchronized (entries)
		{
			entries.clear();
		}
	}

	/** Newest-last list of up to {@code limit} messages whose text contains {@code filter} (case-insensitive). */
	public JsonArray recent(int limit, String filter)
	{
		String f = filter == null || filter.isEmpty() ? null : filter.toLowerCase(Locale.ROOT);
		Entry[] snapshot;
		synchronized (entries)
		{
			snapshot = entries.toArray(new Entry[0]);
		}
		java.util.ArrayList<Entry> matched = new java.util.ArrayList<>();
		for (Entry e : snapshot)
		{
			if (f == null || e.message.toLowerCase(Locale.ROOT).contains(f))
			{
				matched.add(e);
			}
		}
		int from = Math.max(0, matched.size() - limit);
		JsonArray arr = new JsonArray();
		for (Entry e : matched.subList(from, matched.size()))
		{
			JsonObject o = new JsonObject();
			o.addProperty("time", Instant.ofEpochMilli(e.time).toString());
			o.addProperty("type", e.type.name());
			o.addProperty("message", e.message);
			arr.add(o);
		}
		return arr;
	}
}
