package com.osrsmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Tiny builder for tool input JSON Schemas. */
public final class Schema
{
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	private Schema()
	{
	}

	public static Schema object()
	{
		return new Schema();
	}

	/** An empty-arguments schema. */
	public static JsonObject none()
	{
		return object().build();
	}

	public Schema prop(String name, String type, String description)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		properties.add(name, p);
		return this;
	}

	public Schema required(String name, String type, String description)
	{
		prop(name, type, description);
		required.add(name);
		return this;
	}

	public JsonObject build()
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		if (required.size() > 0)
		{
			o.add("required", required);
		}
		return o;
	}
}
