package net.runelite.cache;

import java.io.File;
import java.util.Set;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import org.junit.Test;

public class TempObjectDumpTest
{
	private static final Set<Integer> INTERESTING = Set.of(
		19044, 19045, 19046, 19047, 19048, 19049, 19050,
		26674, 26688, 26677, 26678, 26675, 26676, 26687,
		357, 26670, 26669, 26707, 26661, 26662, 26663, 26664);

	@Test
	public void dumpLadders() throws Exception
	{
		try (Store store = new Store(new File("/home/reich/.runelite/jagexcache/oldschool/LIVE")))
		{
			store.load();
			ObjectManager manager = new ObjectManager(store);
			manager.load();
			for (int id : INTERESTING)
			{
				ObjectDefinition def = manager.getObject(id);
				if (def == null)
				{
					continue;
				}
				System.out.println("DEF ID " + id + " name=" + def.getName()
					+ " sizeX=" + def.getSizeX() + " sizeY=" + def.getSizeY()
					+ " ops=" + opText(def)
					+ " varbit=" + def.getVarbitID()
					+ " varp=" + def.getVarpID()
					+ " dest=" + java.util.Arrays.toString(def.getConfigChangeDest())
					+ " models=" + java.util.Arrays.toString(def.getObjectModels()));
			}

			RegionLoader loader = new RegionLoader(store, null);
			for (int regionId : new int[]{14935, 14936, 14937, 14679, 14680, 14681})
			{
				Region region = loader.loadRegionFromArchive(regionId);
				if (region == null)
				{
					continue;
				}
				System.out.println("REGION " + regionId + " base=" + region.getBaseX() + "," + region.getBaseY());
				for (Location loc : region.getLocations())
				{
					if (INTERESTING.contains(loc.getId()) || (loc.getId() >= 26661 && loc.getId() <= 26664))
					{
						System.out.println("LOC id=" + loc.getId() + " type=" + loc.getType()
							+ " orient=" + loc.getOrientation()
							+ " pos=" + loc.getPosition().getX() + "," + loc.getPosition().getY()
							+ "," + loc.getPosition().getZ());
					}
				}
			}
		}
	}

	private static String opText(ObjectDefinition def)
	{
		if (def.getOps() == null || def.getOps().getOps() == null)
		{
			return "[]";
		}
		StringBuilder builder = new StringBuilder("[");
		for (int i = 0; i < def.getOps().getOps().size(); ++i)
		{
			EntityOpsDefinition.Op op = def.getOps().getOps().get(i);
			if (i > 0)
			{
				builder.append(", ");
			}
			builder.append(op == null ? "null" : op.text);
		}
		return builder.append(']').toString();
	}
}
