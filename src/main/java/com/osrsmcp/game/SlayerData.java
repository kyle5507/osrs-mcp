package com.osrsmcp.game;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** Reads the current slayer task from varps and the slayer task DB table. Client thread only. */
public final class SlayerData
{
	/** Task id used when the assignment is a boss; the real id is then in SLAYER_TARGET_BOSSID. */
	private static final int BOSS_TASK_ID = 98;

	private SlayerData()
	{
	}

	/** Returns the task creature name, or null if there is no task. */
	public static String taskName(Client client)
	{
		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		if (amount <= 0 || taskId <= 0)
		{
			return null;
		}
		if (taskId == BOSS_TASK_ID)
		{
			taskId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
		}
		List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
		if (rows == null || rows.isEmpty())
		{
			return "Unknown task (id " + taskId + ")";
		}
		Object[] name = client.getDBTableField(rows.get(0), DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
		if (name == null || name.length == 0 || !(name[0] instanceof String))
		{
			return "Unknown task (id " + taskId + ")";
		}
		return (String) name[0];
	}
}
