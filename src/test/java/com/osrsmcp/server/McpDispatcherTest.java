package com.osrsmcp.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osrsmcp.tools.Tool;
import com.osrsmcp.tools.ToolException;
import com.osrsmcp.tools.ToolRegistry;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class McpDispatcherTest
{
	private final Gson gson = new Gson();
	private McpDispatcher dispatcher;

	private static class FakeTool implements Tool
	{
		private final String name;
		private final boolean fail;

		FakeTool(String name, boolean fail)
		{
			this.name = name;
			this.fail = fail;
		}

		@Override
		public String name()
		{
			return name;
		}

		@Override
		public String description()
		{
			return "desc of " + name;
		}

		@Override
		public JsonObject inputSchema()
		{
			JsonObject s = new JsonObject();
			s.addProperty("type", "object");
			return s;
		}

		@Override
		public JsonElement call(JsonObject args) throws ToolException
		{
			if (fail)
			{
				throw new ToolException("not logged in");
			}
			JsonObject o = new JsonObject();
			o.addProperty("echo", args.has("x") ? args.get("x").getAsString() : "none");
			return o;
		}
	}

	@Before
	public void setUp()
	{
		ToolRegistry registry = new ToolRegistry();
		registry.register(new FakeTool("fake_ok", false));
		registry.register(new FakeTool("fake_fail", true));
		dispatcher = new McpDispatcher(gson, registry);
	}

	private JsonObject send(String json)
	{
		return dispatcher.handle(gson.fromJson(json, JsonElement.class));
	}

	private JsonObject request(String method, String params)
	{
		return send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
			+ (params == null ? "" : ",\"params\":" + params) + "}");
	}

	@Test
	public void initializeEchoesSupportedVersion()
	{
		JsonObject r = request("initialize", "{\"protocolVersion\":\"2025-03-26\"}");
		JsonObject result = r.getAsJsonObject("result");
		assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
		assertEquals(McpDispatcher.SERVER_NAME, result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertEquals(1, r.get("id").getAsInt());
		assertEquals("2.0", r.get("jsonrpc").getAsString());
	}

	@Test
	public void initializeUnknownVersionFallsBack()
	{
		JsonObject r = request("initialize", "{\"protocolVersion\":\"1999-01-01\"}");
		assertEquals("2025-06-18", r.getAsJsonObject("result").get("protocolVersion").getAsString());
	}

	@Test
	public void initializeWithoutVersionFallsBack()
	{
		JsonObject r = request("initialize", "{}");
		assertEquals("2025-06-18", r.getAsJsonObject("result").get("protocolVersion").getAsString());
	}

	@Test
	public void notificationReturnsNull()
	{
		assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
	}

	@Test
	public void pingReturnsEmptyResult()
	{
		JsonObject r = request("ping", null);
		assertEquals(0, r.getAsJsonObject("result").entrySet().size());
	}

	@Test
	public void toolsList()
	{
		JsonObject r = request("tools/list", null);
		assertEquals(2, r.getAsJsonObject("result").getAsJsonArray("tools").size());
		JsonObject first = r.getAsJsonObject("result").getAsJsonArray("tools").get(0).getAsJsonObject();
		assertEquals("fake_ok", first.get("name").getAsString());
		assertEquals("desc of fake_ok", first.get("description").getAsString());
		assertEquals("object", first.getAsJsonObject("inputSchema").get("type").getAsString());
	}

	@Test
	public void toolsCallSuccess()
	{
		JsonObject r = request("tools/call", "{\"name\":\"fake_ok\",\"arguments\":{\"x\":\"hi\"}}");
		JsonObject result = r.getAsJsonObject("result");
		assertFalse(result.get("isError").getAsBoolean());
		JsonObject text = result.getAsJsonArray("content").get(0).getAsJsonObject();
		assertEquals("text", text.get("type").getAsString());
		assertTrue(text.get("text").getAsString().contains("\"echo\":\"hi\""));
		assertEquals("hi", result.getAsJsonObject("structuredContent").get("echo").getAsString());
	}

	@Test
	public void toolsCallToolExceptionIsError()
	{
		JsonObject r = request("tools/call", "{\"name\":\"fake_fail\"}");
		JsonObject result = r.getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		assertEquals("not logged in",
			result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
	}

	@Test
	public void unknownToolIsInvalidParams()
	{
		JsonObject r = request("tools/call", "{\"name\":\"nope\"}");
		assertEquals(-32602, r.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void unknownMethod()
	{
		JsonObject r = request("bogus/method", null);
		assertEquals(-32601, r.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void promptsListAndGet()
	{
		JsonObject list = request("prompts/list", null);
		assertEquals("osrs_advisor", list.getAsJsonObject("result").getAsJsonArray("prompts")
			.get(0).getAsJsonObject().get("name").getAsString());

		JsonObject get = request("prompts/get", "{\"name\":\"osrs_advisor\"}");
		JsonObject msg = get.getAsJsonObject("result").getAsJsonArray("messages").get(0).getAsJsonObject();
		assertEquals("user", msg.get("role").getAsString());
		assertNotNull(msg.getAsJsonObject("content").get("text"));

		JsonObject bad = request("prompts/get", "{\"name\":\"other\"}");
		assertEquals(-32602, bad.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void nonObjectMessageIsInvalidRequest()
	{
		JsonObject r = dispatcher.handle(gson.fromJson("[1,2]", JsonElement.class));
		assertEquals(-32600, r.getAsJsonObject("error").get("code").getAsInt());
	}
}
