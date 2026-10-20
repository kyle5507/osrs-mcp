package com.osrsmcp.tools;

import java.util.List;

/**
 * A bundle of related tools plus any state they keep. The plugin registers each
 * group on the EventBus (so {@code @Subscribe} methods on the group itself work),
 * calls {@link #startUp()} / {@link #shutDown()}, and adds {@link #tools()} to the registry.
 */
public interface ToolGroup
{
	List<Tool> tools();

	default void startUp()
	{
	}

	default void shutDown()
	{
	}
}
