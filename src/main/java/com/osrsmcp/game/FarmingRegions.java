package com.osrsmcp.game;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lookup of farming patches by "regionId.varbitId" (the key RuneLite's Time Tracking plugin uses).
 * Rows were read from the 1.13.1 client's FarmingWorld constants; that class is package-private so
 * it cannot be called, hence this copy. Unknown keys simply have no name.
 */
public final class FarmingRegions
{
	/** regionId|region name|varbit|patch name (may be empty)|implementation (patch type) */
	private static final String[] ROWS = {
		"13106|Al Kharid|4771||CACTUS",
		"5421|Aldarin|4771||HOPS",
		"9770|Anglers Retreat|4771||HARDWOOD_TREE",
		"10290|Ardougne|4771||BUSH",
		"10548|Ardougne|4771|North|ALLOTMENT",
		"10548|Ardougne|4772|South|ALLOTMENT",
		"10548|Ardougne|4773||FLOWER",
		"10548|Ardougne|4774||HERB",
		"10548|Ardougne|4775||COMPOST",
		"5427|Auburnvale|4771||TREE",
		"5427|Auburnvale|4772||BELLADONNA",
		"6702|Avium Savannah|4771||HARDWOOD_TREE",
		"11058|Brimhaven|4771||FRUIT_TREE",
		"11058|Brimhaven|4772||SPIRIT_TREE",
		"11062|Catherby|4771|North|ALLOTMENT",
		"11062|Catherby|4772|South|ALLOTMENT",
		"11062|Catherby|4773||FLOWER",
		"11062|Catherby|4774||HERB",
		"11062|Catherby|4775||COMPOST",
		"11317|Catherby|4771||FRUIT_TREE",
		"6192|Civitas illa Fortis|4771|North|ALLOTMENT",
		"6192|Civitas illa Fortis|4772|South|ALLOTMENT",
		"6192|Civitas illa Fortis|4773||FLOWER",
		"6192|Civitas illa Fortis|4774||HERB",
		"6192|Civitas illa Fortis|4775||COMPOST",
		"12596|Champions Guild|4771||BUSH",
		"12340|Draynor Manor|4771||BELLADONNA",
		"11060|Entrana|4771||HOPS",
		"10300|Etceteria|4771||BUSH",
		"10300|Etceteria|4772||SPIRIT_TREE",
		"11828|Falador|4771||TREE",
		"12083|Falador|4771|North West|ALLOTMENT",
		"12083|Falador|4772|South East|ALLOTMENT",
		"12083|Falador|4773||FLOWER",
		"12083|Falador|4774||HERB",
		"12083|Falador|4775||COMPOST",
		"14651|Fossil Island|4771|East|HARDWOOD_TREE",
		"14651|Fossil Island|4772|Middle|HARDWOOD_TREE",
		"14651|Fossil Island|4773|West|HARDWOOD_TREE",
		"15008|Seaweed|4771|North|SEAWEED",
		"15008|Seaweed|4772|South|SEAWEED",
		"9781|Gnome Stronghold|4771||TREE",
		"9781|Gnome Stronghold|4772||FRUIT_TREE",
		"12581|Great Conch|4771|East|CORAL",
		"12581|Great Conch|4772|West|CORAL",
		"12581|Great Conch|4773||CALQUAT",
		"15148|Harmony|4771||ALLOTMENT",
		"15148|Harmony|4772||HERB",
		"5423|Kastori|4771||CALQUAT",
		"5423|Kastori|4772||FRUIT_TREE",
		"5423|Kastori|4773||FLOWER",
		"6967|Kourend|4771|North East|ALLOTMENT",
		"6967|Kourend|4772|South West|ALLOTMENT",
		"6967|Kourend|4773||FLOWER",
		"6967|Kourend|4774||HERB",
		"6967|Kourend|4775||COMPOST",
		"6967|Kourend|7904||SPIRIT_TREE",
		"7223|Kourend|4953|East 1|GRAPES",
		"7223|Kourend|4954|East 2|GRAPES",
		"7223|Kourend|4955|East 3|GRAPES",
		"7223|Kourend|4956|East 4|GRAPES",
		"7223|Kourend|4957|East 5|GRAPES",
		"7223|Kourend|4958|East 6|GRAPES",
		"7223|Kourend|4959|West 1|GRAPES",
		"7223|Kourend|4960|West 2|GRAPES",
		"7223|Kourend|4961|West 3|GRAPES",
		"7223|Kourend|4962|West 4|GRAPES",
		"7223|Kourend|4963|West 5|GRAPES",
		"7223|Kourend|4964|West 6|GRAPES",
		"9265|Lletya|4771||FRUIT_TREE",
		"12851|Lumbridge|4771||HOPS",
		"12594|Lumbridge|4771||TREE",
		"13622|Morytania|4771|Mushroom|MUSHROOM",
		"14391|Morytania|4771|North West|ALLOTMENT",
		"14391|Morytania|4772|South East|ALLOTMENT",
		"14391|Morytania|4773||FLOWER",
		"14391|Morytania|4774||HERB",
		"14391|Morytania|4775||COMPOST",
		"12082|Port Sarim|4771||SPIRIT_TREE",
		"11570|Rimmington|4771||BUSH",
		"10551|Seers Village|4771||HOPS",
		"11056|Tai Bwo Wannai|4771||CALQUAT",
		"11573|Taverley|4771||TREE",
		"9777|Tree Gnome Village|4771||FRUIT_TREE",
		"11321|Troll Stronghold|4771||HERB",
		"12854|Varrock|4771||TREE",
		"10288|Yanille|4771||HOPS",
		"11325|Weiss|4771||HERB",
		"5021|Farming Guild|7908|Hespori|HESPORI",
		"4922|Farming Guild|7905||TREE",
		"4922|Farming Guild|4775||HERB",
		"4922|Farming Guild|4772||BUSH",
		"4922|Farming Guild|7906||FLOWER",
		"4922|Farming Guild|4773|North|ALLOTMENT",
		"4922|Farming Guild|4774|South|ALLOTMENT",
		"4922|Farming Guild|7912||BIG_COMPOST",
		"4922|Farming Guild|7904||CACTUS",
		"4922|Farming Guild|4771||SPIRIT_TREE",
		"4922|Farming Guild|7909||FRUIT_TREE",
		"4922|Farming Guild|7911|Anima|ANIMA",
		"4922|Farming Guild|7910||CELASTRUS",
		"4922|Farming Guild|7907||REDWOOD",
		"13151|Prifddinas|4771|North|ALLOTMENT",
		"13151|Prifddinas|4772|South|ALLOTMENT",
		"13151|Prifddinas|4773||FLOWER",
		"13151|Prifddinas|4775||CRYSTAL_TREE",
		"13151|Prifddinas|4774||COMPOST"
	};

	public static final class Patch
	{
		public final int regionId;
		public final String region;
		public final int varbit;
		public final String name;
		public final String type;

		Patch(int regionId, String region, int varbit, String name, String type)
		{
			this.regionId = regionId;
			this.region = region;
			this.varbit = varbit;
			this.name = name;
			this.type = type;
		}

		/** e.g. "Falador herb patch" or "Catherby north allotment patch". */
		public String displayName()
		{
			String t = type.toLowerCase(Locale.ROOT).replace('_', ' ');
			return region + (name.isEmpty() ? "" : " " + name.toLowerCase(Locale.ROOT)) + " " + t + " patch";
		}
	}

	private static final Map<String, Patch> BY_KEY = new HashMap<>();

	static
	{
		for (String row : ROWS)
		{
			String[] p = row.split("[|]", -1);
			int region = Integer.parseInt(p[0]);
			int varbit = Integer.parseInt(p[2]);
			BY_KEY.put(region + "." + varbit, new Patch(region, p[1], varbit, p[3], p[4]));
		}
	}

	private FarmingRegions()
	{
	}

	public static Patch lookup(String key)
	{
		return BY_KEY.get(key);
	}
}
