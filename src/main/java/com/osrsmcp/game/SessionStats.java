package com.osrsmcp.game;

import java.util.EnumMap;
import java.util.Map;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;

/** Tracks XP at the start of the login session so gains can be computed on demand. */
@Singleton
public class SessionStats
{
	private final Map<Skill, Integer> baseline = new EnumMap<>(Skill.class);
	private long startMillis;

	/** Call every game tick on the client thread; captures a baseline once xp values are loaded. */
	public synchronized void onTick(Client client)
	{
		if (!baseline.isEmpty())
		{
			return;
		}
		if (client.getOverallExperience() <= 0)
		{
			return;
		}
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				baseline.put(skill, client.getSkillExperience(skill));
			}
		}
		startMillis = System.currentTimeMillis();
	}

	public synchronized void reset()
	{
		baseline.clear();
		startMillis = 0;
	}

	public synchronized boolean hasBaseline()
	{
		return !baseline.isEmpty();
	}

	public synchronized long startMillis()
	{
		return startMillis;
	}

	/** XP gained for a skill since the baseline (0 if unknown). Call on the client thread. */
	public synchronized int gained(Client client, Skill skill)
	{
		Integer base = baseline.get(skill);
		return base == null ? 0 : Math.max(0, client.getSkillExperience(skill) - base);
	}
}
