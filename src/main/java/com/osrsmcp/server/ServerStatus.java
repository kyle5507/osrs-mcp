package com.osrsmcp.server;

import lombok.Value;

/** Snapshot of server state shown in the side panel. */
@Value
public class ServerStatus
{
	boolean running;
	int port;
	String token;
	/** Startup error (e.g. port in use), or null. */
	String error;
	int toolCount;

	public String url()
	{
		return "http://127.0.0.1:" + port + "/mcp";
	}
}
