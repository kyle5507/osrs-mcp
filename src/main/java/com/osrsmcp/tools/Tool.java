package com.osrsmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A single MCP tool. Implementations must be thread-safe: {@link #call} runs on an
 * HTTP worker thread, so any game-state access must go through
 * {@link com.osrsmcp.game.ClientReader}.
 */
public interface Tool
{
	/** snake_case tool name, unique across the server. */
	String name();

	/** Description shown to the model; say what it returns and when to use it. */
	String description();

	/** JSON Schema for the arguments object. Build with {@link Schema}. */
	JsonObject inputSchema();

	/**
	 * Run the tool. Return a JSON object (preferred) which is sent as both text and
	 * structuredContent. Throw {@link ToolException} for user-facing errors.
	 */
	JsonElement call(JsonObject args) throws ToolException;
}
