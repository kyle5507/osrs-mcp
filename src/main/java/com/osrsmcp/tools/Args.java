package com.osrsmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Helpers for reading tool arguments leniently. */
public final class Args
{
	private Args()
	{
	}

	public static String str(JsonObject args, String key, String def)
	{
		JsonElement e = args == null ? null : args.get(key);
		return e == null || e.isJsonNull() ? def : e.getAsString();
	}

	public static int integer(JsonObject args, String key, int def)
	{
		JsonElement e = args == null ? null : args.get(key);
		if (e == null || e.isJsonNull())
		{
			return def;
		}
		try
		{
			return (int) Math.round(e.getAsDouble());
		}
		catch (NumberFormatException | UnsupportedOperationException ex)
		{
			return def;
		}
	}

	public static Integer optInt(JsonObject args, String key)
	{
		JsonElement e = args == null ? null : args.get(key);
		if (e == null || e.isJsonNull())
		{
			return null;
		}
		return integer(args, key, 0);
	}

	public static int requireInt(JsonObject args, String key) throws ToolException
	{
		Integer v = optInt(args, key);
		if (v == null)
		{
			throw new ToolException("Missing required argument: " + key);
		}
		return v;
	}

	public static boolean bool(JsonObject args, String key, boolean def)
	{
		JsonElement e = args == null ? null : args.get(key);
		return e == null || e.isJsonNull() ? def : e.getAsBoolean();
	}
}
