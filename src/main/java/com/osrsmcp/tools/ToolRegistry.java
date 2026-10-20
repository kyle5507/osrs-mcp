package com.osrsmcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe registry of tools by name. */
public class ToolRegistry
{
	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public synchronized void register(Tool tool)
	{
		if (tools.putIfAbsent(tool.name(), tool) != null)
		{
			throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
		}
	}

	public synchronized void clear()
	{
		tools.clear();
	}

	public synchronized Tool get(String name)
	{
		return tools.get(name);
	}

	public synchronized Collection<Tool> all()
	{
		return new ArrayList<>(tools.values());
	}
}
