package com.osrsmcp.server;

import com.google.gson.Gson;
import com.osrsmcp.tools.ToolRegistry;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class McpHttpServerTest
{
	private static final String TOKEN = "test-token-123";
	private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

	private static final MediaType JSON = MediaType.parse("application/json");
	private static final OkHttpClient HTTP = new OkHttpClient();

	private McpHttpServer server;

	@Before
	public void setUp() throws IOException
	{
		Gson gson = new Gson();
		ToolRegistry registry = new ToolRegistry();
		server = new McpHttpServer(0, () -> TOKEN, new McpDispatcher(gson, registry), gson);
		server.start();
	}

	@After
	public void tearDown()
	{
		server.stop();
	}

	private int request(String method, String auth, String origin, String body) throws IOException
	{
		Request.Builder b = new Request.Builder().url("http://127.0.0.1:" + server.getPort() + "/mcp");
		if (auth != null)
		{
			b.header("Authorization", auth);
		}
		if (origin != null)
		{
			b.header("Origin", origin);
		}
		b.method(method, body == null ? null : RequestBody.create(JSON, body));
		try (Response r = HTTP.newCall(b.build()).execute())
		{
			return r.code();
		}
	}

	@Test
	public void validTokenOk() throws IOException
	{
		assertTrue(server.isRunning());
		assertEquals(200, request("POST", "Bearer " + TOKEN, null, PING));
	}

	@Test
	public void missingTokenUnauthorized() throws IOException
	{
		assertEquals(401, request("POST", null, null, PING));
	}

	@Test
	public void wrongTokenUnauthorized() throws IOException
	{
		assertEquals(401, request("POST", "Bearer nope", null, PING));
	}

	@Test
	public void evilOriginForbidden() throws IOException
	{
		assertEquals(403, request("POST", "Bearer " + TOKEN, "http://evil.example", PING));
	}

	@Test
	public void localhostOriginOk() throws IOException
	{
		assertEquals(200, request("POST", "Bearer " + TOKEN, "http://localhost:1234", PING));
	}

	@Test
	public void getNotAllowed() throws IOException
	{
		assertEquals(405, request("GET", "Bearer " + TOKEN, null, null));
	}

	@Test
	public void notificationAccepted() throws IOException
	{
		assertEquals(202, request("POST", "Bearer " + TOKEN, null,
			"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
	}

	@Test
	public void malformedJsonBadRequest() throws IOException
	{
		assertEquals(400, request("POST", "Bearer " + TOKEN, null, "{not json"));
	}

	@Test
	public void originAllowedRules()
	{
		assertTrue(McpHttpServer.originAllowed(null));
		assertTrue(McpHttpServer.originAllowed(""));
		assertTrue(McpHttpServer.originAllowed("http://localhost"));
		assertTrue(McpHttpServer.originAllowed("http://localhost:3000"));
		assertTrue(McpHttpServer.originAllowed("https://LOCALHOST"));
		assertTrue(McpHttpServer.originAllowed("http://127.0.0.1:8080"));
		assertTrue(McpHttpServer.originAllowed("http://[::1]:8080"));
		assertFalse(McpHttpServer.originAllowed("http://evil.example"));
		assertFalse(McpHttpServer.originAllowed("http://localhost.evil.example"));
		assertFalse(McpHttpServer.originAllowed("http://127.0.0.1.evil.example"));
		assertFalse(McpHttpServer.originAllowed("null"));
		assertFalse(McpHttpServer.originAllowed("http://bad host"));
	}
}
