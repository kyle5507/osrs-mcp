package com.osrsmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.OsrsMcpConfig;
import com.osrsmcp.game.ChatLog;
import com.osrsmcp.game.ClientReader;
import com.osrsmcp.game.DiaryData;
import com.osrsmcp.game.ItemInfo;
import com.osrsmcp.game.SlayerData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/** Quests, diaries, slayer, nearby world and recent game messages. */
@Singleton
public class ProgressTools implements ToolGroup
{
	private static final int MAX_RADIUS = 30;
	private static final int MAX_OBJECTS = 50;
	private static final int MAX_NPCS = 100;
	private static final int MAX_GROUND_ITEMS = 100;

	private final Client client;
	private final ClientReader reader;
	private final ItemInfo itemInfo;
	private final OsrsMcpConfig config;
	private final ChatLog chatLog;

	@Inject
	public ProgressTools(Client client, ClientReader reader, ItemInfo itemInfo, OsrsMcpConfig config)
	{
		this.client = client;
		this.reader = reader;
		this.itemInfo = itemInfo;
		this.config = config;
		this.chatLog = new ChatLog();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		chatLog.onChatMessage(event);
	}

	@Override
	public void shutDown()
	{
		chatLog.clear();
	}

	@Override
	public List<Tool> tools()
	{
		return Arrays.asList(new GetQuests(), new GetDiaries(), new GetSlayerTask(), new GetNearby(), new GetRecentMessages());
	}

	private final class GetQuests implements Tool
	{
		@Override
		public String name()
		{
			return "get_quests";
		}

		@Override
		public String description()
		{
			return "Lists every quest with its state (completed, in_progress, not_started), the player's quest points and counts. "
				+ "Use the optional filter to narrow the list.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object().prop("filter", "string", "all (default), completed, in_progress or not_started").build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			String filter = Args.str(args, "filter", "all").toLowerCase(Locale.ROOT);
			if (!Arrays.asList("all", "completed", "in_progress", "not_started").contains(filter))
			{
				throw new ToolException("filter must be one of: all, completed, in_progress, not_started");
			}
			return reader.readLoggedIn(() ->
			{
				JsonArray list = new JsonArray();
				int done = 0;
				int started = 0;
				int notStarted = 0;
				for (Quest q : Quest.values())
				{
					QuestState s = q.getState(client);
					String state;
					switch (s)
					{
						case FINISHED:
							state = "completed";
							done++;
							break;
						case IN_PROGRESS:
							state = "in_progress";
							started++;
							break;
						default:
							state = "not_started";
							notStarted++;
							break;
					}
					if (filter.equals("all") || filter.equals(state))
					{
						JsonObject o = new JsonObject();
						o.addProperty("name", q.getName());
						o.addProperty("state", state);
						list.add(o);
					}
				}
				JsonObject r = new JsonObject();
				r.addProperty("questPoints", client.getVarpValue(VarPlayerID.QP));
				r.addProperty("completed", done);
				r.addProperty("inProgress", started);
				r.addProperty("notStarted", notStarted);
				r.addProperty("total", done + started + notStarted);
				r.addProperty("filter", filter);
				r.add("quests", list);
				return r;
			});
		}
	}

	private final class GetDiaries implements Tool
	{
		@Override
		public String name()
		{
			return "get_achievement_diaries";
		}

		@Override
		public String description()
		{
			return "Completion of every achievement diary tier (easy/medium/hard/elite) for all 12 regions.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			return reader.readLoggedIn(() ->
			{
				JsonArray regions = new JsonArray();
				int total = 0;
				for (int i = 0; i < DiaryData.REGIONS.length; i++)
				{
					JsonObject o = new JsonObject();
					o.addProperty("region", DiaryData.REGIONS[i]);
					int count = 0;
					for (int t = 0; t < DiaryData.TIERS.length; t++)
					{
						boolean complete = client.getVarbitValue(DiaryData.VARBITS[i][t]) == 1;
						o.addProperty(DiaryData.TIERS[t], complete);
						if (complete)
						{
							count++;
						}
					}
					o.addProperty("tiersComplete", count);
					total += count;
					regions.add(o);
				}
				JsonObject r = new JsonObject();
				r.addProperty("tiersComplete", total);
				r.addProperty("tiersTotal", DiaryData.REGIONS.length * DiaryData.TIERS.length);
				r.add("regions", regions);
				return r;
			});
		}
	}

	private final class GetSlayerTask implements Tool
	{
		@Override
		public String name()
		{
			return "get_slayer_task";
		}

		@Override
		public String description()
		{
			return "Current slayer task (creature and kills remaining), slayer points and consecutive task streak. "
				+ "Reports hasTask=false when there is no task.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.none();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			return reader.readLoggedIn(() ->
			{
				JsonObject r = new JsonObject();
				String name = SlayerData.taskName(client);
				r.addProperty("hasTask", name != null);
				if (name != null)
				{
					r.addProperty("creature", name);
					r.addProperty("remaining", client.getVarpValue(VarPlayerID.SLAYER_COUNT));
				}
				else
				{
					r.addProperty("message", "No slayer task");
				}
				r.addProperty("slayerPoints", client.getVarbitValue(VarbitID.SLAYER_POINTS));
				r.addProperty("taskStreak", client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED));
				return r;
			});
		}
	}

	private final class GetNearby implements Tool
	{
		@Override
		public String name()
		{
			return "get_nearby";
		}

		@Override
		public String description()
		{
			return "What is around the player within a radius (tiles): NPCs, ground items, interactable objects "
				+ "(deduped by name), and a count of other players (never their names). Sorted by distance.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object().prop("radius", "integer", "Radius in tiles, default 15, max 30").build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			int radius = Math.max(1, Math.min(MAX_RADIUS, Args.integer(args, "radius", 15)));
			return reader.readLoggedIn(() -> nearby(radius));
		}
	}

	private JsonObject nearby(int radius)
	{
		Player me = client.getLocalPlayer();
		WorldPoint myPos = me.getWorldLocation();
		WorldView wv = client.getTopLevelWorldView();

		List<JsonObject> npcs = new ArrayList<>();
		for (NPC npc : wv.npcs())
		{
			if (npc == null || npc.getName() == null)
			{
				continue;
			}
			WorldPoint wp = npc.getWorldLocation();
			if (wp == null || wp.getPlane() != myPos.getPlane())
			{
				continue;
			}
			int dist = wp.distanceTo2D(myPos);
			if (dist > radius)
			{
				continue;
			}
			NPCComposition comp = npc.getTransformedComposition();
			JsonObject o = new JsonObject();
			o.addProperty("name", comp != null && comp.getName() != null ? comp.getName() : npc.getName());
			o.addProperty("id", comp != null ? comp.getId() : npc.getId());
			o.addProperty("combatLevel", npc.getCombatLevel());
			o.addProperty("distance", dist);
			o.add("worldPoint", point(wp));
			Actor target = npc.getInteracting();
			o.addProperty("interactingWithYou", target == me);
			npcs.add(o);
		}
		sortByDistance(npcs);
		JsonArray npcArr = cap(npcs, MAX_NPCS);

		// Other players: count only, never names or gear.
		int otherPlayers = 0;
		for (Player p : wv.players())
		{
			if (p != null && p != me && p.getWorldLocation() != null && p.getWorldLocation().distanceTo2D(myPos) <= radius)
			{
				otherPlayers++;
			}
		}

		List<JsonObject> items = new ArrayList<>();
		Map<String, JsonObject> objects = new LinkedHashMap<>();
		Set<Long> seen = new HashSet<>();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		LocalPoint lp = me.getLocalLocation();
		int sx = lp.getSceneX();
		int sy = lp.getSceneY();
		int plane = wv.getPlane();
		if (tiles != null && plane >= 0 && plane < tiles.length)
		{
			Tile[][] level = tiles[plane];
			for (int x = Math.max(0, sx - radius); x <= Math.min(level.length - 1, sx + radius); x++)
			{
				for (int y = Math.max(0, sy - radius); y <= Math.min(level[x].length - 1, sy + radius); y++)
				{
					Tile tile = level[x][y];
					if (tile == null)
					{
						continue;
					}
					WorldPoint twp = tile.getWorldLocation();
					int dist = twp.distanceTo2D(myPos);
					if (dist > radius)
					{
						continue;
					}
					List<TileItem> ground = tile.getGroundItems();
					if (ground != null)
					{
						for (TileItem ti : ground)
						{
							JsonObject d = itemInfo.describe(ti.getId(), ti.getQuantity());
							if (d == null)
							{
								continue;
							}
							d.addProperty("distance", dist);
							d.add("worldPoint", point(twp));
							items.add(d);
						}
					}
					GameObject[] gos = tile.getGameObjects();
					if (gos != null)
					{
						for (GameObject go : gos)
						{
							addObject(objects, seen, go, dist);
						}
					}
					addObject(objects, seen, tile.getGroundObject(), dist);
					addObject(objects, seen, tile.getWallObject(), dist);
					addObject(objects, seen, tile.getDecorativeObject(), dist);
				}
			}
		}
		sortByDistance(items);
		JsonArray itemArr = cap(items, MAX_GROUND_ITEMS);

		List<JsonObject> objList = new ArrayList<>(objects.values());
		sortByDistance(objList);
		JsonArray objArr = cap(objList, MAX_OBJECTS);

		JsonObject r = new JsonObject();
		r.addProperty("radius", radius);
		r.add("playerPosition", point(myPos));
		r.addProperty("otherPlayersNearby", otherPlayers);
		r.add("npcs", npcArr);
		r.add("groundItems", itemArr);
		r.add("objects", objArr);
		return r;
	}

	private void addObject(Map<String, JsonObject> objects, Set<Long> seen, TileObject obj, int dist)
	{
		if (obj == null || !seen.add(obj.getHash()))
		{
			return;
		}
		ObjectComposition comp = client.getObjectDefinition(obj.getId());
		if (comp == null)
		{
			return;
		}
		if (comp.getImpostorIds() != null)
		{
			ObjectComposition imp = comp.getImpostor();
			if (imp != null)
			{
				comp = imp;
			}
		}
		String name = comp.getName();
		if (name == null || name.isEmpty() || name.equals("null"))
		{
			return;
		}
		JsonArray acts = new JsonArray();
		String[] actions = comp.getActions();
		if (actions != null)
		{
			for (String a : actions)
			{
				if (a != null && !a.isEmpty())
				{
					acts.add(a);
				}
			}
		}
		if (acts.size() == 0)
		{
			return;
		}
		JsonObject o = objects.get(name);
		if (o == null)
		{
			o = new JsonObject();
			o.addProperty("name", name);
			o.addProperty("id", comp.getId());
			o.addProperty("count", 1);
			o.addProperty("distance", dist);
			o.add("worldPoint", point(obj.getWorldLocation()));
			o.add("actions", acts);
			objects.put(name, o);
		}
		else
		{
			o.addProperty("count", o.get("count").getAsInt() + 1);
			if (dist < o.get("distance").getAsInt())
			{
				o.addProperty("distance", dist);
				o.add("worldPoint", point(obj.getWorldLocation()));
			}
		}
	}

	private static void sortByDistance(List<JsonObject> list)
	{
		Collections.sort(list, Comparator.comparingInt(o -> o.get("distance").getAsInt()));
	}

	private static JsonArray cap(List<JsonObject> list, int max)
	{
		JsonArray arr = new JsonArray();
		for (int i = 0; i < list.size() && i < max; i++)
		{
			arr.add(list.get(i));
		}
		return arr;
	}

	private static JsonObject point(WorldPoint wp)
	{
		JsonObject o = new JsonObject();
		o.addProperty("x", wp.getX());
		o.addProperty("y", wp.getY());
		o.addProperty("plane", wp.getPlane());
		return o;
	}

	private final class GetRecentMessages implements Tool
	{
		@Override
		public String name()
		{
			return "get_recent_messages";
		}

		@Override
		public String description()
		{
			return "Recent game/system messages (drops, level-ups, kill counts, errors) since the plugin started, oldest first. "
				+ "Player chat is never recorded.";
		}

		@Override
		public JsonObject inputSchema()
		{
			return Schema.object()
				.prop("limit", "integer", "Max messages to return, default 50 (buffer holds 200)")
				.prop("filter", "string", "Only messages containing this text (case-insensitive)")
				.build();
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			if (!config.exposeChat())
			{
				throw new ToolException("Game message access is disabled in the OSRS MCP plugin settings.");
			}
			int limit = Math.max(1, Math.min(200, Args.integer(args, "limit", 50)));
			JsonArray msgs = chatLog.recent(limit, Args.str(args, "filter", null));
			JsonObject r = new JsonObject();
			r.addProperty("count", msgs.size());
			r.add("messages", msgs);
			return r;
		}
	}
}
