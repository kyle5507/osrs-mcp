package com.osrsmcp.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Streamable-HTTP MCP endpoint at POST /mcp, bound to the loopback interface only.
 * Every request needs "Authorization: Bearer &lt;token&gt;", and browser requests from
 * non-localhost origins are rejected to block DNS-rebinding attacks.
 */
@Slf4j
public class McpHttpServer
{
	static final String PATH = "/mcp";
	private static final int MAX_BODY = 1 << 20;

	private final int port;
	private final Supplier<String> token;
	private final McpDispatcher dispatcher;
	private final Gson gson;

	private HttpServer server;
	private ExecutorService executor;

	public McpHttpServer(int port, Supplier<String> token, McpDispatcher dispatcher, Gson gson)
	{
		this.port = port;
		this.token = token;
		this.dispatcher = dispatcher;
		this.gson = gson;
	}

	public void start() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		executor = Executors.newFixedThreadPool(4, r ->
		{
			Thread t = new Thread(r, "osrs-mcp-http");
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(executor);
		server.createContext(PATH, this::handle);
		server.start();
		log.info("OSRS MCP server listening on http://127.0.0.1:{}{}", getPort(), PATH);
	}

	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
			server = null;
		}
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	public boolean isRunning()
	{
		return server != null;
	}

	/** The bound port (useful when constructed with port 0 in tests). */
	public int getPort()
	{
		return server == null ? port : server.getAddress().getPort();
	}

	private void handle(HttpExchange ex) throws IOException
	{
		try
		{
			if (!PATH.equals(ex.getRequestURI().getPath()))
			{
				send(ex, 404, null);
				return;
			}
			if (!originAllowed(ex.getRequestHeaders().getFirst("Origin")))
			{
				send(ex, 403, McpDispatcher.error(null, McpDispatcher.INVALID_REQUEST, "Origin not allowed"));
				return;
			}
			if (!authorized(ex.getRequestHeaders().getFirst("Authorization")))
			{
				ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
				send(ex, 401, McpDispatcher.error(null, McpDispatcher.INVALID_REQUEST, "Missing or invalid bearer token"));
				return;
			}
			if (!"POST".equalsIgnoreCase(ex.getRequestMethod()))
			{
				// No server-initiated SSE stream and no session to delete
				ex.getResponseHeaders().add("Allow", "POST");
				send(ex, 405, null);
				return;
			}

			byte[] body = readBody(ex.getRequestBody());
			if (body == null)
			{
				send(ex, 413, McpDispatcher.error(null, McpDispatcher.INVALID_REQUEST, "Request too large"));
				return;
			}

			JsonElement msg;
			try
			{
				msg = gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonElement.class);
			}
			catch (JsonParseException e)
			{
				send(ex, 400, McpDispatcher.error(null, McpDispatcher.PARSE_ERROR, "Parse error"));
				return;
			}

			JsonObject response = dispatcher.handle(msg);
			send(ex, response == null ? 202 : 200, response);
		}
		catch (Exception e)
		{
			log.warn("MCP HTTP handler error", e);
			send(ex, 500, null);
		}
		finally
		{
			ex.close();
		}
	}

	private boolean authorized(String header)
	{
		String expected = token.get();
		if (expected == null || expected.isEmpty() || header == null || !header.startsWith("Bearer "))
		{
			return false;
		}
		byte[] given = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(given, expected.getBytes(StandardCharsets.UTF_8));
	}

	static boolean originAllowed(String origin)
	{
		if (origin == null || origin.isEmpty())
		{
			return true; // non-browser clients don't send Origin
		}
		try
		{
			String host = URI.create(origin).getHost();
			return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
				|| "[::1]".equals(host) || "::1".equals(host);
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}

	private static byte[] readBody(InputStream in) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1)
		{
			out.write(buf, 0, n);
			if (out.size() > MAX_BODY)
			{
				return null;
			}
		}
		return out.toByteArray();
	}

	private void send(HttpExchange ex, int status, JsonObject body) throws IOException
	{
		if (body == null)
		{
			ex.sendResponseHeaders(status, -1);
			return;
		}
		byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(bytes);
		}
	}
}
