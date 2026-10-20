package com.osrsmcp.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.osrsmcp.tools.Tool;
import com.osrsmcp.tools.ToolException;
import com.osrsmcp.tools.ToolRegistry;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal MCP JSON-RPC 2.0 handler (tools + prompts). Transport-agnostic: takes a
 * parsed request and returns the response object, or null for notifications.
 */
@Slf4j
public class McpDispatcher
{
	public static final List<String> SUPPORTED_VERSIONS = Arrays.asList(
		"2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
	static final String DEFAULT_VERSION = "2025-06-18";
	public static final String SERVER_NAME = "osrs-mcp";
	public static final String SERVER_VERSION = "0.1.0";

	static final int PARSE_ERROR = -32700;
	static final int INVALID_REQUEST = -32600;
	static final int METHOD_NOT_FOUND = -32601;
	static final int INVALID_PARAMS = -32602;
	static final int INTERNAL_ERROR = -32603;

	private static final String INSTRUCTIONS =
		"This server exposes the live state of the user's Old School RuneScape account from their RuneLite client. "
			+ "Always call the relevant read tools (get_player, get_skills, get_equipment, get_inventory, get_bank, get_quests, ...) "
			+ "before giving account-specific advice instead of guessing. Bank data is a snapshot from the last time the bank was opened; "
			+ "mention its age when it matters. Overlay tools only draw visual hints in the user's client; they never perform actions. "
			+ "Bank tools (filter_bank, show_bank_layout) only change which items the in-game bank displays, and apply once the player has the bank open. "
			+ "GE tools (stage_ge_offer, stage_ge_offers) only stage offers: the player places and confirms every offer themselves.";

	private static final String ADVISOR_PROMPT =
		"You are an expert Old School RuneScape advisor with read access to my live game via the osrs-mcp tools. "
			+ "Before answering, gather what you need: get_player (location, account type), get_skills, get_equipment, "
			+ "get_inventory, and when relevant get_bank, get_quests, get_achievement_diaries, get_slayer_task, get_nearby. "
			+ "Respect my account type (ironman restrictions!). Base recommendations on my actual levels, quests and items, "
			+ "say what you checked, and point out missing requirements. If it helps, use highlight_tile / highlight_npc / "
			+ "set_hint_arrow / show_hint to point things out in my client. "
			+ "To tidy my bank, use filter_bank or show_bank_layout (I must have the bank open). "
			+ "To suggest Grand Exchange trades, use stage_ge_offer(s); I place and confirm the offers myself.";

	private final Gson gson;
	private final ToolRegistry registry;

	public McpDispatcher(Gson gson, ToolRegistry registry)
	{
		this.gson = gson;
		this.registry = registry;
	}

	/** Handle one JSON-RPC message. Returns null when no response should be sent. */
	public JsonObject handle(JsonElement message)
	{
		if (message == null || !message.isJsonObject())
		{
			return error(null, INVALID_REQUEST, "Expected a single JSON-RPC object");
		}
		JsonObject req = message.getAsJsonObject();
		JsonElement id = req.get("id");
		JsonElement methodEl = req.get("method");
		if (methodEl == null || !methodEl.isJsonPrimitive())
		{
			// A response from the client (we never send requests) or garbage
			return id == null ? null : error(id, INVALID_REQUEST, "Missing method");
		}
		if (id == null || id.isJsonNull())
		{
			// Notification (e.g. notifications/initialized): no response
			return null;
		}

		String method = methodEl.getAsString();
		JsonObject params = req.has("params") && req.get("params").isJsonObject()
			? req.getAsJsonObject("params") : new JsonObject();

		try
		{
			switch (method)
			{
				case "initialize":
					return result(id, initialize(params));
				case "ping":
					return result(id, new JsonObject());
				case "tools/list":
					return result(id, listTools());
				case "tools/call":
					return callTool(id, params);
				case "prompts/list":
					return result(id, listPrompts());
				case "prompts/get":
					return getPrompt(id, params);
				default:
					return error(id, METHOD_NOT_FOUND, "Method not found: " + method);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("MCP request {} failed", method, e);
			return error(id, INTERNAL_ERROR, "Internal error: " + e.getMessage());
		}
	}

	private JsonObject initialize(JsonObject params)
	{
		JsonElement pv = params.get("protocolVersion");
		String requested = pv != null && pv.isJsonPrimitive() ? pv.getAsString() : null;
		String version = SUPPORTED_VERSIONS.contains(requested) ? requested : DEFAULT_VERSION;

		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		JsonObject prompts = new JsonObject();
		prompts.addProperty("listChanged", false);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", tools);
		capabilities.add("prompts", prompts);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", SERVER_NAME);
		serverInfo.addProperty("title", "Old School RuneScape (RuneLite)");
		serverInfo.addProperty("version", SERVER_VERSION);

		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", version);
		r.add("capabilities", capabilities);
		r.add("serverInfo", serverInfo);
		r.addProperty("instructions", INSTRUCTIONS);
		return r;
	}

	private JsonObject listTools()
	{
		JsonArray arr = new JsonArray();
		for (Tool t : registry.all())
		{
			JsonObject o = new JsonObject();
			o.addProperty("name", t.name());
			o.addProperty("description", t.description());
			o.add("inputSchema", t.inputSchema());
			arr.add(o);
		}
		JsonObject r = new JsonObject();
		r.add("tools", arr);
		return r;
	}

	private JsonObject callTool(JsonElement id, JsonObject params)
	{
		String name = params.has("name") ? params.get("name").getAsString() : null;
		Tool tool = name == null ? null : registry.get(name);
		if (tool == null)
		{
			return error(id, INVALID_PARAMS, "Unknown tool: " + name);
		}
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
			? params.getAsJsonObject("arguments") : new JsonObject();

		JsonObject r = new JsonObject();
		try
		{
			JsonElement out = tool.call(args);
			r.add("content", textContent(out == null ? "null" : gson.toJson(out)));
			if (out != null && out.isJsonObject())
			{
				r.add("structuredContent", out);
			}
			r.addProperty("isError", false);
		}
		catch (ToolException e)
		{
			r.add("content", textContent(e.getMessage()));
			r.addProperty("isError", true);
		}
		catch (RuntimeException e)
		{
			log.warn("Tool {} threw", name, e);
			r.add("content", textContent("Tool failed: " + e));
			r.addProperty("isError", true);
		}
		return result(id, r);
	}

	private JsonObject listPrompts()
	{
		JsonObject p = new JsonObject();
		p.addProperty("name", "osrs_advisor");
		p.addProperty("title", "OSRS advisor");
		p.addProperty("description", "Answer questions using my live account data");
		JsonArray arr = new JsonArray();
		arr.add(p);
		JsonObject r = new JsonObject();
		r.add("prompts", arr);
		return r;
	}

	private JsonObject getPrompt(JsonElement id, JsonObject params)
	{
		String name = params.has("name") ? params.get("name").getAsString() : null;
		if (!"osrs_advisor".equals(name))
		{
			return error(id, INVALID_PARAMS, "Unknown prompt: " + name);
		}
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", ADVISOR_PROMPT);
		JsonObject msg = new JsonObject();
		msg.addProperty("role", "user");
		msg.add("content", content);
		JsonArray messages = new JsonArray();
		messages.add(msg);
		JsonObject r = new JsonObject();
		r.addProperty("description", "OSRS advisor using live account data");
		r.add("messages", messages);
		return result(id, r);
	}

	private static JsonArray textContent(String text)
	{
		JsonObject c = new JsonObject();
		c.addProperty("type", "text");
		c.addProperty("text", text);
		JsonArray arr = new JsonArray();
		arr.add(c);
		return arr;
	}

	private static JsonObject result(JsonElement id, JsonObject result)
	{
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id);
		r.add("result", result);
		return r;
	}

	static JsonObject error(JsonElement id, int code, String message)
	{
		JsonObject e = new JsonObject();
		e.addProperty("code", code);
		e.addProperty("message", message);
		JsonObject r = new JsonObject();
		r.addProperty("jsonrpc", "2.0");
		r.add("id", id == null ? JsonNull.INSTANCE : id);
		r.add("error", e);
		return r;
	}
}
